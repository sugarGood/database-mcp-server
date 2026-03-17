package com.dbmcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.dbmcp.config.DatabaseConfig;
import com.dbmcp.connection.ConnectionService;
import com.dbmcp.connection.ConnectionServiceSettings;
import com.dbmcp.server.DatabaseToolRegistry;
import com.dbmcp.server.http.AuthMode;
import com.dbmcp.server.http.AuthSettings;
import com.dbmcp.server.http.HealthServlet;
import com.dbmcp.server.http.McpAuthFilter;
import com.dbmcp.server.http.RequestAuthenticator;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class DatabaseServerApp {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseServerApp.class);
    private static final String CONFIG_FILE = "config.properties";

    private static final String HOST_ENV = "DATABASE_MCP_SERVER_HOST";
    private static final String PORT_ENV = "DATABASE_MCP_SERVER_PORT";
    private static final String MCP_ENDPOINT_ENV = "DATABASE_MCP_ENDPOINT";
    private static final String HEALTH_ENDPOINT_ENV = "DATABASE_MCP_HEALTH_ENDPOINT";
    private static final String AUTH_MODE_ENV = "DATABASE_MCP_AUTH_MODE";
    private static final String API_TOKEN_ENV = "DATABASE_MCP_API_TOKEN";
    private static final String API_TOKENS_ENV = "DATABASE_MCP_API_TOKENS";
    private static final String TRUSTED_AUTH_HEADER_ENV = "DATABASE_MCP_TRUSTED_AUTH_HEADER";
    private static final String ALLOWED_HOSTS_ENV = "DATABASE_MCP_ALLOWED_HOSTS";
    private static final String ALLOWED_ORIGINS_ENV = "DATABASE_MCP_ALLOWED_ORIGINS";

    private static final String SESSION_TTL_ENV = "DATABASE_MCP_SESSION_TTL_SECONDS";
    private static final String POOL_TTL_ENV = "DATABASE_MCP_POOL_TTL_SECONDS";
    private static final String CLEANUP_INTERVAL_ENV = "DATABASE_MCP_CLEANUP_INTERVAL_SECONDS";
    private static final String MAX_SESSIONS_ENV = "DATABASE_MCP_MAX_SESSIONS";
    private static final String MAX_POOLS_ENV = "DATABASE_MCP_MAX_POOLS";

    private static final String DEFAULT_DB_TYPE_ENV = "DATABASE_MCP_DEFAULT_DB_TYPE";
    private static final String DEFAULT_HOST_ENV = "DATABASE_MCP_DEFAULT_HOST";
    private static final String DEFAULT_PORT_ENV = "DATABASE_MCP_DEFAULT_PORT";
    private static final String DEFAULT_DATABASE_ENV = "DATABASE_MCP_DEFAULT_DATABASE";
    private static final String DEFAULT_USERNAME_ENV = "DATABASE_MCP_DEFAULT_USERNAME";
    private static final String DEFAULT_PASSWORD_ENV = "DATABASE_MCP_DEFAULT_PASSWORD";
    private static final String DEFAULT_SCHEMA_ENV = "DATABASE_MCP_DEFAULT_SCHEMA";
    private static final String DEFAULT_JDBC_URL_ENV = "DATABASE_MCP_DEFAULT_JDBC_URL";

    private DatabaseServerApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            LOGGER.warn("Startup arguments are ignored. Configure the server via config.properties or DATABASE_MCP_* environment variables.");
        }

        Properties properties = loadProperties();
        ObjectMapper appObjectMapper = createAppObjectMapper();
        McpJsonMapper mcpJsonMapper = createMcpJsonMapper();

        ServerSettings serverSettings = resolveServerSettings(properties);
        ConnectionServiceSettings connectionSettings = resolveConnectionSettings(properties);
        DatabaseConfig defaultDatabaseConfig = resolveDefaultDatabaseConfig(properties);

        ConnectionService connectionService = new ConnectionService(defaultDatabaseConfig, connectionSettings);
        DatabaseToolRegistry toolRegistry = new DatabaseToolRegistry(connectionService, appObjectMapper);

        HttpServletStatelessServerTransport transport = createTransport(serverSettings, mcpJsonMapper);
        McpStatelessSyncServer mcpServer = McpServer.sync(transport)
                .serverInfo(
                        properties.getProperty("database.server.name", "database-mcp-server"),
                        properties.getProperty("database.server.version", "1.0.0")
                )
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).logging().build())
                .instructions("Query relational databases, inspect schema metadata, and manage transactions.")
                .jsonMapper(mcpJsonMapper)
                .tools(toolRegistry.toolSpecifications())
                .build();

        Server httpServer = createHttpServer(serverSettings, transport, connectionService);
        registerOptionalAuth(serverSettings, httpServer);
        registerShutdownHook(httpServer, mcpServer, connectionService);

        httpServer.start();
        logStartup(serverSettings, connectionSettings, defaultDatabaseConfig, connectionService);
        httpServer.join();
    }

    private static Server createHttpServer(
            ServerSettings settings,
            HttpServletStatelessServerTransport transport,
            ConnectionService connectionService) {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(settings.host());
        connector.setPort(settings.port());
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(transport), settings.mcpEndpoint());
        contextHandler.addServlet(new ServletHolder(new HealthServlet(connectionService)), settings.healthEndpoint());

        server.setHandler(contextHandler);
        server.setStopAtShutdown(true);
        return server;
    }

    private static void registerOptionalAuth(ServerSettings settings, Server httpServer) {
        if (!settings.authSettings().enabled()) {
            return;
        }

        ServletContextHandler contextHandler = (ServletContextHandler) httpServer.getHandler();
        FilterHolder filterHolder = new FilterHolder(new McpAuthFilter(new RequestAuthenticator(settings.authSettings())));
        contextHandler.addFilter(filterHolder, settings.mcpEndpoint(), java.util.EnumSet.of(DispatcherType.REQUEST));
    }

    private static HttpServletStatelessServerTransport createTransport(
            ServerSettings settings,
            McpJsonMapper mcpJsonMapper) {
        HttpServletStatelessServerTransport.Builder builder = HttpServletStatelessServerTransport.builder()
                .messageEndpoint(settings.mcpEndpoint())
                .jsonMapper(mcpJsonMapper);

        if (!settings.allowedHosts().isEmpty() || !settings.allowedOrigins().isEmpty()) {
            DefaultServerTransportSecurityValidator.Builder validatorBuilder =
                    DefaultServerTransportSecurityValidator.builder();
            if (!settings.allowedHosts().isEmpty()) {
                validatorBuilder.allowedHosts(settings.allowedHosts());
            }
            if (!settings.allowedOrigins().isEmpty()) {
                validatorBuilder.allowedOrigins(settings.allowedOrigins());
            }
            builder.securityValidator(validatorBuilder.build());
        }

        return builder.build();
    }

    private static void registerShutdownHook(
            Server httpServer,
            McpStatelessSyncServer mcpServer,
            ConnectionService connectionService) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                mcpServer.closeGracefully().block(Duration.ofSeconds(5));
            } catch (Exception exception) {
                LOGGER.warn("Failed to close MCP server gracefully", exception);
            }

            try {
                connectionService.close();
            } catch (Exception exception) {
                LOGGER.warn("Failed to close connection service", exception);
            }

            try {
                httpServer.stop();
            } catch (Exception exception) {
                LOGGER.warn("Failed to stop HTTP server gracefully", exception);
            }
        }, "database-mcp-shutdown"));
    }

    private static ObjectMapper createAppObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static McpJsonMapper createMcpJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    private static Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream inputStream = DatabaseServerApp.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        }
        return properties;
    }

    private static ServerSettings resolveServerSettings(Properties properties) {
        String host = firstNonBlank(System.getenv(HOST_ENV), properties.getProperty("database.http.host"), "0.0.0.0");
        int port = parsePositiveInt(firstNonBlank(System.getenv(PORT_ENV), properties.getProperty("database.http.port"), "8080"), "server port");
        String mcpEndpoint = normalizeEndpoint(firstNonBlank(
                System.getenv(MCP_ENDPOINT_ENV),
                properties.getProperty("database.http.mcp-endpoint"),
                "/mcp"
        ));
        String healthEndpoint = normalizeEndpoint(firstNonBlank(
                System.getenv(HEALTH_ENDPOINT_ENV),
                properties.getProperty("database.http.health-endpoint"),
                "/health"
        ));
        String apiToken = firstNonBlank(System.getenv(API_TOKEN_ENV), "");
        String apiTokens = firstNonBlank(
                System.getenv(API_TOKENS_ENV),
                properties.getProperty("database.auth.api-tokens"),
                apiToken
        );
        String authModeValue = firstNonBlank(
                System.getenv(AUTH_MODE_ENV),
                properties.getProperty("database.auth.mode"),
                ""
        );
        String trustedUserHeader = firstNonBlank(
                System.getenv(TRUSTED_AUTH_HEADER_ENV),
                properties.getProperty("database.auth.trusted-user-header"),
                "X-Authenticated-User"
        );
        List<String> allowedHosts = parseCsv(firstNonBlank(
                System.getenv(ALLOWED_HOSTS_ENV),
                properties.getProperty("database.http.allowed-hosts"),
                ""
        ));
        List<String> allowedOrigins = parseCsv(firstNonBlank(
                System.getenv(ALLOWED_ORIGINS_ENV),
                properties.getProperty("database.http.allowed-origins"),
                ""
        ));

        return new ServerSettings(
                host,
                port,
                mcpEndpoint,
                healthEndpoint,
                resolveAuthSettings(authModeValue, apiTokens, trustedUserHeader),
                allowedHosts,
                allowedOrigins
        );
    }

    private static ConnectionServiceSettings resolveConnectionSettings(Properties properties) {
        int sessionTtlSeconds = parsePositiveInt(firstNonBlank(
                System.getenv(SESSION_TTL_ENV),
                properties.getProperty("database.connection.session-ttl-seconds"),
                "900"
        ), "session ttl");
        int poolTtlSeconds = parsePositiveInt(firstNonBlank(
                System.getenv(POOL_TTL_ENV),
                properties.getProperty("database.connection.pool-ttl-seconds"),
                "1800"
        ), "pool ttl");
        int cleanupIntervalSeconds = parsePositiveInt(firstNonBlank(
                System.getenv(CLEANUP_INTERVAL_ENV),
                properties.getProperty("database.connection.cleanup-interval-seconds"),
                "60"
        ), "cleanup interval");
        int maxSessions = parsePositiveInt(firstNonBlank(
                System.getenv(MAX_SESSIONS_ENV),
                properties.getProperty("database.connection.max-sessions"),
                "200"
        ), "max sessions");
        int maxPools = parsePositiveInt(firstNonBlank(
                System.getenv(MAX_POOLS_ENV),
                properties.getProperty("database.connection.max-pools"),
                "50"
        ), "max pools");

        return new ConnectionServiceSettings(
                Duration.ofSeconds(sessionTtlSeconds),
                Duration.ofSeconds(poolTtlSeconds),
                Duration.ofSeconds(cleanupIntervalSeconds),
                maxSessions,
                maxPools
        );
    }

    private static DatabaseConfig resolveDefaultDatabaseConfig(Properties properties) {
        Map<String, String> options = new LinkedHashMap<>();

        putIfNonBlank(options, "db-type", firstNonBlank(System.getenv(DEFAULT_DB_TYPE_ENV), properties.getProperty("database.default.db-type")));
        putIfNonBlank(options, "host", firstNonBlank(System.getenv(DEFAULT_HOST_ENV), properties.getProperty("database.default.host")));
        putIfNonBlank(options, "port", firstNonBlank(System.getenv(DEFAULT_PORT_ENV), properties.getProperty("database.default.port")));
        putIfNonBlank(options, "database", firstNonBlank(System.getenv(DEFAULT_DATABASE_ENV), properties.getProperty("database.default.database")));
        putIfNonBlank(options, "username", firstNonBlank(System.getenv(DEFAULT_USERNAME_ENV), properties.getProperty("database.default.username")));
        putIfNonBlank(options, "password", firstNonBlank(System.getenv(DEFAULT_PASSWORD_ENV), properties.getProperty("database.default.password")));
        putIfNonBlank(options, "schema", firstNonBlank(System.getenv(DEFAULT_SCHEMA_ENV), properties.getProperty("database.default.schema")));
        putIfNonBlank(options, "jdbc-url", firstNonBlank(System.getenv(DEFAULT_JDBC_URL_ENV), properties.getProperty("database.default.jdbc-url")));

        if (!hasMeaningfulDefaultDatabaseOptions(options)) {
            return null;
        }

        return DatabaseConfig.fromOptions(options);
    }

    private static boolean hasMeaningfulDefaultDatabaseOptions(Map<String, String> options) {
        return options.containsKey("db-type")
                || options.containsKey("jdbc-url")
                || options.containsKey("database")
                || options.containsKey("username")
                || options.containsKey("password");
    }

    private static void putIfNonBlank(Map<String, String> options, String key, String value) {
        if (value != null && !value.isBlank()) {
            options.put(key, value.trim());
        }
    }

    private static void logStartup(
            ServerSettings serverSettings,
            ConnectionServiceSettings connectionSettings,
            DatabaseConfig defaultDatabaseConfig,
            ConnectionService connectionService) {
        LOGGER.info(
                "Database MCP Server started at http://{}:{}{} (health: {}). sessionTtl={}s, poolTtl={}s, cleanup={}s, maxSessions={}, maxPools={}",
                serverSettings.host(),
                serverSettings.port(),
                serverSettings.mcpEndpoint(),
                serverSettings.healthEndpoint(),
                connectionSettings.sessionIdleTtl().toSeconds(),
                connectionSettings.poolIdleTtl().toSeconds(),
                connectionSettings.cleanupInterval().toSeconds(),
                connectionSettings.maxSessions(),
                connectionSettings.maxPools()
        );
        if (defaultDatabaseConfig == null) {
            LOGGER.info("No default database connection configured. Clients must provide a connection object or reuse connection_id.");
        } else {
            LOGGER.info("Default database connection configured for {} via {}", defaultDatabaseConfig.dbType(), defaultDatabaseConfig.jdbcUrl());
        }
        LOGGER.info(
                "Authentication mode: {}, allowedHosts={}, allowedOrigins={}, activeSessions={}, activePools={}",
                serverSettings.authSettings().mode(),
                serverSettings.allowedHosts(),
                serverSettings.allowedOrigins(),
                connectionService.activeSessionCount(),
                connectionService.activePoolCount()
        );
        if (serverSettings.authSettings().mode() == AuthMode.NONE) {
            LOGGER.warn("Authentication is disabled. Configure DATABASE_MCP_AUTH_MODE and token/header settings before exposing this service publicly.");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static int parsePositiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(label + " must be a positive integer");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, exception);
        }
    }

    private static String normalizeEndpoint(String endpoint) {
        String normalized = endpoint == null || endpoint.isBlank() ? "/" : endpoint.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static AuthSettings resolveAuthSettings(String authModeValue, String apiTokensValue, String trustedUserHeader) {
        Map<String, String> tokenPrincipals = parseTokenPrincipals(apiTokensValue);
        AuthMode mode;
        if (authModeValue == null || authModeValue.isBlank()) {
            mode = tokenPrincipals.isEmpty() ? AuthMode.NONE : AuthMode.BEARER;
        } else {
            mode = AuthMode.fromString(authModeValue);
        }

        return new AuthSettings(mode, tokenPrincipals, trustedUserHeader);
    }

    private static Map<String, String> parseTokenPrincipals(String apiTokensValue) {
        if (apiTokensValue == null || apiTokensValue.isBlank()) {
            return Map.of();
        }

        Map<String, String> tokenPrincipals = new LinkedHashMap<>();
        int unnamedIndex = 1;
        for (String entry : apiTokensValue.split(",")) {
            String trimmedEntry = entry.trim();
            if (trimmedEntry.isBlank()) {
                continue;
            }

            String principal;
            String token;
            int separatorIndex = trimmedEntry.indexOf('=');
            if (separatorIndex > 0) {
                principal = trimmedEntry.substring(0, separatorIndex).trim();
                token = trimmedEntry.substring(separatorIndex + 1).trim();
            } else {
                principal = "client-" + unnamedIndex++;
                token = trimmedEntry;
            }

            if (token.isBlank()) {
                throw new IllegalArgumentException("API token entry must not be blank");
            }
            tokenPrincipals.put(token, principal.isBlank() ? "client-" + unnamedIndex++ : principal);
        }

        return tokenPrincipals;
    }

    private record ServerSettings(
            String host,
            int port,
            String mcpEndpoint,
            String healthEndpoint,
            AuthSettings authSettings,
            List<String> allowedHosts,
            List<String> allowedOrigins) {
    }
}
