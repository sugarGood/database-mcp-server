package com.dbmcp.dialect;

import com.dbmcp.config.DatabaseConfig;

public final class MySqlDialect implements DatabaseDialect {

    @Override
    public String listSchemasSql() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String listTablesSql() {
        return """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = COALESCE(?, DATABASE())
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;
    }

    @Override
    public String describeTableSql() {
        return """
            SELECT
                c.column_name,
                c.column_type AS data_type,
                c.is_nullable,
                c.column_default,
                c.column_key,
                c.extra
            FROM information_schema.columns c
            WHERE c.table_schema = COALESCE(?, DATABASE())
              AND c.table_name = ?
            ORDER BY c.ordinal_position
            """;
    }

    @Override
    public String defaultSchema(DatabaseConfig config) {
        if (config.schema() != null && !config.schema().isBlank()) {
            return config.schema();
        }
        return config.database();
    }
}