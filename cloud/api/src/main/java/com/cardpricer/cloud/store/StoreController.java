package com.cardpricer.cloud.store;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.cardpricer.model.BuyRateRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Store settings: buy rates and staff accounts. Changes are owner-only. */
@RestController
@RequestMapping("/api/app")
public class StoreController {
    public record Rule(@NotNull BigDecimal thresholdMin, @NotNull BigDecimal creditRate, @NotNull BigDecimal checkRate) {}
    public record RatesBody(@NotNull @Size(min = 1, max = 20) List<@Valid Rule> rules) {}
    /** Staff sign in through Auth0 with this email; their account links on their first sign-in. */
    public record StaffBody(@NotBlank @Size(max = 120) String name, @NotBlank @Email String email) {}

    private final RateRepository rates;
    private final JdbcTemplate jdbc;

    public StoreController(RateRepository rates, JdbcTemplate jdbc) {
        this.rates = rates;
        this.jdbc = jdbc;
    }

    @GetMapping("/rates")
    public Map<String, Object> rates(HttpServletRequest request) {
        return Map.of("rules", rates.rules(CurrentUser.of(request).tenantId()).stream()
                .map(r -> new Rule(r.thresholdMin, r.creditRate, r.checkRate)).toList());
    }

    @PutMapping("/rates")
    public Map<String, Object> saveRates(@Valid @RequestBody RatesBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        var seen = new HashSet<BigDecimal>();
        List<BuyRateRule> rules = body.rules().stream().map(r -> {
            var rule = new BuyRateRule(r.thresholdMin().setScale(2, java.math.RoundingMode.HALF_UP), r.creditRate(), r.checkRate());
            if (!seen.add(rule.thresholdMin)) throw ApiException.badRequest("Each threshold can only appear once");
            return rule;
        }).toList();
        if (!seen.contains(new BigDecimal("0.00")))
            throw ApiException.badRequest("Include a $0.00 threshold so every card has a rate");
        rates.replace(user.tenantId(), rules);
        return rates(request);
    }

    @GetMapping("/staff")
    public List<Map<String, Object>> staff(HttpServletRequest request) {
        return jdbc.queryForList("SELECT id, name, email, role FROM users WHERE tenant_id = ? ORDER BY role, name",
                CurrentUser.of(request).tenantId());
    }

    @PostMapping("/staff")
    public List<Map<String, Object>> addStaff(@Valid @RequestBody StaffBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        try {
            jdbc.update("INSERT INTO users (id, tenant_id, email, name, role) VALUES (?, ?, ?, ?, 'staff')",
                    UUID.randomUUID(), user.tenantId(), body.email().trim().toLowerCase(), body.name().trim());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with that email already exists");
        }
        return staff(request);
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only the store owner can change this");
        return user;
    }
}
