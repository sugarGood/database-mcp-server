package com.dbmcp.server.http;

public record AuthResult(
        boolean authenticated,
        String principal,
        String mechanism,
        String message,
        String wwwAuthenticateHeader) {

    public static AuthResult success(String principal, String mechanism) {
        return new AuthResult(true, principal, mechanism, "", "");
    }

    public static AuthResult failure(String message, String wwwAuthenticateHeader) {
        return new AuthResult(false, "", "", message, wwwAuthenticateHeader == null ? "" : wwwAuthenticateHeader);
    }
}
