package com.dbmcp.server.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class McpAuthFilter implements Filter {

    public static final String ATTR_PRINCIPAL = "database.auth.principal";
    public static final String ATTR_MECHANISM = "database.auth.mechanism";

    private static final Logger LOGGER = LoggerFactory.getLogger(McpAuthFilter.class);

    private final RequestAuthenticator authenticator;

    public McpAuthFilter(RequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        AuthResult authResult = authenticator.authenticate(extractHeaders(httpRequest));
        if (!authResult.authenticated()) {
            LOGGER.warn("Rejected MCP request from {}: {}", httpRequest.getRemoteAddr(), authResult.message());
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json;charset=UTF-8");
            if (!authResult.wwwAuthenticateHeader().isBlank()) {
                httpResponse.setHeader("WWW-Authenticate", authResult.wwwAuthenticateHeader());
            }
            httpResponse.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"" + escapeJson(authResult.message()) + "\"}");
            return;
        }

        httpRequest.setAttribute(ATTR_PRINCIPAL, authResult.principal());
        httpRequest.setAttribute(ATTR_MECHANISM, authResult.mechanism());
        chain.doFilter(request, response);
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String headerName : Collections.list(request.getHeaderNames())) {
            headers.put(headerName.toLowerCase(), request.getHeader(headerName));
        }
        return headers;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
