package com.dbmcp.transaction;

import com.dbmcp.connection.ConnectionManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

public final class TransactionManager implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final ConnectionManager connectionManager;
    private final Duration timeout;

    private Connection transactionConnection;
    private Instant lastActivityAt;

    public TransactionManager(ConnectionManager connectionManager) {
        this(connectionManager, Duration.ofMinutes(5));
    }

    public TransactionManager(ConnectionManager connectionManager, Duration timeout) {
        this.connectionManager = connectionManager;
        this.timeout = timeout;
    }

    public synchronized void begin() throws SQLException {
        rollbackIfTimedOutLocked();
        if (transactionConnection != null) {
            throw new IllegalStateException("Transaction is already active");
        }

        Connection connection = connectionManager.getConnection();
        connection.setAutoCommit(false);

        transactionConnection = connection;
        lastActivityAt = Instant.now();
    }

    public synchronized void commit() throws SQLException {
        rollbackIfTimedOutLocked();
        if (transactionConnection == null) {
            throw new IllegalStateException("No active transaction");
        }

        try {
            transactionConnection.commit();
        }
        finally {
            closeTransactionConnectionLocked();
        }
    }

    public synchronized void rollback() throws SQLException {
        rollbackIfTimedOutLocked();
        if (transactionConnection == null) {
            throw new IllegalStateException("No active transaction");
        }

        try {
            transactionConnection.rollback();
        }
        finally {
            closeTransactionConnectionLocked();
        }
    }

    public synchronized boolean isInTransaction() {
        rollbackIfTimedOutLocked();
        return transactionConnection != null;
    }

    public <T> T execute(SqlWork<T> work) throws SQLException {
        Connection connection;
        boolean transactionBound;

        synchronized (this) {
            rollbackIfTimedOutLocked();
            if (transactionConnection != null) {
                connection = transactionConnection;
                transactionBound = true;
                lastActivityAt = Instant.now();
            }
            else {
                connection = connectionManager.getConnection();
                transactionBound = false;
            }
        }

        try {
            return work.apply(connection);
        }
        finally {
            if (transactionBound) {
                synchronized (this) {
                    if (transactionConnection == connection) {
                        lastActivityAt = Instant.now();
                    }
                }
            }
            else {
                connection.close();
            }
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (transactionConnection != null) {
            try {
                transactionConnection.rollback();
            }
            finally {
                closeTransactionConnectionLocked();
            }
        }
    }

    private void rollbackIfTimedOutLocked() {
        if (transactionConnection == null || lastActivityAt == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(timeout);
        if (lastActivityAt.isBefore(cutoff)) {
            try {
                transactionConnection.rollback();
            }
            catch (SQLException ignored) {
                // Best effort rollback on timeout.
            }
            finally {
                try {
                    closeTransactionConnectionLocked();
                }
                catch (SQLException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private void closeTransactionConnectionLocked() throws SQLException {
        if (transactionConnection != null) {
            transactionConnection.close();
            transactionConnection = null;
            lastActivityAt = null;
        }
    }
}