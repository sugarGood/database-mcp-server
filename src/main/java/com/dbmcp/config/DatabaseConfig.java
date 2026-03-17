package com.dbmcp.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DatabaseConfig {

    public enum DbType {
        MYSQL,
        POSTGRESQL,
        ORACLE;

        public static DbType fromCli(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required argument: --db-type");
            }

            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "mysql" -> MYSQL;
                case "postgres", "postgresql" -> POSTGRESQL;
                case "oracle" -> ORACLE;
                default -> throw new IllegalArgumentException("Unsupported --db-type: " + value);
            };
        }
    }

    private final DbType dbType;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String schema;
    private final String jdbcUrl;

    private DatabaseConfig(DbType dbType,
                           String host,
                           int port,
                           String database,
                           String username,
                           String password,
                           String schema,
                           String jdbcUrl) {
        this.dbType = Objects.requireNonNull(dbType, "dbType");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.database = Objects.requireNonNull(database, "database");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.schema = schema;
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    public static DatabaseConfig fromArgs(String[] args) {
        Map<String, String> options = parseOptions(args);
        return fromOptions(options);
    }

    public static DatabaseConfig fromOptions(Map<String, String> options) {
        Objects.requireNonNull(options, "options");

        DbType dbType = DbType.fromCli(options.get("db-type"));
        String host = options.getOrDefault("host", "localhost");

        String database = required(options, "database");
        String username = required(options, "username");
        String password = required(options, "password");
        String schema = blankToNull(options.get("schema"));
        String jdbcUrl = blankToNull(options.get("jdbc-url"));

        int port = options.containsKey("port")
            ? parsePort(options.get("port"))
            : defaultPort(dbType);

        if (jdbcUrl == null) {
            jdbcUrl = buildJdbcUrl(dbType, host, port, database, schema);
        }

        return new DatabaseConfig(dbType, host, port, database, username, password, schema, jdbcUrl);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + token + ". Expected --key value pairs.");
            }

            String key = token.substring(2).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Invalid argument name: " + token);
            }

            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for argument: " + token);
            }

            options.put(key, args[++i]);
        }

        return options;
    }

    private static int parsePort(String portText) {
        try {
            int value = Integer.parseInt(portText);
            if (value <= 0 || value > 65535) {
                throw new IllegalArgumentException("Invalid --port, expected 1-65535 but got: " + portText);
            }
            return value;
        }
        catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid --port, not a number: " + portText, ex);
        }
    }

    private static String required(Map<String, String> options, String key) {
        String value = blankToNull(options.get(key));
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: --" + key);
        }
        return value;
    }

    private static String buildJdbcUrl(DbType dbType, String host, int port, String database, String schema) {
        return switch (dbType) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            case POSTGRESQL -> {
                String base = "jdbc:postgresql://" + host + ":" + port + "/" + database;
                if (schema != null) {
                    yield base + "?currentSchema=" + schema;
                }
                yield base;
            }
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        };
    }

    private static int defaultPort(DbType dbType) {
        return switch (dbType) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
            case ORACLE -> 1521;
        };
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public DbType dbType() {
        return dbType;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String schema() {
        return schema;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }
}
