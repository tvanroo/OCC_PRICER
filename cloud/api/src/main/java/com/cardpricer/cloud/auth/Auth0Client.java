package com.cardpricer.cloud.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Talks to Auth0 Universal Login for a Regular Web Application: builds the authorize URL (code + PKCE),
 * exchanges the code with the client secret, and validates the returned ID token.
 */
@Component
public class Auth0Client {
    /** Who signed in, from a validated ID token. */
    public record Identity(String sub, String email, boolean emailVerified, String name) {}

    public static class Auth0Exception extends Exception {
        public Auth0Exception(String message) { super(message); }
    }

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile DefaultJWTProcessor<SecurityContext> processor;

    /** {@code issuer} overrides {@code https://<domain>/}; the tests point it at a local stand-in for Auth0. */
    public Auth0Client(@Value("${app.auth0.domain:}") String domain,
                       @Value("${app.auth0.issuer:}") String issuer,
                       @Value("${app.auth0.client-id:}") String clientId,
                       @Value("${app.auth0.client-secret:}") String clientSecret) {
        String base = issuer.isBlank() ? (domain.isBlank() ? "" : "https://" + domain.trim()) : issuer.trim();
        this.issuer = base.isEmpty() || base.endsWith("/") ? base : base + "/";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public boolean configured() {
        return !issuer.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String authorizeUrl(String redirectUri, String state, String nonce, String codeChallenge, boolean signup) {
        var params = new LinkedHashMap<String, String>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", "openid email profile");
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        if (signup) params.put("screen_hint", "signup");
        return issuer + "authorize?" + form(params);
    }

    /** Ends the Auth0 session too, so the next sign-in asks for credentials again. */
    public String logoutUrl(String returnTo) {
        return issuer + "v2/logout?" + form(Map.of("client_id", clientId, "returnTo", returnTo));
    }

    public Identity exchange(String code, String redirectUri, String codeVerifier, String nonce) throws Auth0Exception {
        var body = new LinkedHashMap<String, String>();
        body.put("grant_type", "authorization_code");
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        body.put("code_verifier", codeVerifier);
        JsonNode tokens;
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(issuer + "oauth/token"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new Auth0Exception("Auth0 token exchange failed (" + response.statusCode() + ")");
            tokens = json.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new Auth0Exception("Could not reach Auth0");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Auth0Exception("Interrupted");
        }
        String idToken = tokens.path("id_token").asText("");
        if (idToken.isEmpty()) throw new Auth0Exception("Auth0 returned no ID token");
        JWTClaimsSet claims;
        try {
            claims = processor().process(idToken, null);
        } catch (Exception e) {
            throw new Auth0Exception("Invalid ID token");
        }
        if (!nonce.equals(claims.getClaim("nonce"))) throw new Auth0Exception("Invalid ID token");
        Object verified = claims.getClaim("email_verified");
        Object email = claims.getClaim("email");
        Object name = claims.getClaim("name");
        return new Identity(claims.getSubject(), email instanceof String s ? s : null, Boolean.TRUE.equals(verified),
                name instanceof String s ? s : null);
    }

    /** Built on first use so the app starts (and the free price check works) even if Auth0 is unreachable. */
    private DefaultJWTProcessor<SecurityContext> processor() throws java.net.MalformedURLException {
        if (processor == null) {
            synchronized (this) {
                if (processor == null) {
                    JWKSource<SecurityContext> keys = JWKSourceBuilder.create(new URL(issuer + ".well-known/jwks.json")).build();
                    var p = new DefaultJWTProcessor<SecurityContext>();
                    p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
                    p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(clientId,
                            new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "exp", "iat", "nonce")));
                    processor = p;
                }
            }
        }
        return processor;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
