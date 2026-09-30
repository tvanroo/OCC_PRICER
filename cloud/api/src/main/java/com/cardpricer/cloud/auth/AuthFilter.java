package com.cardpricer.cloud.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * Guards the paid store workflow under /api/app/**: requires a valid session and a store
 * whose subscription is active or still in trial. The free price check is never guarded.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String COOKIE = "occ_session";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final SessionTokens tokens;
    private final JdbcTemplate jdbc;

    public AuthFilter(SessionTokens tokens, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/app/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // CSRF: browsers cannot send a cross-site JSON body without a CORS preflight, which we never allow.
        if (!SAFE_METHODS.contains(request.getMethod())) {
            String type = request.getContentType();
            if (type == null || !type.startsWith("application/json")) {
                reject(response, 415, "Requests must be JSON");
                return;
            }
        }
        var userId = tokens.verify(sessionCookie(request));
        if (userId.isEmpty()) {
            reject(response, 401, "Please sign in");
            return;
        }
        var rows = jdbc.query("""
                SELECT u.id, u.tenant_id, u.role, u.name, u.email,
                       t.plan_status = 'active' OR (t.plan_status = 'trial' AND t.trial_ends_at > now()) AS entitled
                FROM users u JOIN tenants t ON t.id = u.tenant_id WHERE u.id = ?""",
                (rs, i) -> new Object[]{
                        new CurrentUser(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                                rs.getString(4), rs.getString(5)),
                        rs.getBoolean(6)},
                userId.get());
        if (rows.isEmpty()) {
            reject(response, 401, "Please sign in");
            return;
        }
        if (!(Boolean) rows.getFirst()[1]) {
            reject(response, 402, "Your store's subscription has ended");
            return;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, rows.getFirst()[0]);
        chain.doFilter(request, response);
    }

    static String sessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> COOKIE.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
