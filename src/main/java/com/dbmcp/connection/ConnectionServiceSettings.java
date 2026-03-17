package com.dbmcp.connection;

import java.time.Duration;

public record ConnectionServiceSettings(Duration sessionIdleTtl,
                                        Duration poolIdleTtl,
                                        Duration cleanupInterval,
                                        int maxSessions,
                                        int maxPools) {
}
