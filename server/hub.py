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

并发模型：一条连接上允许多个在途请求，响应按 id 分发。
否则一条长任务（wait/scroll）会把连接占满，`/v1/cancel` 无法送达。

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
    "/v1/cancel": "cancel",
    "/v1/ops": "ops",
    "/v1/screenshot": "screenshot",
}


class Link:
    """一条到手机的 JSON-lines 连接，支持并发在途请求。"""

    def __init__(self, sock: socket.socket, addr):
        self.sock = sock
        self.addr = addr
        self.buf = b""
        self.send_lock = threading.Lock()
        self.pending: dict[str, dict] = {}
        self.pending_lock = threading.Lock()
        self.seq = 0
        self.seq_lock = threading.Lock()
        self.device = {}
        self.closed = False
        self.reader: threading.Thread | None = None

    # ---------- 收 ----------

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

    def start_reader(self) -> None:
        self.reader = threading.Thread(target=self._read_loop, name="hub-reader", daemon=True)
        self.reader.start()

    def _read_loop(self) -> None:
        try:
            while not self.closed:
                line = self._readline(600.0)
                if not line.strip():
                    continue
                obj = json.loads(line)
                rid = obj.get("id")
                with self.pending_lock:
                    slot = self.pending.pop(rid, None)
                if slot is not None:
                    slot["result"] = obj
                    slot["event"].set()
        except Exception as e:
            if not self.closed:
                log.warning("读线程退出：%s", e)
        finally:
            self._fail_pending("连接已断开")
            self.closed = True

    def _fail_pending(self, reason: str) -> None:
        with self.pending_lock:
            slots = list(self.pending.values())
            self.pending.clear()
        for slot in slots:
            slot["error"] = reason
            slot["event"].set()

    # ---------- 发 ----------

    def send(self, obj: dict) -> None:
        with self.send_lock:
            self.sock.sendall((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))

    def call(self, cmd: str, payload: dict | None = None, timeout: float = 25.0) -> dict:
        if self.closed:
            raise ConnectionError("设备未连接")

        with self.seq_lock:
            self.seq += 1
            rid = str(self.seq)

        slot = {"event": threading.Event(), "result": None, "error": None}
        with self.pending_lock:
            self.pending[rid] = slot

        try:
            self.send({"id": rid, "cmd": cmd, "payload": payload or {}})
        except Exception:
            with self.pending_lock:
                self.pending.pop(rid, None)
            raise

        if not slot["event"].wait(timeout):
            with self.pending_lock:
                self.pending.pop(rid, None)
            raise TimeoutError(f"等待手机响应超时（{timeout}s）")

        if slot.get("error"):
            raise ConnectionError(slot["error"])
        return slot["result"]

    def close(self) -> None:
        self.closed = True
        self._fail_pending("连接已关闭")
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
    link = Link(conn, addr)
    try:
        hello = json.loads(link._readline(10.0))
        if hello.get("token") != token:
            log.warning("%s 令牌不匹配，已拒绝", addr)
            link.send({"ok": False, "error": "UNAUTHORIZED"})
            link.close()
            return

        link.device = hello.get("device", {})
        model = link.device.get("model", "unknown")
        log.info("设备已连接：%s (%s:%s)", model, addr[0], addr[1])

        set_current(link)
        link.start_reader()
        link.reader.join()
    except Exception as e:
        log.warning("设备连接异常：%s", e)
    finally:
        link.close()
        if get_current() is link:
            set_current(None)
        log.info("设备已断开：%s:%s", addr[0], addr[1])


def start_device_server(bind: str, port: int, token: str) -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((bind, port))
    srv.listen(8)
    log.info("等待手机反连：%s:%s", bind, port)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=device_thread, args=(conn, addr, token), daemon=True).start()


def make_handler(token: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # 访问日志交给 logging
            log.debug("HTTP %s", fmt % args)

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
                # 长任务给更宽的等待窗口
                timeout = 60.0 if cmd in ("wait", "waitstable", "scroll", "shell") else 25.0
                result = link.call(cmd, payload, timeout=timeout)
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
    log.info("HTTP 接口已就绪：http://127.0.0.1:%s", args.http_port)
    log.info("令牌：%s", args.token[:4] + "****" if args.token else "(空)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log.info("退出")
        httpd.shutdown()


if __name__ == "__main__":
    main()
