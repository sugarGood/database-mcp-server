package com.dbmcp.executor;

import com.dbmcp.transaction.TransactionManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SqlExecutor {

    private final TransactionManager transactionManager;
    private final int maxRows;

    public SqlExecutor(TransactionManager transactionManager) {
        this(transactionManager, 1_000);
    }

    public SqlExecutor(TransactionManager transactionManager, int maxRows) {
        this.transactionManager = transactionManager;
        this.maxRows = maxRows;
    }

    public QueryResult query(String sql) throws SQLException {
        return transactionManager.execute(connection -> {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                return toQueryResult(rs);
            }
        });
    }

    public QueryResult query(String sql, List<Object> parameters) throws SQLException {
        return transactionManager.execute(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                bindParameters(stmt, parameters);
                try (ResultSet rs = stmt.executeQuery()) {
                    return toQueryResult(rs);
                }
            }
        });
    }

    public ExecuteResult execute(String sql) throws SQLException {
        return transactionManager.execute(connection -> {
            try (Statement stmt = connection.createStatement()) {
                int rows = stmt.executeUpdate(sql);
                return new ExecuteResult(rows);
            }
        });
    }

    public ExecuteResult execute(String sql, List<Object> parameters) throws SQLException {
        return transactionManager.execute(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                bindParameters(stmt, parameters);
                int rows = stmt.executeUpdate();
                return new ExecuteResult(rows);
            }
        });
    }

    private QueryResult toQueryResult(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }

        return new QueryResult(rows, truncated, maxRows);
    }

    private void bindParameters(PreparedStatement stmt, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            stmt.setObject(i + 1, parameters.get(i));
        }
    }
}