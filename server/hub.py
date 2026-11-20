#!/usr/bin/env python3
"""反连 hub：手机主动连过来，PC 侧脚本仍然用 http://127.0.0.1:7912 访问。

用法：
    python server/hub.py --token <手机界面上的令牌>

    # 可选参数
    --http-port 7912      PC 本地 HTTP 端口（脚本连这个）
    --device-port 7913    等手机连进来的 TCP 端口
    --bind 0.0.0.0        device 监听地址（真机同局域网时用 0.0.0.0）

手机端：设置里选「反连模式」，地址填 PC 的 IP:7913
       （Android 模拟器里 PC 的地址固定是 10.0.2.2）

只依赖标准库，不需要 pip install。
"""

from __future__ import annotations

import argparse
import base64
import json
import socket
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from logging_setup import setup, add_level_arg

log = setup("mae.hub")

ROUTES = {
    "/v1/ping": "ping",
    "/v1/capabilities": "capabilities",
    "/v1/hierarchy": "hierarchy",
    "/v1/find": "find",
    "/v1/findall": "findall",
    "/v1/wait": "wait",
    "/v1/waitstable": "waitstable",
    "/v1/scroll": "scroll",
    "/v1/click": "click",
    "/v1/longclick": "longclick",
    "/v1/swipe": "swipe",
    "/v1/input": "input",
    "/v1/global": "global",
    "/v1/app/current": "app.current",
    "/v1/app/start": "app.start",
    "/v1/shell": "shell",
    "/v1/screenshot": "screenshot",
}


class Link:
    """一条到手机的 JSON-lines 连接。"""

    def __init__(self, sock: socket.socket, addr):
        self.sock = sock
        self.addr = addr
        self.buf = b""
        self.lock = threading.Lock()
        self.seq = 0
        self.device = {}
        self.closed = False

    def _readline(self, timeout: float) -> str:
        deadline = time.time() + timeout
        while b"\n" not in self.buf:
            remain = deadline - time.time()
            if remain <= 0:
                raise TimeoutError("等待手机响应超时")
            self.sock.settimeout(remain)
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("手机断开了连接")
            self.buf += chunk
        line, _, self.buf = self.buf.partition(b"\n")
        return line.decode("utf-8", "replace")

    def send(self, obj: dict) -> None:
        self.sock.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    def call(self, cmd: str, payload: dict | None = None, timeout: float = 25.0) -> dict:
        with self.lock:
            if self.closed:
                raise ConnectionError("设备未连接")
            self.seq += 1
            rid = str(self.seq)
            try:
                self.send({"id": rid, "cmd": cmd, "payload": payload or {}})
                while True:
                    line = self._readline(timeout)
                    if not line.strip():
                        continue
                    obj = json.loads(line)
                    if obj.get("id") == rid:
                        return obj
                    # 其它消息（hello/heartbeat）忽略
            except Exception:
                self.closed = True
                try:
                    self.sock.close()
                except OSError:
                    pass
                raise

    def close(self) -> None:
        self.closed = True
        try:
            self.sock.close()
        except OSError:
            pass


_current: Link | None = None
_current_lock = threading.Lock()


def set_current(link: Link | None) -> None:
    global _current
    with _current_lock:
        _current = link


def get_current() -> Link | None:
    with _current_lock:
        return _current


def device_thread(conn: socket.socket, addr, token: str) -> None:
    global _current
    link = Link(conn, addr)
    try:
        hello_raw = link._readline(10.0)
        hello = json.loads(hello_raw)
        if hello.get("token") != token:
            log.info(f"[!] {addr} 令牌不匹配，已拒绝")
            link.send({"ok": False, "error": "UNAUTHORIZED"})
            link.close()
            return
        link.device = hello.get("device", {})
        model = link.device.get("model", "unknown")
        log.info(f"[+] 设备已连接：{model} ({addr[0]}:{addr[1]})")
        set_current(link)
        # 阻塞等待连接结束（call() 会消费数据，这里只等 EOF 不可行，
        # 因此用 readline 的空闲超时循环探测存活）
        while not link.closed:
            time.sleep(1.0)
    except Exception as e:
        log.info(f"[!] 设备连接异常：{e}")
    finally:
        link.close()
        if get_current() is link:
            set_current(None)
        log.info(f"[-] 设备已断开：{addr[0]}:{addr[1]}")


def start_device_server(bind: str, port: int, token: str) -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((bind, port))
    srv.listen(8)
    log.info(f"[*] 等待手机反连：{bind}:{port}")
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=device_thread, args=(conn, addr, token), daemon=True).start()


def make_handler(token: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # 静默访问日志
            pass

        def _reply(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _json(self, code: int, obj: dict) -> None:
            self._reply(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                        "application/json; charset=utf-8")

        def _handle(self, method: str) -> None:
            if self.headers.get("X-Auth-Token") != token:
                self._json(401, {"ok": False, "error": "UNAUTHORIZED"})
                return

            path = urllib.parse.urlparse(self.path).path
            cmd = ROUTES.get(path)
            if cmd is None:
                self._json(404, {"ok": False, "error": "NO_ROUTE", "uri": path})
                return

            payload = {}
            if method == "POST":
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                if raw.strip():
                    try:
                        payload = json.loads(raw.decode("utf-8"))
                    except json.JSONDecodeError:
                        self._json(400, {"ok": False, "error": "BAD_JSON"})
                        return

            link = get_current()
            if link is None:
                self._json(503, {"ok": False, "error": "DEVICE_OFFLINE"})
                return

            try:
                result = link.call(cmd, payload)
            except Exception as e:
                self._json(503, {"ok": False, "error": f"DEVICE_ERROR: {e}"})
                return

            if cmd == "screenshot":
                if not result.get("ok") or not result.get("base64"):
                    self._json(500, result)
                    return
                self._reply(200, base64.b64decode(result["base64"]), "image/png")
                return

            self._json(200, result)

        def do_GET(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

        def do_DELETE(self):
            self._json(405, {"ok": False, "error": "METHOD_NOT_ALLOWED"})

    return Handler


def main() -> None:
    ap = argparse.ArgumentParser(description="A11y Driver 反连 hub")
    ap.add_argument("--token", required=True, help="手机界面上显示的令牌")
    ap.add_argument("--http-port", type=int, default=7912, help="PC 本地 HTTP 端口")
    ap.add_argument("--device-port", type=int, default=7913, help="等手机连进来的端口")
    ap.add_argument("--bind", default="0.0.0.0", help="device 监听地址")
    add_level_arg(ap)
    args = ap.parse_args()
    setup("mae.hub", args.log_level)

    threading.Thread(
        target=start_device_server,
        args=(args.bind, args.device_port, args.token),
        daemon=True,
    ).start()

    httpd = ThreadingHTTPServer(("127.0.0.1", args.http_port), make_handler(args.token))
    log.info(f"[*] HTTP 接口已就绪：http://127.0.0.1:{args.http_port}")
    log.info(f"[*] 令牌：{args.token}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log.info("\n[*] 退出")
        httpd.shutdown()


if __name__ == "__main__":
    main()
