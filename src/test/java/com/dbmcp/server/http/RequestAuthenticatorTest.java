package com.dbmcp.server.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestAuthenticatorTest {

    @Test
    void authenticatesBearerTokenWithNamedPrincipal() {
        RequestAuthenticator authenticator = new RequestAuthenticator(
                new AuthSettings(AuthMode.BEARER, Map.of("secret-1", "claude"), "X-Authenticated-User")
        );

        AuthResult result = authenticator.authenticate(Map.of("authorization", "Bearer secret-1"));

        assertTrue(result.authenticated());
        assertEquals("claude", result.principal());
        assertEquals("bearer", result.mechanism());
    }

    @Test
    void authenticatesTrustedHeader() {
        RequestAuthenticator authenticator = new RequestAuthenticator(
                new AuthSettings(AuthMode.TRUSTED_HEADER, Map.of(), "X-Authenticated-User")
        );

        AuthResult result = authenticator.authenticate(Map.of("x-authenticated-user", "alice@example.com"));

        assertTrue(result.authenticated());
        assertEquals("alice@example.com", result.principal());
        assertEquals("trusted-header", result.mechanism());
    }

    @Test
    void combinedModeFallsBackToBearer() {
        RequestAuthenticator authenticator = new RequestAuthenticator(
                new AuthSettings(AuthMode.BEARER_OR_TRUSTED_HEADER, Map.of("secret-2", "ops"), "X-Authenticated-User")
        );

        AuthResult result = authenticator.authenticate(Map.of("authorization", "Bearer secret-2"));

        assertTrue(result.authenticated());
        assertEquals("ops", result.principal());
    }

    @Test
    void rejectsMissingAuthentication() {
        RequestAuthenticator authenticator = new RequestAuthenticator(
                new AuthSettings(AuthMode.BEARER, Map.of("secret-1", "claude"), "X-Authenticated-User")
        );

        AuthResult result = authenticator.authenticate(Map.of());

        assertFalse(result.authenticated());
        assertTrue(result.message().contains("Missing Bearer token"));
    }

    @Test
    void validatesBearerModeNeedsTokens() {
        assertThrows(IllegalArgumentException.class, () ->
                new RequestAuthenticator(new AuthSettings(AuthMode.BEARER, Map.of(), "X-Authenticated-User")));
    }
}
