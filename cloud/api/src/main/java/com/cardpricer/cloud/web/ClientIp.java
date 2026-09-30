package com.cardpricer.cloud.web;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {
    private ClientIp() {}

    /** Container Apps ingress appends the caller's address to X-Forwarded-For. */
    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
