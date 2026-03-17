package com.dbmcp.executor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class QueryResult {

    private final List<Map<String, Object>> rows;
    private final boolean truncated;
    private final int maxRows;

    public QueryResult(List<Map<String, Object>> rows, boolean truncated, int maxRows) {
        this.rows = Collections.unmodifiableList(rows);
        this.truncated = truncated;
        this.maxRows = maxRows;
    }

    public List<Map<String, Object>> rows() {
        return rows;
    }

    public boolean truncated() {
        return truncated;
    }

    public int maxRows() {
        return maxRows;
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "rows", rows,
            "rowCount", rows.size(),
            "truncated", truncated,
            "maxRows", maxRows
        );
    }
}