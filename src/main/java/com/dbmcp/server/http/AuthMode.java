package com.dbmcp.server.http;

public enum AuthMode {
    NONE,
    BEARER,
    TRUSTED_HEADER,
    BEARER_OR_TRUSTED_HEADER;

    public static AuthMode fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Auth mode must not be blank");
        }

        return switch (value.trim().toLowerCase()) {
            case "none" -> NONE;
            case "bearer" -> BEARER;
            case "trusted-header" -> TRUSTED_HEADER;
            case "bearer-or-trusted-header" -> BEARER_OR_TRUSTED_HEADER;
            default -> throw new IllegalArgumentException("Unsupported auth mode: " + value);
        };
    }
}
