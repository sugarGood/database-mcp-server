package com.dbmcp.connection;

import com.dbmcp.config.DatabaseConfig;
import com.dbmcp.dialect.DatabaseDialect;
import com.dbmcp.dialect.MySqlDialect;
import com.dbmcp.dialect.OracleDialect;
import com.dbmcp.dialect.PostgresDialect;
import com.dbmcp.executor.SqlExecutor;
import com.dbmcp.transaction.TransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConnectionService implements AutoCloseable {

    private static final List<String> FLAT_CONNECTION_KEYS = List.of(
        "db_type", "dbtype", "db-type",
        "host", "port", "database", "username", "password",
        "schema", "jdbc_url", "jdbc-url"
    );

    private final ConcurrentHashMap<String, ConnectionSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SharedPool> pools = new ConcurrentHashMap<>();
    private final ConnectionServiceSettings settings;
    private final DatabaseConfig defaultConfig;
    private final ScheduledExecutorService cleanupExecutor;

    public ConnectionService(DatabaseConfig defaultConfig, ConnectionServiceSettings settings) {
        this.defaultConfig = defaultConfig;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "dbmcp-connection-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(
            this::cleanupSafely,
            settings.cleanupInterval().toSeconds(),
            settings.cleanupInterval().toSeconds(),
            TimeUnit.SECONDS
        );
    }

    public ResolvedConnection resolve(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");

        String connectionId = optionalString(arguments.get("connection_id"));
        if (connectionId == null) {
            connectionId = optionalString(arguments.get("connectionId"));
        }

        if (connectionId != null) {
            ConnectionSession existing = sessions.get(connectionId);
            if (existing == null) {
                throw new IllegalArgumentException("Unknown connection_id: " + connectionId);
            }
            existing.touch();
            existing.pool().touch();
            return existing.toResolved();
        }

        Map<String, String> optionMap = extractConnectionOptions(arguments);
        DatabaseConfig config;
        if (!optionMap.isEmpty()) {
            config = DatabaseConfig.fromOptions(optionMap);
        }
        else if (defaultConfig != null) {
            config = defaultConfig;
        }
        else {
            throw new IllegalArgumentException("Missing connection parameters. Provide connection_id, or connection object with db_type/host/port/database/username/password");
        }

        return createSession(config).toResolved();
    }

    public void closeSession(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: connection_id");
        }

        ConnectionSession removed = sessions.remove(connectionId);
        if (removed == null) {
            throw new IllegalArgumentException("Unknown connection_id: " + connectionId);
        }

        removed.closeSafely();
        releasePool(removed.pool());
        closeIdlePools(Instant.now());
    }

    public boolean hasDefaultConnection() {
        return defaultConfig != null;
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public int activePoolCount() {
        return pools.size();
    }

    @Override
    public void close() {
        cleanupExecutor.shutdownNow();

        for (ConnectionSession session : sessions.values()) {
            session.closeSafely();
        }
        sessions.clear();

        for (SharedPool pool : pools.values()) {
            pool.closeSafely();
        }
        pools.clear();
    }

    private ConnectionSession createSession(DatabaseConfig config) {
        cleanupExpiredResources();

        if (sessions.size() >= settings.maxSessions()) {
            throw new IllegalStateException("Too many active connection sessions. Close unused sessions or wait for cleanup.");
        }

        SharedPool pool = acquirePool(config);
        String sessionId = nextSessionId();
        ConnectionSession session = new ConnectionSession(
            sessionId,
            config,
            createDialect(config),
            pool,
            new TransactionManager(pool.connectionManager()),
            Instant.now()
        );

        sessions.put(sessionId, session);
        return session;
    }

    private synchronized SharedPool acquirePool(DatabaseConfig config) {
        String poolKey = poolKeyOf(config);
        SharedPool existing = pools.get(poolKey);
        if (existing != null) {
            existing.activeSessions().incrementAndGet();
            existing.touch();
            return existing;
        }

        cleanupExpiredResources();
        if (pools.size() >= settings.maxPools()) {
            throw new IllegalStateException("Too many active datasource pools. Try again later or reduce distinct database targets.");
        }

        SharedPool created = new SharedPool(
            poolKey,
            new ConnectionManager(config),
            new AtomicInteger(1),
            Instant.now()
        );
        pools.put(poolKey, created);
        return created;
    }

    private synchronized void releasePool(SharedPool pool) {
        pool.activeSessions().decrementAndGet();
        pool.touch();
    }

    private void cleanupSafely() {
        try {
            cleanupExpiredResources();
        }
        catch (Exception ignored) {
            // Cleanup is best-effort and should never crash the server.
        }
    }

    private synchronized void cleanupExpiredResources() {
        Instant now = Instant.now();

        for (Map.Entry<String, ConnectionSession> entry : sessions.entrySet()) {
            ConnectionSession session = entry.getValue();
            if (session.lastAccessAt().plus(settings.sessionIdleTtl()).isBefore(now)) {
                if (sessions.remove(entry.getKey(), session)) {
                    session.closeSafely();
                    releasePool(session.pool());
                }
            }
        }

        closeIdlePools(now);
    }

    private synchronized void closeIdlePools(Instant now) {
        for (Map.Entry<String, SharedPool> entry : pools.entrySet()) {
            SharedPool pool = entry.getValue();
            if (pool.activeSessions().get() <= 0 && pool.lastAccessAt().plus(settings.poolIdleTtl()).isBefore(now)) {
                if (pools.remove(entry.getKey(), pool)) {
                    pool.closeSafely();
                }
            }
        }
    }

    private DatabaseDialect createDialect(DatabaseConfig config) {
        return switch (config.dbType()) {
            case MYSQL -> new MySqlDialect();
            case POSTGRESQL -> new PostgresDialect();
            case ORACLE -> new OracleDialect();
        };
    }

    private String poolKeyOf(DatabaseConfig config) {
        String material = String.join("|",
            config.dbType().name(),
            config.jdbcUrl(),
            config.username(),
            config.password()
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return "pool_" + builder;
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String nextSessionId() {
        while (true) {
            String candidate = "conn_" + UUID.randomUUID().toString().replace("-", "");
            if (!sessions.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    private Map<String, String> extractConnectionOptions(Map<String, Object> arguments) {
        Map<String, String> options = new LinkedHashMap<>();

        Object nestedConnection = arguments.get("connection");
        if (nestedConnection instanceof Map<?, ?> nestedMap) {
            nestedMap.forEach((key, value) -> {
                if (key != null) {
                    String normalized = normalizeKey(String.valueOf(key));
                    String asString = optionalString(value);
                    if (asString != null) {
                        options.put(normalized, asString);
                    }
                }
            });
        }

        for (String key : FLAT_CONNECTION_KEYS) {
            if (arguments.containsKey(key)) {
                String asString = optionalString(arguments.get(key));
                if (asString != null) {
                    options.put(normalizeKey(key), asString);
                }
            }
        }

        if (options.containsKey("dbtype") && !options.containsKey("db-type")) {
            options.put("db-type", options.get("dbtype"));
        }

        return options;
    }

    private String normalizeKey(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.equals("dbtype")) {
            return "db-type";
        }
        if (normalized.equals("jdbcurl")) {
            return "jdbc-url";
        }
        return normalized;
    }

    private String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static final class ConnectionSession {

        private final String sessionId;
        private final DatabaseConfig config;
        private final DatabaseDialect dialect;
        private final SharedPool pool;
        private final TransactionManager transactionManager;
        private final SqlExecutor sqlExecutor;
        private volatile Instant lastAccessAt;

        private ConnectionSession(String sessionId,
                                  DatabaseConfig config,
                                  DatabaseDialect dialect,
                                  SharedPool pool,
                                  TransactionManager transactionManager,
                                  Instant lastAccessAt) {
            this.sessionId = sessionId;
            this.config = config;
            this.dialect = dialect;
            this.pool = pool;
            this.transactionManager = transactionManager;
            this.sqlExecutor = new SqlExecutor(transactionManager);
            this.lastAccessAt = lastAccessAt;
        }

        private ResolvedConnection toResolved() {
            return new ResolvedConnection(sessionId, config, dialect, sqlExecutor, transactionManager);
        }

        private void touch() {
            lastAccessAt = Instant.now();
        }

        private Instant lastAccessAt() {
            return lastAccessAt;
        }

        private SharedPool pool() {
            return pool;
        }

        private void closeSafely() {
            try {
                transactionManager.close();
            }
            catch (Exception ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private static final class SharedPool {

        private final String poolKey;
        private final ConnectionManager connectionManager;
        private final AtomicInteger activeSessions;
        private volatile Instant lastAccessAt;

        private SharedPool(String poolKey,
                           ConnectionManager connectionManager,
                           AtomicInteger activeSessions,
                           Instant lastAccessAt) {
            this.poolKey = poolKey;
            this.connectionManager = connectionManager;
            this.activeSessions = activeSessions;
            this.lastAccessAt = lastAccessAt;
        }

        private ConnectionManager connectionManager() {
            return connectionManager;
        }

        private AtomicInteger activeSessions() {
            return activeSessions;
        }

        private Instant lastAccessAt() {
            return lastAccessAt;
        }

        private void touch() {
            lastAccessAt = Instant.now();
        }

        private void closeSafely() {
            connectionManager.close();
        }
    }
}
