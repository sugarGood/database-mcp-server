package com.dbmcp.server.http;

import java.util.Map;

public record AuthSettings(
        AuthMode mode,
        Map<String, String> tokenPrincipals,
        String trustedUserHeader) {

    public AuthSettings {
        tokenPrincipals = Map.copyOf(tokenPrincipals);
    }

    public boolean enabled() {
        return mode != AuthMode.NONE;
    }
}
