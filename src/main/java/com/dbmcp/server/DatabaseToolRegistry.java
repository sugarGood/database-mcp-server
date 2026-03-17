package com.dbmcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dbmcp.connection.ConnectionService;
import com.dbmcp.connection.ResolvedConnection;
import com.dbmcp.executor.ExecuteResult;
import com.dbmcp.executor.QueryResult;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DatabaseToolRegistry {

    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;

    public DatabaseToolRegistry(ConnectionService connectionService, ObjectMapper objectMapper) {
        this.connectionService = connectionService;
        this.objectMapper = objectMapper;
    }

    public List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecifications() {
        return List.of(
                syncTool(queryTool(), this::handleQuery),
                syncTool(executeTool(), this::handleExecute),
                syncTool(listSchemasTool(), this::handleListSchemas),
                syncTool(listTablesTool(), this::handleListTables),
                syncTool(describeTableTool(), this::handleDescribeTable),
                syncTool(transactionBeginTool(), this::handleTransactionBegin),
                syncTool(transactionCommitTool(), this::handleTransactionCommit),
                syncTool(transactionRollbackTool(), this::handleTransactionRollback),
                syncTool(connectionCloseTool(), this::handleConnectionClose)
        );
    }

    private McpStatelessServerFeatures.SyncToolSpecification syncTool(
            McpSchema.Tool tool,
            ToolAction action) {
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((transportContext, request) -> {
                    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
                    try {
                        return success(action.execute(arguments));
                    } catch (Exception exception) {
                        return error(messageOf(exception));
                    }
                })
                .build();
    }

    private Object handleQuery(Map<String, Object> arguments) throws Exception {
        String sql = requiredTrimmedText(arguments, "sql");
        ensureReadQuery(sql);

        ResolvedConnection resolved = connectionService.resolve(arguments);
        QueryResult result = resolved.sqlExecutor().query(sql);

        Map<String, Object> payload = new LinkedHashMap<>(result.toMap());
        payload.put("connection_id", resolved.connectionId());
        if (result.truncated()) {
            payload.put("notice", "Result set truncated to " + result.maxRows() + " rows");
        }
        return payload;
    }

    private Object handleExecute(Map<String, Object> arguments) throws Exception {
        String sql = requiredTrimmedText(arguments, "sql");
        ensureMutatingQuery(sql);

        ResolvedConnection resolved = connectionService.resolve(arguments);
        ExecuteResult result = resolved.sqlExecutor().execute(sql);

        Map<String, Object> payload = new LinkedHashMap<>(result.toMap());
        payload.put("status", "ok");
        payload.put("connection_id", resolved.connectionId());
        return payload;
    }

    private Object handleListSchemas(Map<String, Object> arguments) throws Exception {
        ResolvedConnection resolved = connectionService.resolve(arguments);
        QueryResult result = resolved.sqlExecutor().query(resolved.dialect().listSchemasSql());
        List<String> schemas = result.rows().stream()
                .map(this::firstColumnAsString)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connection_id", resolved.connectionId());
        payload.put("schemas", schemas);
        payload.put("count", schemas.size());
        return payload;
    }

    private Object handleListTables(Map<String, Object> arguments) throws Exception {
        ResolvedConnection resolved = connectionService.resolve(arguments);

        String schemaArg = optionalTrimmedText(arguments, "schema");
        String schema = resolved.dialect().normalizeSchema(schemaArg, resolved.config());

        QueryResult result = resolved.sqlExecutor().query(resolved.dialect().listTablesSql(), List.of(schema));
        List<String> tables = result.rows().stream()
                .map(this::firstColumnAsString)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toList());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connection_id", resolved.connectionId());
        payload.put("schema", schema);
        payload.put("tables", tables);
        payload.put("count", tables.size());
        return payload;
    }

    private Object handleDescribeTable(Map<String, Object> arguments) throws Exception {
        ResolvedConnection resolved = connectionService.resolve(arguments);

        String tableArg = requiredTrimmedText(arguments, "table");
        String schemaArg = optionalTrimmedText(arguments, "schema");
        String schema = resolved.dialect().normalizeSchema(schemaArg, resolved.config());
        String table = resolved.dialect().normalizeTable(tableArg);

        QueryResult result = resolved.sqlExecutor().query(resolved.dialect().describeTableSql(), List.of(schema, table));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connection_id", resolved.connectionId());
        payload.put("schema", schema);
        payload.put("table", table);
        payload.put("columns", result.rows());
        payload.put("columnCount", result.rows().size());
        return payload;
    }

    private Object handleTransactionBegin(Map<String, Object> arguments) throws Exception {
        ResolvedConnection resolved = connectionService.resolve(arguments);
        resolved.transactionManager().begin();
        return Map.of(
                "status", "started",
                "inTransaction", true,
                "connection_id", resolved.connectionId()
        );
    }

    private Object handleTransactionCommit(Map<String, Object> arguments) throws Exception {
        ResolvedConnection resolved = connectionService.resolve(arguments);
        resolved.transactionManager().commit();
        return Map.of(
                "status", "committed",
                "inTransaction", false,
                "connection_id", resolved.connectionId()
        );
    }

    private Object handleTransactionRollback(Map<String, Object> arguments) throws Exception {
        ResolvedConnection resolved = connectionService.resolve(arguments);
        resolved.transactionManager().rollback();
        return Map.of(
                "status", "rolled_back",
                "inTransaction", false,
                "connection_id", resolved.connectionId()
        );
    }

    private Object handleConnectionClose(Map<String, Object> arguments) {
        String connectionId = requiredTrimmedText(arguments, "connection_id");
        connectionService.closeSession(connectionId);
        return Map.of(
                "status", "closed",
                "connection_id", connectionId
        );
    }

    private McpSchema.CallToolResult success(Object payload) {
        try {
            String text = payload instanceof String stringPayload
                    ? stringPayload
                    : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            return McpSchema.CallToolResult.builder()
                    .structuredContent(payload)
                    .addTextContent(text)
                    .isError(false)
                    .build();
        } catch (JsonProcessingException exception) {
            return error("Failed to serialize tool result: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult error(String message) {
        String safeMessage = message == null || message.isBlank() ? "Unexpected server error" : message;
        Map<String, Object> payload = Map.of("error", safeMessage);
        return McpSchema.CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(safeMessage)
                .isError(true)
                .build();
    }

    private void ensureReadQuery(String sql) throws SQLException {
        String normalized = sql.trim().toLowerCase();
        if (!(normalized.startsWith("select") || normalized.startsWith("with"))) {
            throw new SQLException("query tool only supports SELECT/WITH statements");
        }
    }

    private void ensureMutatingQuery(String sql) throws SQLException {
        String normalized = sql.trim().toLowerCase();
        if (normalized.startsWith("select") || normalized.startsWith("with")) {
            throw new SQLException("execute tool does not support SELECT/WITH statements");
        }
    }

    private String requiredTrimmedText(Map<String, Object> arguments, String field) {
        Object value = arguments.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Field '" + field + "' is required");
        }
        return value.toString().trim();
    }

    private String optionalTrimmedText(Map<String, Object> arguments, String field) {
        Object value = arguments.get(field);
        return value == null ? null : value.toString().trim();
    }

    private String firstColumnAsString(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object value = row.values().iterator().next();
        return value == null ? null : String.valueOf(value);
    }

    private String messageOf(Exception exception) {
        if (exception.getMessage() == null || exception.getMessage().isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getMessage();
    }

    private McpSchema.Tool queryTool() {
        return McpSchema.Tool.builder()
                .name("query")
                .description("Execute SELECT queries and return rows as JSON")
                .inputSchema(objectSchema(
                        connectionAwareProperties(Map.of(
                                "sql", stringProperty("SELECT or WITH SQL statement")
                        )),
                        List.of("sql")
                ))
                .build();
    }

    private McpSchema.Tool executeTool() {
        return McpSchema.Tool.builder()
                .name("execute")
                .description("Execute DML or DDL statements and return the execution result")
                .inputSchema(objectSchema(
                        connectionAwareProperties(Map.of(
                                "sql", stringProperty("DML or DDL SQL statement")
                        )),
                        List.of("sql")
                ))
                .build();
    }

    private McpSchema.Tool listSchemasTool() {
        return McpSchema.Tool.builder()
                .name("list_schemas")
                .description("List all accessible schemas or databases")
                .inputSchema(objectSchema(connectionAwareProperties(Map.of()), List.of()))
                .build();
    }

    private McpSchema.Tool listTablesTool() {
        return McpSchema.Tool.builder()
                .name("list_tables")
                .description("List tables in the selected schema")
                .inputSchema(objectSchema(
                        connectionAwareProperties(Map.of(
                                "schema", stringProperty("Optional schema or database name")
                        )),
                        List.of()
                ))
                .build();
    }

    private McpSchema.Tool describeTableTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("table", stringProperty("Target table name"));
        properties.put("schema", stringProperty("Optional schema or database name"));
        return McpSchema.Tool.builder()
                .name("describe_table")
                .description("Describe table structure including columns, types, and constraints")
                .inputSchema(objectSchema(connectionAwareProperties(properties), List.of("table")))
                .build();
    }

    private McpSchema.Tool transactionBeginTool() {
        return McpSchema.Tool.builder()
                .name("transaction_begin")
                .description("Begin a database transaction")
                .inputSchema(objectSchema(connectionAwareProperties(Map.of()), List.of()))
                .build();
    }

    private McpSchema.Tool transactionCommitTool() {
        return McpSchema.Tool.builder()
                .name("transaction_commit")
                .description("Commit the current database transaction")
                .inputSchema(objectSchema(connectionAwareProperties(Map.of()), List.of()))
                .build();
    }

    private McpSchema.Tool transactionRollbackTool() {
        return McpSchema.Tool.builder()
                .name("transaction_rollback")
                .description("Rollback the current database transaction")
                .inputSchema(objectSchema(connectionAwareProperties(Map.of()), List.of()))
                .build();
    }

    private McpSchema.Tool connectionCloseTool() {
        return McpSchema.Tool.builder()
                .name("connection_close")
                .description("Close and release a dynamic connection session")
                .inputSchema(objectSchema(
                        Map.of("connection_id", stringProperty("Connection session identifier")),
                        List.of("connection_id")
                ))
                .build();
    }

    private Map<String, Object> connectionAwareProperties(Map<String, Object> baseProperties) {
        Map<String, Object> properties = new LinkedHashMap<>(baseProperties);
        properties.put("connection_id", stringProperty("Reuse an existing dynamic connection"));
        properties.put("connection", connectionObjectProperty());
        return properties;
    }

    private Map<String, Object> connectionObjectProperty() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "object");
        property.put("description", "Database connection parameters");
        property.put("properties", connectionProperties());
        property.put("required", List.of("db_type", "database", "username", "password"));
        property.put("additionalProperties", Boolean.FALSE);
        return property;
    }

    private Map<String, Object> connectionProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("db_type", stringProperty("mysql | postgresql | oracle"));
        properties.put("host", stringProperty("Database host"));
        properties.put("port", integerProperty("Database port"));
        properties.put("database", stringProperty("Database name or Oracle service name"));
        properties.put("username", stringProperty("Database username"));
        properties.put("password", stringProperty("Database password"));
        properties.put("schema", stringProperty("Default schema"));
        properties.put("jdbc_url", stringProperty("Full JDBC URL override"));
        return properties;
    }

    private McpSchema.JsonSchema objectSchema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema(
                "object",
                properties,
                required,
                Boolean.FALSE,
                Map.of(),
                Map.of()
        );
    }

    private Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    @FunctionalInterface
    private interface ToolAction {
        Object execute(Map<String, Object> arguments) throws Exception;
    }
}
