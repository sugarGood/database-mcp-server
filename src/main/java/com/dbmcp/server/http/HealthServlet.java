package com.dbmcp.server.http;

import com.dbmcp.connection.ConnectionService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class HealthServlet extends HttpServlet {

    private final ConnectionService connectionService;

    public HealthServlet(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"status\":\"UP\",\"defaultConnection\":" + connectionService.hasDefaultConnection()
                        + ",\"activeSessions\":" + connectionService.activeSessionCount()
                        + ",\"activePools\":" + connectionService.activePoolCount()
                        + "}"
        );
    }
}
