# Database MCP Server 中文部署与使用手册

本文档面向部署人员、运维人员和 MCP 客户端接入方，说明 `database-mcp-server` 的安装、配置、启动、鉴权和调用方式。

## 1. 架构说明

当前服务采用以下架构：

- MCP 传输：Stateless MCP over HTTP
- HTTP 容器：Jetty 11
- 数据连接管理：HikariCP
- 数据库支持：MySQL / PostgreSQL / Oracle
- 连接模型：默认连接 + 动态连接 + `connection_id` 复用
- 鉴权模型：Bearer Token / Trusted Header / 混合模式

服务不直接把数据库配置写死在代码里，而是优先读取环境变量，适合部署在服务器、Docker、Kubernetes、systemd 等环境中。

## 2. 构建说明

### 2.1 环境要求

- JDK 17
- Maven 3.9+
- 可访问目标数据库

### 2.2 打包

```bash
mvn clean package
```

打包完成后，产物位于：

```text
target/database-mcp-server-1.0.0.jar
```

## 3. 启动方式

### 3.1 零配置启动

```bash
java -jar target/database-mcp-server-1.0.0.jar
```

默认值：

- `DATABASE_MCP_SERVER_HOST=0.0.0.0`
- `DATABASE_MCP_SERVER_PORT=8080`
- `DATABASE_MCP_ENDPOINT=/mcp`
- `DATABASE_MCP_HEALTH_ENDPOINT=/health`

此模式下，客户端每次调用工具都需要传 `connection`，或者复用已有 `connection_id`。

### 3.2 带默认数据库启动

如果你希望服务本身绑定一条默认数据库连接，可配置：

```bash
set DATABASE_MCP_DEFAULT_DB_TYPE=mysql
set DATABASE_MCP_DEFAULT_HOST=127.0.0.1
set DATABASE_MCP_DEFAULT_PORT=3306
set DATABASE_MCP_DEFAULT_DATABASE=demo
set DATABASE_MCP_DEFAULT_USERNAME=root
set DATABASE_MCP_DEFAULT_PASSWORD=secret
java -jar target/database-mcp-server-1.0.0.jar
```

此时客户端未显式传 `connection` / `connection_id` 时，服务会回退到默认数据库连接。

## 4. 配置项说明

### 4.1 HTTP 与安全配置

| 环境变量 | 说明 | 默认值 |
|---|---|---|
| `DATABASE_MCP_SERVER_HOST` | 服务监听地址 | `0.0.0.0` |
| `DATABASE_MCP_SERVER_PORT` | 服务监听端口 | `8080` |
| `DATABASE_MCP_ENDPOINT` | MCP 路径 | `/mcp` |
| `DATABASE_MCP_HEALTH_ENDPOINT` | 健康检查路径 | `/health` |
| `DATABASE_MCP_ALLOWED_HOSTS` | Host 白名单，逗号分隔 | 空 |
| `DATABASE_MCP_ALLOWED_ORIGINS` | Origin 白名单，逗号分隔 | 空 |
| `DATABASE_MCP_AUTH_MODE` | 鉴权模式 | 自动推断 |
| `DATABASE_MCP_API_TOKEN` | 单个 Bearer Token | 空 |
| `DATABASE_MCP_API_TOKENS` | 多个 Bearer Token，支持 `name=token` | 空 |
| `DATABASE_MCP_TRUSTED_AUTH_HEADER` | 可信用户 Header 名 | `X-Authenticated-User` |

### 4.2 连接池与会话配置

| 环境变量 | 说明 | 默认值 |
|---|---|---|
| `DATABASE_MCP_SESSION_TTL_SECONDS` | 连接会话空闲过期时间 | `900` |
| `DATABASE_MCP_POOL_TTL_SECONDS` | 连接池空闲过期时间 | `1800` |
| `DATABASE_MCP_CLEANUP_INTERVAL_SECONDS` | 清理周期 | `60` |
| `DATABASE_MCP_MAX_SESSIONS` | 最大活动连接会话数 | `200` |
| `DATABASE_MCP_MAX_POOLS` | 最大连接池数 | `50` |

### 4.3 默认数据库配置

| 环境变量 | 说明 |
|---|---|
| `DATABASE_MCP_DEFAULT_DB_TYPE` | `mysql` / `postgresql` / `oracle` |
| `DATABASE_MCP_DEFAULT_HOST` | 数据库主机 |
| `DATABASE_MCP_DEFAULT_PORT` | 数据库端口 |
| `DATABASE_MCP_DEFAULT_DATABASE` | 数据库名或 Oracle Service Name |
| `DATABASE_MCP_DEFAULT_USERNAME` | 用户名 |
| `DATABASE_MCP_DEFAULT_PASSWORD` | 密码 |
| `DATABASE_MCP_DEFAULT_SCHEMA` | 默认 schema |
| `DATABASE_MCP_DEFAULT_JDBC_URL` | 完整 JDBC URL，可覆盖 host/port/database |

## 5. 鉴权说明

### 5.1 none

```bash
set DATABASE_MCP_AUTH_MODE=none
```

仅建议本地联调使用。

### 5.2 bearer

```bash
set DATABASE_MCP_AUTH_MODE=bearer
set DATABASE_MCP_API_TOKENS=claude=replace-with-long-random-token,ops=another-secret
```

客户端请求头示例：

```text
Authorization: Bearer replace-with-long-random-token
```

### 5.3 trusted-header

```bash
set DATABASE_MCP_AUTH_MODE=trusted-header
set DATABASE_MCP_TRUSTED_AUTH_HEADER=X-Authenticated-User
```

该模式适合：

- 前面已有 SSO / OAuth2 代理
- 网关已经完成用户认证
- 应用仅信任反向代理注入的用户头

### 5.4 bearer-or-trusted-header

```bash
set DATABASE_MCP_AUTH_MODE=bearer-or-trusted-header
```

适合：

- 一部分客户端走自动化 Bearer Token
- 一部分客户端走网页登录和统一认证网关

## 6. MCP 工具调用手册

### 6.1 `query`

用途：执行 `SELECT` / `WITH` 查询。

请求示例：

```json
{
  "name": "query",
  "arguments": {
    "sql": "SELECT CURRENT_DATE AS today",
    "connection": {
      "db_type": "postgresql",
      "host": "127.0.0.1",
      "port": 5432,
      "database": "demo",
      "username": "postgres",
      "password": "secret"
    }
  }
}
```

### 6.2 `execute`

用途：执行 `INSERT` / `UPDATE` / `DELETE` / DDL。

请求示例：

```json
{
  "name": "execute",
  "arguments": {
    "sql": "CREATE TABLE demo_table(id INT)",
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

### 6.3 `list_schemas`

```json
{
  "name": "list_schemas",
  "arguments": {
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

### 6.4 `list_tables`

```json
{
  "name": "list_tables",
  "arguments": {
    "schema": "public",
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

### 6.5 `describe_table`

```json
{
  "name": "describe_table",
  "arguments": {
    "schema": "public",
    "table": "users",
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

### 6.6 事务工具

开启事务：

```json
{
  "name": "transaction_begin",
  "arguments": {
    "connection": {
      "db_type": "mysql",
      "host": "127.0.0.1",
      "port": 3306,
      "database": "demo",
      "username": "root",
      "password": "secret"
    }
  }
}
```

随后使用同一个 `connection_id` 调用 `execute`，最后再提交：

```json
{
  "name": "transaction_commit",
  "arguments": {
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

或回滚：

```json
{
  "name": "transaction_rollback",
  "arguments": {
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

### 6.7 关闭连接

```json
{
  "name": "connection_close",
  "arguments": {
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

## 7. 连接传参规范

### 7.1 `connection` 对象字段

| 字段 | 说明 | 必填 |
|---|---|---|
| `db_type` | `mysql` / `postgresql` / `oracle` | 是 |
| `host` | 数据库地址 | 否 |
| `port` | 数据库端口 | 否 |
| `database` | 数据库名 / 服务名 | 是 |
| `username` | 用户名 | 是 |
| `password` | 密码 | 是 |
| `schema` | 默认 schema | 否 |
| `jdbc_url` | 完整 JDBC URL | 否 |

### 7.2 `connection_id` 的使用建议

- 首次调用带 `connection`
- 记录返回的 `connection_id`
- 同一轮任务内尽量复用 `connection_id`
- 任务结束后调用 `connection_close`

## 8. 部署手册

### 8.1 直接部署 JAR

```bash
java -jar target/database-mcp-server-1.0.0.jar
```

适合：

- 测试环境
- 单机部署
- 被 systemd 管理的服务

### 8.2 Docker 部署

构建：

```bash
docker build -t database-mcp-server:latest .
```

运行：

```bash
docker run -d \
  --name database-mcp \
  -p 8080:8080 \
  -e DATABASE_MCP_AUTH_MODE=bearer \
  -e DATABASE_MCP_API_TOKENS=claude=replace-with-long-random-token \
  database-mcp-server:latest
```

### 8.3 Docker Compose 部署

1. 复制环境文件

```bash
cp .env.example .env
```

2. 修改 `.env`

3. 启动服务

```bash
docker compose up -d --build
```

### 8.4 systemd 部署

建议目录：

- 程序：`/opt/database-mcp/database-mcp-server.jar`
- 环境文件：`/etc/database-mcp/database-mcp.env`

示例命令：

```bash
sudo mkdir -p /opt/database-mcp /etc/database-mcp
sudo cp target/database-mcp-server-1.0.0.jar /opt/database-mcp/database-mcp-server.jar
sudo cp deploy/systemd/database-mcp.service /etc/systemd/system/database-mcp.service
sudo cp deploy/systemd/database-mcp.env.example /etc/database-mcp/database-mcp.env
sudo systemctl daemon-reload
sudo systemctl enable --now database-mcp
sudo systemctl status database-mcp
```

### 8.5 Nginx 反向代理

如果使用 Bearer Token，参考：

- `deploy/nginx/database-mcp.conf`

如果使用可信 Header 模式，参考：

- `deploy/nginx/database-mcp-trusted-header.conf`

## 9. 常见问题

### 9.1 `Unknown connection_id`

原因：服务内已不存在该连接会话，可能已过期或服务已重启。

处理：重新传 `connection` 发起一次调用，获取新的 `connection_id`。

### 9.2 `Missing connection parameters`

原因：服务没有默认数据库连接，而本次调用也没有传 `connection` 或 `connection_id`。

处理：补传 `connection` 或先初始化一次连接。

### 9.3 `query tool only supports SELECT/WITH statements`

原因：把更新类 SQL 发给了 `query` 工具。

处理：改用 `execute`。

### 9.4 `execute tool does not support SELECT/WITH statements`

原因：把查询类 SQL 发给了 `execute` 工具。

处理：改用 `query`。

## 10. 生产建议

- 生产环境优先通过 Nginx / API Gateway 暴露，不建议直接裸露 Java 端口。
- 尽量启用 `bearer` 或 `trusted-header`，不要对公网使用 `none`。
- 给数据库账号最小权限，区分读账号和写账号。
- 通过环境变量、密钥管理服务或容器 secret 注入敏感信息。
- 对高风险环境建议限制 `DATABASE_MCP_ALLOWED_HOSTS` / `DATABASE_MCP_ALLOWED_ORIGINS`。

## 附录 A. 主流 AI Agent 接入手册

下面补充几种常见 Agent / IDE 的接入方式，默认你的服务运行在：

- `http://127.0.0.1:8080/mcp`
- 如启用健康检查：`http://127.0.0.1:8080/health`

如果服务启用了 `bearer` 鉴权，请将示例中的 Token 替换为真实值。

### A.1 Codex CLI

接入无鉴权 HTTP MCP：

```bash
codex mcp add database-mcp --url http://127.0.0.1:8080/mcp
```

接入 Bearer Token 保护的 HTTP MCP：

```bash
set DATABASE_MCP_TOKEN=replace-with-your-token
codex mcp add database-mcp --url http://127.0.0.1:8080/mcp --bearer-token-env-var DATABASE_MCP_TOKEN
```

适用场景：

- 本地运行 Codex CLI
- 需要让 Codex 直接调用数据库 MCP 工具
- 不希望把 Token 明文写在命令中

### A.2 Claude Code

接入无鉴权 HTTP MCP：

```bash
claude mcp add --transport http database-mcp http://127.0.0.1:8080/mcp
```

接入带 Bearer Token 的 HTTP MCP：

```bash
claude mcp add --transport http database-mcp http://127.0.0.1:8080/mcp --header "Authorization: Bearer replace-with-your-token"
```

如果你要把配置保存到项目范围，可使用：

```bash
claude mcp add --scope project --transport http database-mcp http://127.0.0.1:8080/mcp
```

适用场景：

- Claude Code 需要把数据库读写、表结构查看、事务控制作为工具能力
- 团队希望在项目级共享 MCP 接入方式

### A.3 Cursor

Cursor 官方文档说明可以通过设置页或 `mcp.json` 配置 MCP 服务器。

一个常见示例：

```json
{
  "mcpServers": {
    "database-mcp": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

如果当前 Cursor 版本支持为 MCP 服务器配置请求头，可进一步加上 Bearer Token：

```json
{
  "mcpServers": {
    "database-mcp": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer replace-with-your-token"
      }
    }
  }
}
```

适用场景：

- 需要在 Cursor 的 Agent / Chat / Composer 中直接调用数据库 MCP
- 希望在 IDE 中完成查询、建表、结构分析、事务测试

### A.4 其他 AI Agent 的通用接入方法

如果某个 Agent 支持 Streamable HTTP MCP，一般需要提供以下信息：

- 服务名称：例如 `database-mcp`
- MCP 地址：`http://127.0.0.1:8080/mcp`
- 鉴权头：如 `Authorization: Bearer <token>`

首次接入推荐验证顺序：

1. 先请求 `/health`，确认服务在线
2. 再在 Agent 中配置 MCP 地址
3. 先执行一个最简单的 `query`
4. 成功后再尝试 `list_schemas`、`list_tables`、`describe_table`

### A.5 推荐的首个测试命令

建议任何 Agent 接入后，先让它调用：

```json
{
  "name": "query",
  "arguments": {
    "sql": "SELECT 1 AS ok",
    "connection": {
      "db_type": "postgresql",
      "host": "127.0.0.1",
      "port": 5432,
      "database": "demo",
      "username": "postgres",
      "password": "secret"
    }
  }
}
```

若返回中包含 `rows` 和 `connection_id`，说明以下链路都已打通：

- Agent 到 MCP 服务的连接
- 鉴权逻辑
- 数据库连接
- 工具执行
- 会话复用
