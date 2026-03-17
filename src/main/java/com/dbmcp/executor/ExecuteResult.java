package com.dbmcp.executor;

import java.util.Map;

public final class ExecuteResult {

    private final int rowsAffected;

    public ExecuteResult(int rowsAffected) {
        this.rowsAffected = rowsAffected;
    }

    public int rowsAffected() {
        return rowsAffected;
    }

    public Map<String, Object> toMap() {
        return Map.of("rowsAffected", rowsAffected);
    }
}