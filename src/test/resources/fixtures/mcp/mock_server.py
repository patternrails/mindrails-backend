#!/usr/bin/env python3
"""Dependency-free MCP fixture: stdio, Streamable HTTP and legacy SSE.

Only local mock operations are implemented. stdout is reserved for JSON-RPC in stdio mode.
"""
import argparse
import json
import os
import queue
import sys
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from socketserver import TCPServer
from urllib.parse import urlparse, parse_qs


def schema(properties, required=None):
    return {"type": "object", "properties": properties, "required": required or [], "additionalProperties": False}


TOOLS = [
    {"name": "echo", "description": "Return the text unchanged with the mock server label.",
     "inputSchema": schema({"text": {"type": "string"}}, ["text"])},
    {"name": "add", "description": "Add two numbers.",
     "inputSchema": schema({"a": {"type": "number"}, "b": {"type": "number"}}, ["a", "b"])},
    {"name": "fail", "description": "Return a deliberate MCP application error.",
     "inputSchema": schema({})},
    {"name": "slow", "description": "Wait for seconds; useful for timeout and cancellation testing.",
     "inputSchema": schema({"seconds": {"type": "number"}, "text": {"type": "string"}}, ["seconds"])},
    {"name": "structured", "description": "Return structured JSON content.",
     "inputSchema": schema({}), "outputSchema": schema({"label": {"type": "string"}, "ok": {"type": "boolean"}}, ["label", "ok"])},
    {"name": "binary", "description": "Return an image content block to verify omission of binary content.",
     "inputSchema": schema({})},
    {"name": "large", "description": "Return 50,000 characters to verify result limits.",
     "inputSchema": schema({})},
]


class Mock:
    def __init__(self, label, ignore_cancel=False, events=None):
        self.label, self.ignore_cancel, self.events = label, ignore_cancel, events
        self.pending, self.counts = {}, Counter()
        self.lock = threading.Lock()

    def event(self, message):
        with self.lock:
            self.counts[message["method"]] += 1
            if self.events:
                with open(self.events, "a", encoding="utf-8") as file:
                    file.write(json.dumps(message) + "\n")

    def dispatch(self, message, respond):
        method, request_id = message.get("method"), message.get("id")
        self.event({"method": method, "id": request_id})
        if method == "notifications/cancelled":
            target = message.get("params", {}).get("requestId")
            with self.lock:
                event = self.pending.get(target)
            if event and not self.ignore_cancel:
                event.set()
            return
        if request_id is None:
            return
        response = {"jsonrpc": "2.0", "id": request_id}
        if method == "initialize":
            response["result"] = {"protocolVersion": "2025-11-25", "capabilities": {"tools": {"listChanged": False}},
                                  "serverInfo": {"name": self.label, "version": "1.0.0"}}
        elif method == "ping":
            response["result"] = {}
        elif method == "tools/list":
            # Deliberately paginate so integrations cannot accidentally expose only the first page.
            cursor = message.get("params", {}).get("cursor")
            response["result"] = {"tools": TOOLS[3:] if cursor == "next" else TOOLS[:3]}
            if cursor != "next":
                response["result"]["nextCursor"] = "next"
        elif method == "tools/call":
            event = threading.Event()
            with self.lock:
                self.pending[request_id] = event
            def execute():
                try:
                    response["result"] = self.call(message.get("params", {}), event)
                    respond(response)
                except (ValueError, TypeError, KeyError):
                    response["error"] = {"code": -32602, "message": "Invalid mock tool arguments"}
                    respond(response)
                finally:
                    with self.lock:
                        self.pending.pop(request_id, None)
            threading.Thread(target=execute, daemon=True).start()
            return
        else:
            response["error"] = {"code": -32601, "message": "Method not found"}
        respond(response)

    def call(self, params, cancelled):
        name, arguments = params.get("name"), params.get("arguments", {})
        text, error = "", False
        if name == "echo":
            if not isinstance(arguments.get("text"), str):
                raise ValueError()
            text = f"{self.label}: {arguments['text']}"
        elif name == "add":
            a, b = arguments["a"], arguments["b"]
            if isinstance(a, bool) or isinstance(b, bool) or not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
                raise ValueError()
            text = str(a + b)
        elif name == "fail":
            text, error = "Deliberate mock failure", True
        elif name == "slow":
            seconds = float(arguments["seconds"])
            if seconds < 0 or seconds > 300:
                raise ValueError()
            if cancelled.wait(seconds):
                text, error = "Mock request cancelled", True
            else:
                text = f"{self.label}: {arguments.get('text', 'slow completed')}"
        elif name == "structured":
            return {"content": [], "structuredContent": {"label": self.label, "ok": True}, "isError": False}
        elif name == "binary":
            return {"content": [{"type": "image", "mimeType": "image/png", "data": "bW9jaw=="}], "isError": False}
        elif name == "large":
            text = "x" * 50000
        else:
            text, error = "Unknown mock tool", True
        return {"content": [{"type": "text", "text": text}], "isError": error}


def stdio(mock):
    lock = threading.Lock()
    def respond(message):
        with lock:
            print(json.dumps(message, ensure_ascii=False), flush=True)
    for line in sys.stdin:
        try:
            mock.dispatch(json.loads(line), respond)
        except (ValueError, TypeError):
            respond({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})


def http(mock, host, port, token, call_status=200, sse_responses=False):
    sessions, session_lock = {}, threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *_):
            pass
        def authorized(self):
            return not token or self.headers.get("Authorization") == f"Bearer {token}"
        def reply(self, code, value=None):
            body = b"" if value is None else json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def do_GET(self):
            path = urlparse(self.path).path
            if not self.authorized():
                self.reply(401, {"error": "Unauthorized"}); return
            if path == "/health":
                self.reply(200, {"ok": True}); return
            if path == "/stats":
                with mock.lock:
                    stats = dict(mock.counts)
                self.reply(200, stats); return
            if path != "/sse":
                self.reply(405); return
            session_id = str(time.monotonic_ns())
            messages = queue.Queue()
            with session_lock:
                sessions[session_id] = messages
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(f"event: endpoint\ndata: /messages?session={session_id}\n\n".encode()); self.wfile.flush()
            try:
                while True:
                    try:
                        item = messages.get(timeout=1)
                        self.wfile.write(("event: message\ndata: " + json.dumps(item) + "\n\n").encode())
                    except queue.Empty:
                        self.wfile.write(b": heartbeat\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with session_lock:
                    sessions.pop(session_id, None)
        def do_POST(self):
            if not self.authorized():
                self.reply(401); return
            target = urlparse(self.path)
            if target.path not in ("/mcp", "/messages"):
                self.reply(404); return
            try:
                size = int(self.headers.get("Content-Length", 0))
                if size > 1048576:
                    self.reply(413); return
                message = json.loads(self.rfile.read(size))
            except (ValueError, TypeError):
                self.reply(400); return
            if target.path == "/messages":
                session_id = parse_qs(target.query).get("session", [""])[0]
                with session_lock:
                    messages = sessions.get(session_id)
                if messages is None:
                    self.reply(404); return
                mock.dispatch(message, messages.put); self.reply(202); return
            if message.get("method") == "tools/call" and call_status != 200:
                mock.event({"method": "tools/call", "id": message.get("id")})
                self.reply(call_status, {"error": "Deliberate HTTP mock failure"}); return
            if message.get("id") is None:
                mock.dispatch(message, lambda _: None); self.reply(202); return
            replies = queue.Queue()
            mock.dispatch(message, replies.put)
            try:
                result = replies.get(timeout=310)
                if sse_responses:
                    body = ("event: message\ndata: " + json.dumps(result) + "\n\n").encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.reply(200, result)
            except (queue.Empty, BrokenPipeError, ConnectionResetError):
                self.close_connection = True
        def do_DELETE(self):
            self.reply(204)
    class LocalServer(ThreadingHTTPServer):
        def server_bind(self):
            # HTTPServer normally performs reverse DNS here, which can stall offline verification.
            TCPServer.server_bind(self)
            self.server_name, self.server_port = self.server_address
    server = LocalServer((host, port), Handler)
    print(f"Mock MCP {mock.label} listening at http://{host}:{server.server_port}/mcp (SSE: /sse)", file=sys.stderr, flush=True)
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", choices=["stdio", "http"], default="stdio")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--label", default="mindrails-mock")
    parser.add_argument("--token-env", help="Read an optional HTTP bearer token from this environment variable")
    parser.add_argument("--ignore-cancel", action="store_true")
    parser.add_argument("--events", help="Append request method/id events to this file (never arguments or headers)")
    parser.add_argument("--call-status", type=int, choices=[200, 404, 503], default=200)
    parser.add_argument("--http-sse-responses", action="store_true")
    args = parser.parse_args()
    mock = Mock(args.label, args.ignore_cancel, args.events)
    if args.transport == "stdio":
        stdio(mock)
    else:
        http(mock, args.host, args.port, os.environ.get(args.token_env) if args.token_env else None, args.call_status, args.http_sse_responses)


if __name__ == "__main__":
    main()
