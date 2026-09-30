package com.cardpricer.cloud.auth;

import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record SignupRequest(@NotBlank @Size(max = 120) String storeName, @NotBlank @Size(max = 120) String name,
                                @NotBlank @Email String email, @NotBlank @Size(min = 10, max = 200) String password) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();
    // Compared against when the email is unknown, so response time does not reveal which emails exist.
    private static final String DUMMY_HASH = PASSWORDS.encode("not-a-real-password");

    private final JdbcTemplate jdbc;
    private final SessionTokens tokens;
    private final int trialDays;
    private final boolean secureCookie;

    public AuthController(JdbcTemplate jdbc, SessionTokens tokens,
                          @Value("${app.trial-days:30}") int trialDays,
                          @Value("${app.secure-cookie:true}") boolean secureCookie) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.trialDays = trialDays;
        this.secureCookie = secureCookie;
    }

    /** Creates a store in trial with default rates (50% credit, 40% check) and its owner. */
    @PostMapping("/signup")
    @Transactional
    public Map<String, Object> signup(@Valid @RequestBody SignupRequest body, HttpServletResponse response) {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, trial_ends_at) VALUES (?, ?, ?)", tenant, body.storeName().trim(),
                Timestamp.from(Instant.now().plus(Duration.ofDays(trialDays))));
        try {
            jdbc.update("INSERT INTO users (id, tenant_id, email, name, password_hash, role) VALUES (?, ?, ?, ?, ?, 'owner')",
                    user, tenant, body.email().trim().toLowerCase(), body.name().trim(), PASSWORDS.encode(body.password()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with that email already exists");
        }
        jdbc.update("INSERT INTO buy_rate_rules (tenant_id, threshold_min, credit_rate, check_rate) VALUES (?, ?, ?, ?)",
                tenant, BigDecimal.ZERO, new BigDecimal("0.50"), new BigDecimal("0.40"));
        setCookie(response, tokens.issue(user), SessionTokens.LIFETIME);
        return me(user);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletResponse response) {
        var rows = jdbc.query("SELECT id, password_hash FROM users WHERE lower(email) = lower(?)",
                (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)), body.email().trim());
        String hash = rows.isEmpty() ? DUMMY_HASH : rows.getFirst().getValue();
        if (!PASSWORDS.matches(body.password(), hash) || rows.isEmpty())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect");
        UUID user = rows.getFirst().getKey();
        setCookie(response, tokens.issue(user), SessionTokens.LIFETIME);
        return me(user);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        setCookie(response, "", Duration.ZERO);
        return Map.of("ok", true);
    }

    /** Who is signed in, or 401. Works even when the trial has ended so the app can say so. */
    @GetMapping("/me")
    public Map<String, Object> current(HttpServletRequest request) {
        var user = tokens.verify(AuthFilter.sessionCookie(request))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in"));
        return me(user);
    }

    private Map<String, Object> me(UUID user) {
        var rows = jdbc.queryForList("""
                SELECT u.name, u.email, u.role, t.name AS store, t.plan_status, t.trial_ends_at,
                       t.plan_status = 'active' OR (t.plan_status = 'trial' AND t.trial_ends_at > now()) AS entitled
                FROM users u JOIN tenants t ON t.id = u.tenant_id WHERE u.id = ?""", user);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in");
        var row = rows.getFirst();
        return Map.of("name", row.get("name"), "email", row.get("email"), "role", row.get("role"),
                "store", row.get("store"), "planStatus", row.get("plan_status"),
                "trialEndsAt", ((Timestamp) row.get("trial_ends_at")).toInstant().toString(),
                "entitled", row.get("entitled"));
    }

    private void setCookie(HttpServletResponse response, String value, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AuthFilter.COOKIE, value)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(maxAge).build().toString());
    }
}
