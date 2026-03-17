package com.dbmcp.connection;

import com.dbmcp.config.DatabaseConfig;
import com.dbmcp.dialect.DatabaseDialect;
import com.dbmcp.executor.SqlExecutor;
import com.dbmcp.transaction.TransactionManager;

public record ResolvedConnection(String connectionId,
                                 DatabaseConfig config,
                                 DatabaseDialect dialect,
                                 SqlExecutor sqlExecutor,
                                 TransactionManager transactionManager) {
}