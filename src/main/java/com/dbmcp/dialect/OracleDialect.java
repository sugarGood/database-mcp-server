package com.dbmcp.dialect;

import com.dbmcp.config.DatabaseConfig;

import java.util.Locale;

public final class OracleDialect implements DatabaseDialect {

    @Override
    public String listSchemasSql() {
        return "SELECT username AS schema_name FROM all_users ORDER BY username";
    }

    @Override
    public String listTablesSql() {
        return """
            SELECT table_name
            FROM all_tables
            WHERE owner = NVL(?, USER)
            ORDER BY table_name
            """;
    }

    @Override
    public String describeTableSql() {
        return """
            SELECT
                c.column_name,
                c.data_type
                    || CASE
                        WHEN c.data_type IN ('VARCHAR2', 'CHAR', 'NCHAR', 'NVARCHAR2')
                            THEN '(' || c.char_length || ')'
                        WHEN c.data_type = 'NUMBER' AND c.data_precision IS NOT NULL
                            THEN '(' || c.data_precision || NVL2(c.data_scale, ',' || c.data_scale, '') || ')'
                        ELSE ''
                    END AS data_type,
                CASE c.nullable WHEN 'Y' THEN 'YES' ELSE 'NO' END AS is_nullable,
                c.data_default AS column_default,
                CASE WHEN pcols.column_name IS NOT NULL THEN 'PRIMARY KEY' ELSE NULL END AS constraint_type
            FROM all_tab_columns c
            LEFT JOIN (
                SELECT acc.owner, acc.table_name, acc.column_name
                FROM all_cons_columns acc
                JOIN all_constraints ac
                  ON ac.owner = acc.owner
                 AND ac.constraint_name = acc.constraint_name
                 AND ac.table_name = acc.table_name
                WHERE ac.constraint_type = 'P'
            ) pcols
              ON pcols.owner = c.owner
             AND pcols.table_name = c.table_name
             AND pcols.column_name = c.column_name
            WHERE c.owner = NVL(?, USER)
              AND c.table_name = ?
            ORDER BY c.column_id
            """;
    }

    @Override
    public String defaultSchema(DatabaseConfig config) {
        if (config.schema() != null && !config.schema().isBlank()) {
            return config.schema().toUpperCase(Locale.ROOT);
        }
        return config.username().toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeSchema(String schema, DatabaseConfig config) {
        String source = schema;
        if (source == null || source.isBlank()) {
            source = defaultSchema(config);
        }
        return source.toUpperCase(Locale.ROOT);
    }

    @Override
    public String normalizeTable(String table) {
        return table == null ? null : table.toUpperCase(Locale.ROOT);
    }
}