package com.cardpricer.cloud.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public record CurrentUser(UUID userId, UUID tenantId, String role, String name, String email) {
    static final String ATTRIBUTE = CurrentUser.class.getName();

    public boolean owner() { return "owner".equals(role); }

    /** Only present on /api/app/** requests, which AuthFilter guards. */
    public static CurrentUser of(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(ATTRIBUTE);
    }
}
