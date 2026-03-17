package com.dbmcp.connection;

import com.dbmcp.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public final class ConnectionManager implements AutoCloseable {

    private final HikariDataSource dataSource;

    public ConnectionManager(DatabaseConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());

        hikariConfig.setAutoCommit(true);
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(10_000);
        hikariConfig.setIdleTimeout(300_000);
        hikariConfig.setMaxLifetime(1_800_000);

        switch (config.dbType()) {
            case MYSQL -> hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            case POSTGRESQL -> hikariConfig.setDriverClassName("org.postgresql.Driver");
            case ORACLE -> hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver");
            default -> {
            }
        }

        this.dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}