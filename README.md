# Database MCP Server

`database-mcp-server` 是一个基于 Java 17 的数据库 MCP 服务，面向 MySQL、PostgreSQL、Oracle 三类关系型数据库，提供查询、执行 SQL、查看 Schema、查看表结构、事务控制和连接复用能力。

当前版本已经按照 `knowledge_mcp_server` 的项目骨架完成改造，统一为面向服务部署的 HTTP MCP 形态，而不是早期的本地 CLI 参数驱动模式。

## 1. 项目特点

- 传输协议：Stateless MCP over HTTP
- HTTP 容器：Jetty 11
- MCP SDK：MCP Java SDK 1.0.0
- 数据连接：HikariCP 连接池
- 支持数据库：MySQL / PostgreSQL / Oracle
- 鉴权模式：`none` / `bearer` / `trusted-header` / `bearer-or-trusted-header`
- 健康检查：`/health`
- MCP 入口：`/mcp`
- 支持默认数据库连接，也支持每次调用动态传入连接参数
- 支持 `connection_id` 复用连接，减少重复传递密码

## 2. 当前目录结构

```text
database-mcp-server/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── README.md
├── USAGE.zh-CN.md
├── deploy/
│   ├── nginx/
│   │   ├── database-mcp.conf
│   │   └── database-mcp-trusted-header.conf
│   └── systemd/
│       ├── database-mcp.service
│       └── database-mcp.env.example
├── src/main/java/com/dbmcp/
│   ├── DatabaseMcpServer.java
│   ├── DatabaseServerApp.java
│   ├── config/
│   ├── connection/
│   ├── dialect/
│   ├── executor/
│   ├── server/
│   │   ├── DatabaseToolRegistry.java
│   │   └── http/
│   └── transaction/
├── src/main/resources/
│   ├── config.properties
│   └── logback.xml
└── src/test/java/
```

## 3. MCP Tools

| Tool | 说明 | 必填参数 |
|---|---|---|
| `query` | 执行 `SELECT` / `WITH` 查询 | `sql` |
| `execute` | 执行 DML / DDL 语句 | `sql` |
| `list_schemas` | 列出可访问 schema / database | 无 |
| `list_tables` | 列出指定 schema 下的表 | 无 |
| `describe_table` | 查看表结构 | `table` |
| `transaction_begin` | 开启事务 | 无 |
| `transaction_commit` | 提交事务 | 无 |
| `transaction_rollback` | 回滚事务 | 无 |
| `connection_close` | 主动关闭动态连接会话 | `connection_id` |

说明：除 `connection_close` 外，所有工具都支持以下两种连接方式。

- 传 `connection`：本次调用直接传数据库连接参数。
- 传 `connection_id`：复用此前工具返回的连接会话。

## 4. 配置方式

服务启动后，配置优先级如下：

1. 环境变量 `DATABASE_MCP_*`
2. `src/main/resources/config.properties` 中的默认值

当前版本不再推荐通过命令行参数传默认数据库配置。程序会以 `config.properties + 环境变量` 的方式运行，更适合服务器部署、Docker 和 systemd 场景。

## 5. 快速开始

### 5.1 构建

```bash
mvn clean package
```

产物：

```text
target/database-mcp-server-1.0.0.jar
```

### 5.2 本地启动

```bash
java -jar target/database-mcp-server-1.0.0.jar
```

默认监听：

- Host：`0.0.0.0`
- Port：`8080`
- MCP：`/mcp`
- Health：`/health`

### 5.3 验证健康检查

```bash
curl http://127.0.0.1:8080/health
```

返回示例：

```json
{
  "status": "UP",
  "defaultConnection": false,
  "activeSessions": 0,
  "activePools": 0
}
```

## 6. 默认数据库连接

如果你希望服务启动后就带一条默认数据库连接，可以通过环境变量配置：

```bash
set DATABASE_MCP_DEFAULT_DB_TYPE=postgresql
set DATABASE_MCP_DEFAULT_HOST=127.0.0.1
set DATABASE_MCP_DEFAULT_PORT=5432
set DATABASE_MCP_DEFAULT_DATABASE=demo
set DATABASE_MCP_DEFAULT_USERNAME=postgres
set DATABASE_MCP_DEFAULT_PASSWORD=secret
java -jar target/database-mcp-server-1.0.0.jar
```

此时客户端在调用工具时，可以不传 `connection`，服务会自动使用默认连接。

## 7. 动态连接调用方式

如果不配置默认数据库连接，则需要在工具调用里传 `connection`：

```json
{
  "name": "query",
  "arguments": {
    "sql": "SELECT 1 AS ok",
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

服务返回结果中会附带 `connection_id`，后续可复用：

```json
{
  "name": "list_tables",
  "arguments": {
    "schema": "demo",
    "connection_id": "conn_xxxxxxxxxxxxxxxx"
  }
}
```

更详细的工具调用说明，请查看 [USAGE.zh-CN.md](/C:/Users/76741/Desktop/database-mcp-server/USAGE.zh-CN.md)。

## 8. 鉴权模式

### 8.1 `none`

不做鉴权，只适合本地联调或内网临时测试。

### 8.2 `bearer`

校验 `Authorization: Bearer <token>`。

```bash
set DATABASE_MCP_AUTH_MODE=bearer
set DATABASE_MCP_API_TOKENS=claude=replace-with-long-random-token
java -jar target/database-mcp-server-1.0.0.jar
```

### 8.3 `trusted-header`

由反向代理或统一认证网关完成登录，再把用户身份通过 Header 透传给应用。

```bash
set DATABASE_MCP_AUTH_MODE=trusted-header
set DATABASE_MCP_TRUSTED_AUTH_HEADER=X-Authenticated-User
```

### 8.4 `bearer-or-trusted-header`

同时支持 Bearer Token 和可信 Header，适合迁移期或多入口接入场景。

## 9. 部署手册

### 9.1 Docker

仓库已提供 [Dockerfile](/C:/Users/76741/Desktop/database-mcp-server/Dockerfile)。

构建镜像：

```bash
docker build -t database-mcp-server:latest .
```

运行容器：

```bash
docker run -d ^
  --name database-mcp ^
  -p 8080:8080 ^
  -e DATABASE_MCP_AUTH_MODE=bearer ^
  -e DATABASE_MCP_API_TOKENS=claude=replace-with-long-random-token ^
  database-mcp-server:latest
```

### 9.2 Docker Compose

仓库已提供 [docker-compose.yml](/C:/Users/76741/Desktop/database-mcp-server/docker-compose.yml) 和 [.env.example](/C:/Users/76741/Desktop/database-mcp-server/.env.example)。

先复制环境文件：

```bash
copy .env.example .env
```

再启动：

```bash
docker compose up -d --build
```

### 9.3 systemd

仓库已提供：

- [database-mcp.service](/C:/Users/76741/Desktop/database-mcp-server/deploy/systemd/database-mcp.service)
- [database-mcp.env.example](/C:/Users/76741/Desktop/database-mcp-server/deploy/systemd/database-mcp.env.example)

典型步骤：

1. 把 JAR 放到 `/opt/database-mcp/database-mcp-server.jar`
2. 创建运行用户 `database-mcp`
3. 创建 `/etc/database-mcp/database-mcp.env`
4. 拷贝 service 文件到 `/etc/systemd/system/database-mcp.service`
5. 执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now database-mcp
sudo systemctl status database-mcp
```

### 9.4 Nginx 反向代理

仓库已提供：

- [database-mcp.conf](/C:/Users/76741/Desktop/database-mcp-server/deploy/nginx/database-mcp.conf)
- [database-mcp-trusted-header.conf](/C:/Users/76741/Desktop/database-mcp-server/deploy/nginx/database-mcp-trusted-header.conf)

推荐做法：

1. Java 服务只监听内网地址，如 `127.0.0.1:8080`
2. 由 Nginx 提供 TLS
3. 对公网只暴露 `https://your-domain/mcp`

## 10. 安全建议

- 生产环境不要使用 `none` 模式直接暴露公网。
- 数据库账号尽量使用最小权限用户。
- `DATABASE_MCP_API_TOKENS` 建议使用长随机串，不要提交到仓库。
- 如果使用 `trusted-header`，必须确认反向代理到应用之间是可信网络。
- 如无必要，不建议在客户端长期缓存明文数据库密码。

## 11. 测试

```bash
mvn test
mvn package
```

当前已覆盖鉴权逻辑基础测试，后续可继续补充数据库连接与工具调用级测试。

## 附录 A. 主流 AI Agent 接入方式

以下示例默认你的服务已经启动在：

- MCP 地址：`http://127.0.0.1:8080/mcp`
- 健康检查：`http://127.0.0.1:8080/health`

如果你启用了 `bearer` 鉴权，请把示例中的 Token 替换成你自己的值。

### A.1 Codex CLI / Codex Agent

本机已验证 `codex mcp add` 支持直接接入 Streamable HTTP MCP。

无鉴权接入：

```bash
codex mcp add database-mcp --url http://127.0.0.1:8080/mcp
```

Bearer Token 接入：

```bash
set DATABASE_MCP_TOKEN=replace-with-your-token
codex mcp add database-mcp --url http://127.0.0.1:8080/mcp --bearer-token-env-var DATABASE_MCP_TOKEN
```

说明：

- `--url` 用于接入 HTTP MCP 服务。
- `--bearer-token-env-var` 让 Codex 从环境变量中读取 Token，不需要把密钥直接写进命令历史。
- 如果你后续更换了服务地址或 Token，删除后重新添加对应 MCP 配置即可。

### A.2 Claude Code

本机已验证 `claude mcp add` 支持 `http` 传输和自定义 Header。

无鉴权接入：

```bash
claude mcp add --transport http database-mcp http://127.0.0.1:8080/mcp
```

Bearer Token 接入：

```bash
claude mcp add --transport http database-mcp http://127.0.0.1:8080/mcp --header "Authorization: Bearer replace-with-your-token"
```

如果你希望把配置写到项目范围而不是本地默认范围，可以补上：

```bash
claude mcp add --scope project --transport http database-mcp http://127.0.0.1:8080/mcp
```

说明：

- `--transport http` 用于指定 HTTP MCP。
- `--header` 可以传 Bearer Token，也可以传其他网关 Header。
- 如果你的环境走 `trusted-header`，通常不建议直接在客户端手工写用户身份头，建议交给反向代理或认证网关注入。

### A.3 Cursor

根据 Cursor 官方文档，当前可以通过两种方式接入 MCP：

- 在 Cursor 设置页的 `Tools & Integrations` 中新增 MCP Server
- 或通过 `~/.cursor/mcp.json` 维护配置

一个常见的 HTTP MCP 配置示例如下：

```json
{
  "mcpServers": {
    "database-mcp": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

如果你的 Cursor 版本支持在 MCP 配置中填写请求头，可以进一步补充 Bearer Token，例如：

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

说明：

- Cursor 的 UI 与配置字段会随版本演进，建议优先以当前版本设置页中的 MCP 配置项为准。
- 如果本地直连不稳定，优先检查服务是否已启动、端口是否可达，以及 Bearer Token 是否正确。

### A.4 其他支持 MCP 的 Agent / IDE

对于其他支持 Streamable HTTP MCP 的客户端，通常只需要以下三项信息：

- MCP URL：`http://127.0.0.1:8080/mcp`
- 鉴权方式：无鉴权 / Bearer Token / 可信代理注入 Header
- 连接策略：建议由 Agent 在首次调用时传 `connection`，随后复用返回的 `connection_id`

推荐接入顺序：

1. 先用 `curl http://127.0.0.1:8080/health` 确认服务可用
2. 再在客户端里配置 MCP 地址
3. 若开启 Bearer Token，先验证 Token 再接入 Agent
4. 首次工具调用时先执行一个最简单的 `query`，例如 `SELECT 1 AS ok`

### A.5 接入后的推荐测试

无论接的是 Codex、Claude Code、Cursor 还是其他 Agent，建议都先做这三步：

1. 执行 `query`，SQL 使用 `SELECT 1 AS ok`
2. 检查返回里是否包含 `connection_id`
3. 再用该 `connection_id` 执行一次 `list_schemas` 或 `list_tables`

这样可以一次性验证：

- MCP 服务连通性
- 鉴权是否生效
- 数据库连接参数是否正确
- `connection_id` 复用是否正常
