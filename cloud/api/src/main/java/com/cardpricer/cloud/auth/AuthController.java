package com.cardpricer.cloud.auth;

import com.cardpricer.cloud.web.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Sign-in through Auth0 Universal Login (authorization code + PKCE). The Auth0 user id ({@code sub}) is the
 * user's key, with verified email as the fallback link for accounts added before their first sign-in.
 * Someone who signs in without an account is sent to /signup to name their store.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record SignupRequest(@NotBlank @Size(max = 120) String storeName, @NotBlank @Size(max = 120) String name) {}

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    static final String TRANSACTION_COOKIE = "occ_auth";
    static final String SIGNUP_COOKIE = "occ_signup";
    private static final String TRANSACTION = "auth0-transaction";
    private static final String PENDING = "pending-signup";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final SessionTokens tokens;
    private final Auth0Client auth0;
    private final int trialDays;
    private final boolean secureCookie;
    private final String ownerEmail;

    public AuthController(JdbcTemplate jdbc, SessionTokens tokens, Auth0Client auth0,
                          @Value("${app.trial-days:30}") int trialDays,
                          @Value("${app.secure-cookie:true}") boolean secureCookie,
                          @Value("${app.owner-email:}") String ownerEmail) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.auth0 = auth0;
        this.trialDays = trialDays;
        this.secureCookie = secureCookie;
        this.ownerEmail = ownerEmail.trim();
    }

    /**
     * Starts Universal Login; {@code signup=true} opens Auth0's sign-up screen instead of sign-in, and
     * {@code chooseAccount=true} asks for credentials even when Auth0 remembers an earlier sign-in.
     */
    @GetMapping("/login")
    public void login(@RequestParam(defaultValue = "false") boolean signup,
                      @RequestParam(defaultValue = "false") boolean chooseAccount, HttpServletResponse response) throws IOException {
        if (!auth0.configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Sign-in is not configured");
        String state = random(), nonce = random(), verifier = random();
        setCookie(response, TRANSACTION_COOKIE, tokens.seal(TRANSACTION, String.join(" ", state, nonce, verifier), Duration.ofMinutes(10)),
                Duration.ofMinutes(10), "/api/auth");
        response.sendRedirect(auth0.authorizeUrl(url("/api/auth/callback"), state, nonce, challenge(verifier), signup, chooseAccount));
    }

    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        var transaction = tokens.open(TRANSACTION, cookie(request, TRANSACTION_COOKIE)).map(t -> t.split(" "));
        setCookie(response, TRANSACTION_COOKIE, "", Duration.ZERO, "/api/auth");
        if (error != null) {
            fail(response, "Sign-in was cancelled or refused. Please try again.");
            return;
        }
        if (transaction.isEmpty() || transaction.get().length != 3 || code == null
                || !MessageDigest.isEqual(transaction.get()[0].getBytes(StandardCharsets.UTF_8), String.valueOf(state).getBytes(StandardCharsets.UTF_8))) {
            fail(response, "Your sign-in expired. Please try again.");
            return;
        }
        Auth0Client.Identity identity;
        try {
            identity = auth0.exchange(code, url("/api/auth/callback"), transaction.get()[2], transaction.get()[1]);
        } catch (Auth0Client.Auth0Exception e) {
            log.warn("Auth0 sign-in failed: {}", e.getMessage());
            fail(response, "Sign-in failed. Please try again.");
            return;
        }
        if (!identity.emailVerified() || identity.email() == null) {
            fail(response, "Please verify your email address using the link Auth0 sent you, then sign in again.");
            return;
        }
        String email = identity.email().trim().toLowerCase(Locale.ROOT);

        var bySub = jdbc.queryForList("SELECT id FROM users WHERE auth0_sub = ?", UUID.class, identity.sub());
        UUID user = null;
        if (!bySub.isEmpty()) {
            user = bySub.getFirst();
            try {
                jdbc.update("UPDATE users SET email = ? WHERE id = ? AND email <> ?", email, user, email);
            } catch (DuplicateKeyException ignored) {
                // Another account already uses the new address; keep the old one.
            }
        } else {
            var byEmail = jdbc.query("SELECT id, auth0_sub FROM users WHERE lower(email) = ?",
                    (rs, i) -> Map.entry(rs.getObject(1, UUID.class), Optional.ofNullable(rs.getString(2))), email);
            if (!byEmail.isEmpty()) {
                if (byEmail.getFirst().getValue().isPresent()) {
                    fail(response, "That email already signs in another way. Use the sign-in method you used before.");
                    return;
                }
                user = byEmail.getFirst().getKey();
                jdbc.update("UPDATE users SET auth0_sub = ? WHERE id = ? AND auth0_sub IS NULL", identity.sub(), user);
            }
        }
        if (user == null) {
            String name = identity.name() == null || identity.name().equalsIgnoreCase(email) ? "" : identity.name().replace('\n', ' ');
            setCookie(response, SIGNUP_COOKIE, tokens.seal(PENDING, String.join("\n", identity.sub(), email, name), Duration.ofMinutes(30)),
                    Duration.ofMinutes(30), "/api/auth");
            response.sendRedirect("/signup");
            return;
        }
        setCookie(response, AuthFilter.COOKIE, tokens.issue(user), SessionTokens.LIFETIME, "/");
        response.sendRedirect("/app");
    }

    /** The signed-in Auth0 identity waiting to create a store, or 404. */
    @GetMapping("/pending")
    public Map<String, Object> pending(HttpServletRequest request) {
        var parts = pendingSignup(request);
        return Map.of("email", parts[1], "name", parts[2]);
    }

    /** Creates a store in trial with default rates (50% credit, 40% check), owned by the pending Auth0 identity. */
    @PostMapping("/signup")
    @Transactional
    public Map<String, Object> signup(@Valid @RequestBody SignupRequest body, HttpServletRequest request, HttpServletResponse response) {
        var parts = pendingSignup(request);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, trial_ends_at) VALUES (?, ?, ?)", tenant, body.storeName().trim(),
                Timestamp.from(Instant.now().plus(Duration.ofDays(trialDays))));
        try {
            jdbc.update("INSERT INTO users (id, tenant_id, email, name, auth0_sub, role) VALUES (?, ?, ?, ?, ?, 'owner')",
                    user, tenant, parts[1], body.name().trim(), parts[0]);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with that email already exists. Please sign in.");
        }
        jdbc.update("INSERT INTO buy_rate_rules (tenant_id, threshold_min, credit_rate, check_rate) VALUES (?, ?, ?, ?)",
                tenant, BigDecimal.ZERO, new BigDecimal("0.50"), new BigDecimal("0.40"));
        setCookie(response, SIGNUP_COOKIE, "", Duration.ZERO, "/api/auth");
        setCookie(response, AuthFilter.COOKIE, tokens.issue(user), SessionTokens.LIFETIME, "/");
        return me(user);
    }

    /** Clears our session; the client then visits {@code logoutUrl} to end the Auth0 session as well. */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        setCookie(response, AuthFilter.COOKIE, "", Duration.ZERO, "/");
        return Map.of("logoutUrl", auth0.configured() ? auth0.logoutUrl(url("/")) : "/");
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
                SELECT u.name, u.email, u.role, u.auth0_sub IS NOT NULL AS linked, t.name AS store, t.plan_status, t.trial_ends_at,
                       t.plan_status = 'active' OR (t.plan_status = 'trial' AND t.trial_ends_at > now()) AS entitled
                FROM users u JOIN tenants t ON t.id = u.tenant_id WHERE u.id = ?""", user);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in");
        var row = rows.getFirst();
        return Map.of("name", row.get("name"), "email", row.get("email"), "role", row.get("role"),
                "admin", isAdmin((String) row.get("email"), (Boolean) row.get("linked")),
                "store", row.get("store"), "planStatus", row.get("plan_status"),
                "trialEndsAt", ((Timestamp) row.get("trial_ends_at")).toInstant().toString(),
                "entitled", row.get("entitled"));
    }

    /** Platform owner: the configured email, once Auth0 has verified it belongs to this user. */
    boolean isAdmin(String email, boolean linked) {
        return linked && !ownerEmail.isEmpty() && ownerEmail.equalsIgnoreCase(email);
    }

    private String[] pendingSignup(HttpServletRequest request) {
        return tokens.open(PENDING, cookie(request, SIGNUP_COOKIE)).map(p -> p.split("\n", -1)).filter(p -> p.length == 3)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Please sign in first"));
    }

    private void fail(HttpServletResponse response, String message) throws IOException {
        response.sendRedirect("/login?error=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static String url(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> name.equals(c.getName())).map(Cookie::getValue).findFirst().orElse(null);
    }

    private void setCookie(HttpServletResponse response, String name, String value, Duration maxAge, String path) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path(path).maxAge(maxAge).build().toString());
    }
}
