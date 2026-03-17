package com.dbmcp.dialect;

import com.dbmcp.config.DatabaseConfig;

public final class PostgresDialect implements DatabaseDialect {

    @Override
    public String listSchemasSql() {
        return "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
    }

    @Override
    public String listTablesSql() {
        return """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = COALESCE(?, current_schema())
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;
    }

    @Override
    public String describeTableSql() {
        return """
            SELECT
                c.column_name,
                c.data_type,
                c.is_nullable,
                c.column_default,
                CASE
                    WHEN tc.constraint_type = 'PRIMARY KEY' THEN 'PRIMARY KEY'
                    WHEN tc.constraint_type = 'UNIQUE' THEN 'UNIQUE'
                    ELSE NULL
                END AS constraint_type
            FROM information_schema.columns c
            LEFT JOIN information_schema.key_column_usage kcu
                ON c.table_schema = kcu.table_schema
               AND c.table_name = kcu.table_name
               AND c.column_name = kcu.column_name
            LEFT JOIN information_schema.table_constraints tc
                ON kcu.constraint_name = tc.constraint_name
               AND kcu.table_schema = tc.table_schema
               AND kcu.table_name = tc.table_name
            WHERE c.table_schema = COALESCE(?, current_schema())
              AND c.table_name = ?
            ORDER BY c.ordinal_position
            """;
    }

    @Override
    public String defaultSchema(DatabaseConfig config) {
        if (config.schema() != null && !config.schema().isBlank()) {
            return config.schema();
        }
        return "public";
    }
}