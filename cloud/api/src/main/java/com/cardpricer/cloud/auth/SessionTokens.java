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
 * Stateless HMAC-signed session tokens: {@code userId.expiryEpochSeconds.signature}.
 * Replaced by Entra External ID sign-in in a later phase.
 */
@Component
public class SessionTokens {
    public static final Duration LIFETIME = Duration.ofHours(12);
    private final byte[] key;

    public SessionTokens(@Value("${app.session-secret}") String secret) {
        if (secret == null || secret.length() < 32)
            throw new IllegalStateException("app.session-secret must be at least 32 characters");
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(UUID userId) {
        String payload = userId + "." + Instant.now().plus(LIFETIME).getEpochSecond();
        return payload + "." + sign(payload);
    }

    public Optional<UUID> verify(String token) {
        if (token == null) return Optional.empty();
        int last = token.lastIndexOf('.');
        if (last < 0) return Optional.empty();
        String payload = token.substring(0, last);
        byte[] expected = sign(payload).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, token.substring(last + 1).getBytes(StandardCharsets.US_ASCII)))
            return Optional.empty();
        String[] parts = payload.split("\\.");
        try {
            if (parts.length != 2 || Instant.now().getEpochSecond() > Long.parseLong(parts[1])) return Optional.empty();
            return Optional.of(UUID.fromString(parts[0]));
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
