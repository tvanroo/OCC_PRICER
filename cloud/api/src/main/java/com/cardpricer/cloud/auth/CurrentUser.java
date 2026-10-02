package com.cardpricer.cloud.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * {@code admin} is the platform owner: the configured owner email, verified by Auth0, or (with the CardBox link on)
 * someone CardBox made a platform owner.
 */
public record CurrentUser(UUID userId, UUID tenantId, String role, String name, String email, boolean admin, String auth0Sub) {
    static final String ATTRIBUTE = CurrentUser.class.getName();

    public boolean owner() { return "owner".equals(role); }

    /** Only present on the requests AuthFilter guards. */
    public static CurrentUser of(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(ATTRIBUTE);
    }
}
