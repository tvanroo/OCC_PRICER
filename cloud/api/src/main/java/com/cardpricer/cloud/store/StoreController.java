package com.cardpricer.cloud.store;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.cardpricer.model.BuyRateRule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Store settings: profile, locations, buy rates and the people on the store. Changes are owner-only. */
@RestController
@RequestMapping("/api/app")
public class StoreController {
    public record Rule(@NotNull BigDecimal thresholdMin, @NotNull BigDecimal creditRate, @NotNull BigDecimal checkRate) {}
    public record RatesBody(@NotNull @Size(min = 1, max = 20) List<@Valid Rule> rules) {}
    /** Staff sign in through Auth0 with this email; their account links on their first sign-in. */
    public record StaffBody(@NotBlank @Size(max = 120) String name, @NotBlank @Email String email,
                            @Pattern(regexp = "owner|staff") String role) {}
    public record RoleBody(@NotNull @Pattern(regexp = "owner|staff") String role) {}
    public record ProfileBody(@NotBlank @Size(max = 120) String name, @Size(max = 200) String website,
                              @Size(max = 40) String phone, @Email @Size(max = 200) String contactEmail) {}
    public record LocationBody(@NotBlank @Size(max = 80) String name, @Size(max = 300) String address,
                               @Size(max = 40) String phone, Boolean archived) {}

    private final RateRepository rates;
    private final JdbcTemplate jdbc;

    public StoreController(RateRepository rates, JdbcTemplate jdbc) {
        this.rates = rates;
        this.jdbc = jdbc;
    }

    /** The store profile and its locations; every member reads it so a register can pick its location. */
    @GetMapping("/store")
    public Map<String, Object> store(HttpServletRequest request) {
        UUID tenant = CurrentUser.of(request).tenantId();
        Map<String, Object> store = new HashMap<>(jdbc.queryForMap(
                "SELECT name, website, phone, contact_email AS \"contactEmail\" FROM tenants WHERE id = ?", tenant));
        store.put("locations", jdbc.queryForList("""
                SELECT id, name, address, phone, archived_at IS NOT NULL AS archived FROM locations
                WHERE tenant_id = ? ORDER BY archived_at IS NOT NULL, created_at""", tenant));
        return store;
    }

    @PutMapping("/store")
    public Map<String, Object> saveStore(@Valid @RequestBody ProfileBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        jdbc.update("UPDATE tenants SET name = ?, website = ?, phone = ?, contact_email = ? WHERE id = ?",
                body.name().trim(), website(body.website()), clean(body.phone()),
                clean(body.contactEmail()).toLowerCase(Locale.ROOT), user.tenantId());
        return store(request);
    }

    @PostMapping("/locations")
    public Map<String, Object> addLocation(@Valid @RequestBody LocationBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        try {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, address, phone) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), user.tenantId(), body.name().trim(), clean(body.address()), clean(body.phone()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "This store already has a location with that name");
        }
        return store(request);
    }

    /** Renames or edits a location; {@code archived} closes or reopens it. A store always keeps one open location. */
    @PutMapping("/locations/{id}")
    @Transactional
    public Map<String, Object> saveLocation(@PathVariable UUID id, @Valid @RequestBody LocationBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        // Lock the store so two owners can't each archive one of the last two open locations.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, user.tenantId());
        var current = jdbc.queryForList("SELECT archived_at IS NOT NULL FROM locations WHERE id = ? AND tenant_id = ?",
                Boolean.class, id, user.tenantId());
        if (current.isEmpty()) throw ApiException.notFound("Location not found");
        boolean archive = body.archived() != null ? body.archived() : current.getFirst();
        if (archive && !current.getFirst()) {
            Integer open = jdbc.queryForObject("SELECT count(*) FROM locations WHERE tenant_id = ? AND archived_at IS NULL",
                    Integer.class, user.tenantId());
            if (open != null && open <= 1) throw ApiException.badRequest("A store needs at least one open location");
        }
        try {
            jdbc.update("""
                    UPDATE locations SET name = ?, address = ?, phone = ?,
                        archived_at = CASE WHEN ? THEN coalesce(archived_at, now()) ELSE NULL END
                    WHERE id = ? AND tenant_id = ?""",
                    body.name().trim(), clean(body.address()), clean(body.phone()), archive, id, user.tenantId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "This store already has a location with that name");
        }
        return store(request);
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
        return jdbc.queryForList("""
                SELECT id, name, email, role, auth0_sub IS NOT NULL AS joined FROM users
                WHERE tenant_id = ? AND removed_at IS NULL ORDER BY role, name""",
                CurrentUser.of(request).tenantId());
    }

    /** Adds a person by email as staff or as another owner. Someone removed earlier is brought back. */
    @PostMapping("/staff")
    public List<Map<String, Object>> addStaff(@Valid @RequestBody StaffBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        String role = body.role() == null ? "staff" : body.role();
        int restored = jdbc.update("""
                UPDATE users SET removed_at = NULL, name = ?, role = ?
                WHERE tenant_id = ? AND lower(email) = ? AND removed_at IS NOT NULL""", body.name().trim(), role, user.tenantId(), email);
        if (restored == 0) {
            try {
                jdbc.update("INSERT INTO users (id, tenant_id, email, name, role) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(), user.tenantId(), email, body.name().trim(), role);
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "An account with that email already exists");
            }
        }
        return staff(request);
    }

    /** Makes someone an owner or staff. The store always keeps at least one owner. */
    @PutMapping("/staff/{id}")
    @Transactional
    public List<Map<String, Object>> setRole(@PathVariable UUID id, @Valid @RequestBody RoleBody body, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        if ("staff".equals(body.role())) keepAnOwner(user.tenantId(), id);
        if (jdbc.update("UPDATE users SET role = ? WHERE id = ? AND tenant_id = ? AND removed_at IS NULL",
                body.role(), id, user.tenantId()) == 0) throw ApiException.notFound("Person not found");
        return staff(request);
    }

    /** Takes someone off the store. Their past trades still show their name. */
    @PostMapping("/staff/{id}/remove")
    @Transactional
    public List<Map<String, Object>> remove(@PathVariable UUID id, HttpServletRequest request) {
        CurrentUser user = requireOwner(request);
        keepAnOwner(user.tenantId(), id);
        if (jdbc.update("UPDATE users SET removed_at = now() WHERE id = ? AND tenant_id = ? AND removed_at IS NULL",
                id, user.tenantId()) == 0) throw ApiException.notFound("Person not found");
        return staff(request);
    }

    /** Refuses a change that would leave the store without an owner when {@code leaving} stops being one. */
    private void keepAnOwner(UUID tenant, UUID leaving) {
        // Lock the store so two owners can't demote each other at the same moment.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE", UUID.class, tenant);
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM users WHERE tenant_id = ? AND role = 'owner' AND removed_at IS NULL AND id <> ?""",
                Integer.class, tenant, leaving);
        if (others == null || others == 0) throw ApiException.badRequest("A store needs at least one owner. Make someone else an owner first.");
    }

    private static CurrentUser requireOwner(HttpServletRequest request) {
        CurrentUser user = CurrentUser.of(request);
        if (!user.owner()) throw ApiException.forbidden("Only a store owner can change this");
        return user;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** Stores the website as a full https link so it can be shown as one; refuses anything but a web address. */
    static String website(String value) {
        String site = clean(value);
        if (site.isEmpty()) return "";
        if (!site.matches("(?i)https?://.*")) site = "https://" + site;
        try {
            var uri = java.net.URI.create(site);
            if (uri.getHost() == null || !uri.getHost().contains(".")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Enter the website as an address like example.com");
        }
        return site;
    }
}
