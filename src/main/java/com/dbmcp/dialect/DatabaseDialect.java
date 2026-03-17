package com.dbmcp.dialect;

import com.dbmcp.config.DatabaseConfig;

public interface DatabaseDialect {

    String listSchemasSql();

    String listTablesSql();

    String describeTableSql();

    default String normalizeSchema(String schema, DatabaseConfig config) {
        if (schema == null || schema.isBlank()) {
            return defaultSchema(config);
        }
        return schema;
    }

    default String normalizeTable(String table) {
        return table;
    }

    default String defaultSchema(DatabaseConfig config) {
        return config.schema();
    }
}