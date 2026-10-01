package com.cardpricer.cloud.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless HMAC-signed tokens: {@code base64url(payload).expiryEpochSeconds.signature}. The signature also covers a
 * purpose, so a sign-in transaction or pending-signup token can never be replayed as a session.
 */
@Component
public class SessionTokens {
    public static final Duration LIFETIME = Duration.ofHours(12);
    private static final String SESSION = "session";
    private final byte[] key;

    public SessionTokens(@Value("${app.session-secret}") String secret) {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("app.session-secret must be at least 32 characters");
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(UUID userId) {
        return seal(SESSION, userId.toString(), LIFETIME);
    }

    public Optional<UUID> verify(String token) {
        try {
            return open(SESSION, token).map(UUID::fromString);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String seal(String purpose, String payload, Duration lifetime) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + Instant.now().plus(lifetime).getEpochSecond();
        return body + "." + sign(purpose + "|" + body);
    }

    public Optional<String> open(String purpose, String token) {
        if (token == null) return Optional.empty();
        int last = token.lastIndexOf('.');
        if (last < 0) return Optional.empty();
        String body = token.substring(0, last);
        byte[] expected = sign(purpose + "|" + body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, token.substring(last + 1).getBytes(StandardCharsets.US_ASCII)))
            return Optional.empty();
        String[] parts = body.split("\\.");
        try {
            if (parts.length != 2 || Instant.now().getEpochSecond() > Long.parseLong(parts[1])) return Optional.empty();
            return Optional.of(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
