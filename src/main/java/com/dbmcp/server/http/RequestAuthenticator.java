package com.dbmcp.server.http;

import java.util.Map;

public class RequestAuthenticator {

    private final AuthSettings settings;

    public RequestAuthenticator(AuthSettings settings) {
        this.settings = settings;
        validateSettings();
    }

    public AuthResult authenticate(Map<String, String> headers) {
        return switch (settings.mode()) {
            case NONE -> AuthResult.success("anonymous", "none");
            case BEARER -> authenticateBearer(headers);
            case TRUSTED_HEADER -> authenticateTrustedHeader(headers);
            case BEARER_OR_TRUSTED_HEADER -> {
                AuthResult trustedHeaderResult = authenticateTrustedHeader(headers);
                if (trustedHeaderResult.authenticated()) {
                    yield trustedHeaderResult;
                }

                AuthResult bearerResult = authenticateBearer(headers);
                if (bearerResult.authenticated()) {
                    yield bearerResult;
                }

                yield AuthResult.failure(
                        trustedHeaderResult.message() + " / " + bearerResult.message(),
                        bearerResult.wwwAuthenticateHeader()
                );
            }
        };
    }

    private AuthResult authenticateBearer(Map<String, String> headers) {
        String authorization = headers.getOrDefault("authorization", "");
        if (!authorization.startsWith("Bearer ")) {
            return AuthResult.failure(
                    "Missing Bearer token",
                    "Bearer realm=\"database-mcp\""
            );
        }

        String token = authorization.substring("Bearer ".length()).trim();
        String principal = settings.tokenPrincipals().get(token);
        if (principal == null || principal.isBlank()) {
            return AuthResult.failure(
                    "Invalid Bearer token",
                    "Bearer realm=\"database-mcp\", error=\"invalid_token\""
            );
        }

        return AuthResult.success(principal, "bearer");
    }

    private AuthResult authenticateTrustedHeader(Map<String, String> headers) {
        String user = headers.getOrDefault(settings.trustedUserHeader().toLowerCase(), "").trim();
        if (user.isBlank()) {
            return AuthResult.failure(
                    "Missing trusted user header: " + settings.trustedUserHeader(),
                    ""
            );
        }

        return AuthResult.success(user, "trusted-header");
    }

    private void validateSettings() {
        if ((settings.mode() == AuthMode.BEARER || settings.mode() == AuthMode.BEARER_OR_TRUSTED_HEADER)
                && settings.tokenPrincipals().isEmpty()) {
            throw new IllegalArgumentException("Auth mode requires at least one API token");
        }

        if ((settings.mode() == AuthMode.TRUSTED_HEADER || settings.mode() == AuthMode.BEARER_OR_TRUSTED_HEADER)
                && (settings.trustedUserHeader() == null || settings.trustedUserHeader().isBlank())) {
            throw new IllegalArgumentException("Trusted header auth requires a non-empty trusted user header");
        }
    }
}
