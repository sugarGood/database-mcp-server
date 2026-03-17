import argparse
import json
import os
import queue
import subprocess
import sys
import threading
import time
import urllib.request


def parse_args():
    parser = argparse.ArgumentParser(description="Probe the database MCP server over stdio or HTTP.")
    parser.add_argument("--jar", default=os.path.join("target", "database-mcp-server-1.0.0.jar"))
    parser.add_argument("--server-url", help="Use an existing HTTP MCP endpoint such as http://127.0.0.1:8080/mcp")
    parser.add_argument("--db-type", required=True, choices=["mysql", "postgresql", "oracle"])
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int)
    parser.add_argument("--database", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--schema")
    parser.add_argument("--sql", default="SELECT 1 AS ok")
    parser.add_argument("--list-tables", action="store_true")
    return parser.parse_args()


class McpProbe:
    def __init__(self, workdir, jar_path):
        self.workdir = workdir
        self.jar_path = jar_path
        self.process = None
        self.stdout_queue = queue.Queue()
        self.stderr_lines = []

    def start(self):
        self.process = subprocess.Popen(
            ["java", "-jar", self.jar_path],
            cwd=self.workdir,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def close(self):
        if not self.process:
            return
        try:
            if self.process.stdin:
                self.process.stdin.close()
        except Exception:
            pass
        time.sleep(0.2)
        if self.process.poll() is None:
            try:
                self.process.terminate()
                self.process.wait(timeout=2)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass

    def send(self, obj):
        if not self.process or not self.process.stdin:
            raise RuntimeError("MCP process is not running")
        self.process.stdin.write(json.dumps(obj, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

    def wait_for_id(self, request_id, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                line = self.stdout_queue.get(timeout=0.2)
            except queue.Empty:
                if self.process and self.process.poll() is not None:
                    break
                continue
            try:
                payload = json.loads(line)
            except Exception:
                continue
            if str(payload.get("id")) == str(request_id):
                return payload
        return None

    def _read_stdout(self):
        for line in iter(self.process.stdout.readline, ""):
            line = line.strip()
            if line:
                self.stdout_queue.put(line)

    def _read_stderr(self):
        for line in iter(self.process.stderr.readline, ""):
            line = line.strip()
            if line:
                self.stderr_lines.append(line)


def build_connection(args):
    connection = {
        "db_type": args.db_type,
        "host": args.host,
        "database": args.database,
        "username": args.username,
        "password": args.password,
    }
    if args.port is not None:
        connection["port"] = args.port
    if args.schema:
        connection["schema"] = args.schema
    return connection


def main():
    args = parse_args()
    workdir = os.path.abspath(os.path.dirname(os.path.dirname(__file__)))
    jar_path = os.path.abspath(os.path.join(workdir, args.jar))
    if not os.path.exists(jar_path):
        print(json.dumps({"error": f"Jar not found: {jar_path}"}, ensure_ascii=False, indent=2))
        return 1

    connection = build_connection(args)
    schema_name = args.schema or args.database
    stderr_lines = []

    if args.server_url:
        init_response = http_call(
            args.server_url,
            {
                "jsonrpc": "2.0",
                "id": "1",
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "local-probe", "version": "1.0.0"},
                },
            },
        )
        if not init_response:
            print(json.dumps({"error": "initialize timeout_or_empty_http_response"}, ensure_ascii=False, indent=2))
            return 2

        http_call(args.server_url, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

        query_response = http_call(
            args.server_url,
            {
                "jsonrpc": "2.0",
                "id": "2",
                "method": "tools/call",
                "params": {
                    "name": "query",
                    "arguments": {
                        "sql": args.sql,
                        "connection": connection,
                    },
                },
            },
        )

        connection_id = None
        if query_response:
            structured = (query_response.get("result") or {}).get("structuredContent") or {}
            connection_id = structured.get("connection_id")

        list_tables_response = None
        if args.list_tables and connection_id:
            list_tables_response = http_call(
                args.server_url,
                {
                    "jsonrpc": "2.0",
                    "id": "3",
                    "method": "tools/call",
                    "params": {
                        "name": "list_tables",
                        "arguments": {
                            "schema": schema_name,
                            "connection_id": connection_id,
                        },
                    },
                },
            )
    else:
        probe = McpProbe(workdir, jar_path)
        probe.start()

        try:
            probe.send({
                "jsonrpc": "2.0",
                "id": "1",
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "local-probe", "version": "1.0.0"},
                },
            })
            init_response = probe.wait_for_id("1")
            if not init_response:
                print(json.dumps({"error": "initialize timeout", "stderr": probe.stderr_lines}, ensure_ascii=False, indent=2))
                return 2

            probe.send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

            probe.send({
                "jsonrpc": "2.0",
                "id": "2",
                "method": "tools/call",
                "params": {
                    "name": "query",
                    "arguments": {
                        "sql": args.sql,
                        "connection": connection,
                    },
                },
            })
            query_response = probe.wait_for_id("2", timeout=20)

            connection_id = None
            if query_response:
                structured = (query_response.get("result") or {}).get("structuredContent") or {}
                connection_id = structured.get("connection_id")

            list_tables_response = None
            if args.list_tables and connection_id:
                probe.send({
                    "jsonrpc": "2.0",
                    "id": "3",
                    "method": "tools/call",
                    "params": {
                        "name": "list_tables",
                        "arguments": {
                            "schema": schema_name,
                            "connection_id": connection_id,
                        },
                    },
                })
                list_tables_response = probe.wait_for_id("3", timeout=20)

            stderr_lines = probe.stderr_lines
        finally:
            probe.close()

    summary = {
        "initialize_ok": bool(init_response),
        "query_ok": bool(query_response),
        "query_error": bool((query_response or {}).get("result", {}).get("isError")) if query_response else True,
        "connection_id": connection_id,
        "query_result": ((query_response or {}).get("result") or {}).get("structuredContent"),
        "list_tables_ok": bool(list_tables_response) if args.list_tables else None,
        "list_tables_error": bool((list_tables_response or {}).get("result", {}).get("isError")) if list_tables_response else None,
        "list_tables_result": ((list_tables_response or {}).get("result") or {}).get("structuredContent") if list_tables_response else None,
        "stderr_lines": stderr_lines,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def http_call(server_url, payload):
    request = urllib.request.Request(
        server_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        body = response.read().decode("utf-8")
        return json.loads(body) if body else None


if __name__ == "__main__":
    sys.exit(main())
