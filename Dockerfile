FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml .
COPY src src

RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /workspace/target/database-mcp-server-1.0.0.jar /app/database-mcp-server.jar

ENV DATABASE_MCP_SERVER_HOST=0.0.0.0
ENV DATABASE_MCP_SERVER_PORT=8080
ENV DATABASE_MCP_ENDPOINT=/mcp
ENV DATABASE_MCP_HEALTH_ENDPOINT=/health

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/database-mcp-server.jar"]