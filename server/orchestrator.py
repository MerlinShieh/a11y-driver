#!/usr/bin/env python3
"""PC 侧编排器：给脚本一个统一入口，背后分流到两条通道。

    /v1/ui/*   ──HTTP──▶ 手机引擎（无障碍，经 hub 反连转发）
    /v1/shell  ──ADB───▶ 设备 shell（uid=2000，能做 pm/dumpsys/logcat）
    /v1/logcat ──ADB───▶ 设备日志

用法：
    python server/orchestrator.py --token <令牌> --serial emulator-5554

    --ui-port 7912       手机引擎（或 hub）所在端口
    --port 7920          本编排器监听端口
    --serial             指定设备；不填则自动取第一台
    --no-adb             只转发 UI，禁用 shell（模拟无 ADB 环境）
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from logging_setup import setup, add_level_arg

log = setup("mae.orchestrator")

# 不写死本机路径：优先环境变量，其次 PATH
ADB = os.environ.get("ADB") or shutil.which("adb") or "adb"

UI_ROUTES = {
    "/v1/ping", "/v1/capabilities", "/v1/hierarchy", "/v1/find", "/v1/findall",
    "/v1/wait", "/v1/waitstable", "/v1/scroll", "/v1/click", "/v1/longclick",
    "/v1/swipe", "/v1/input", "/v1/global", "/v1/app/current", "/v1/app/start",
    "/v1/shell", "/v1/cancel", "/v1/ops", "/v1/screenshot",
}


class Config:
    token = ""
    serial = ""
    ui_base = "http://127.0.0.1:7912"
    adb_enabled = True


def adb(*args: str, timeout: float = 30.0) -> tuple[int, str, str]:
    """执行 adb 命令，返回 (exitCode, stdout, stderr)。"""
    cmd = [ADB]
    if Config.serial:
        cmd += ["-s", Config.serial]
    cmd += list(args)
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return -1, "", f"adb 超时（{timeout}s）"
    except FileNotFoundError:
        return -1, "", f"找不到 adb: {ADB}"


def detect_device() -> str:
    code, out, _ = adb("devices")
    if code != 0:
        return ""
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return ""


def ui_request(path: str, method: str, body: bytes | None) -> tuple[int, bytes, str]:
    """把请求转发给手机引擎，返回 (status, body, contentType)。"""
    req = urllib.request.Request(
        Config.ui_base + path,
        data=body,
        headers={"X-Auth-Token": Config.token, "Content-Type": "application/json"},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read(), r.headers.get("Content-Type", "application/json")
    except urllib.error.HTTPError as e:
        return e.code, e.read(), "application/json"
    except urllib.error.URLError as e:
        return 503, json.dumps(
            {"ok": False, "error": f"UI_CHANNEL_UNAVAILABLE: {e.reason}"}
        ).encode(), "application/json"


def capabilities() -> dict:
    shell_kind = "none"
    if Config.adb_enabled:
        code, out, _ = adb("exec-out", "id")
        if code == 0:
            if "uid=2000" in out:
                shell_kind = "adb-shell"      # 正常 shell 身份
            elif "uid=0" in out:
                shell_kind = "adb-root"       # adbd 以 root 运行（adb root）
            else:
                shell_kind = "adb-other"

    ui_ok = False
    sem = "unknown"
    try:
        st, body, _ = ui_request("/v1/ping", "GET", None)
        if st == 200:
            info = json.loads(body)
            ui_ok = bool(info.get("accessibility"))
            sem = "ready" if ui_ok else "accessibility_not_ready"
    except Exception:
        sem = "unreachable"

    return {
        "ok": True,
        "channels": {
            "ui": {"available": ui_ok, "state": sem, "transport": "reverse-hub"},
            "shell": {"available": shell_kind != "none", "kind": shell_kind, "via": "adb"},
            "logcat": {"available": Config.adb_enabled},
        },
        "device": {
            "serial": Config.serial,
            "model": adb("shell", "getprop", "ro.product.model")[1].strip() if Config.adb_enabled else "",
            "sdk": adb("shell", "getprop", "ro.build.version.sdk")[1].strip() if Config.adb_enabled else "",
        },
    }


def make_handler():
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def _send(self, code: int, body: bytes, ctype: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _json(self, code: int, obj: dict) -> None:
            self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                       "application/json; charset=utf-8")

        def _body(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            if not raw.strip():
                return {}
            try:
                return json.loads(raw.decode("utf-8"))
            except json.JSONDecodeError:
                return {}

        def _handle(self, method: str) -> None:
            if self.headers.get("X-Auth-Token") != Config.token:
                self._json(401, {"ok": False, "error": "UNAUTHORIZED"})
                return

            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path
            query = urllib.parse.parse_qs(parsed.query)

            if path == "/v1/capabilities":
                self._json(200, capabilities())
                return

            if path == "/v1/shell":
                self._shell(method)
                return

            if path == "/v1/logcat":
                self._logcat(query)
                return

            if path in UI_ROUTES:
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)) if method == "POST" else None
                code, data, ctype = ui_request(path, method, body)
                self._send(code, data, ctype)
                return

            self._json(404, {"ok": False, "error": "NO_ROUTE", "uri": path})

        def _shell(self, method: str) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""

            # 没有 ADB 时，退回到设备侧通道（Shizuku / root）
            if not Config.adb_enabled:
                code, data, ctype = ui_request("/v1/shell", "POST", raw)
                self._send(code, data, ctype)
                return

            try:
                payload = json.loads(raw.decode("utf-8")) if raw.strip() else {}
            except json.JSONDecodeError:
                payload = {}

            cmd = (payload.get("cmd") or "").strip()
            if not cmd:
                self._json(400, {"ok": False, "error": "EMPTY_CMD"})
                return
            timeout = float(payload.get("timeoutMs", 30000)) / 1000.0
            started = time.time()
            code, out, err = adb("shell", cmd, timeout=timeout)
            self._json(200, {
                "ok": code == 0,
                "exitCode": code,
                "stdout": out,
                "stderr": err,
                "elapsedMs": int((time.time() - started) * 1000),
                "cmd": cmd,
                "kind": "adb",
            })

        def _logcat(self, query: dict) -> None:
            if not Config.adb_enabled:
                self._json(503, {"ok": False, "error": "LOGCAT_UNAVAILABLE"})
                return
            lines = int((query.get("lines") or ["200"])[0])
            filt = (query.get("filter") or [""])[0]
            args = ["logcat", "-d", "-t", str(lines)]
            if filt:
                args += ["-e", filt]
            code, out, err = adb(*args)
            self._json(200, {
                "ok": code == 0,
                "lines": out.splitlines(),
                "stderr": err,
            })

        def do_GET(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

    return Handler


def main() -> None:
    ap = argparse.ArgumentParser(description="A11y Driver PC 侧编排器")
    ap.add_argument("--token", required=True)
    ap.add_argument("--port", type=int, default=7920)
    ap.add_argument("--ui-port", type=int, default=7912)
    ap.add_argument("--serial", default="")
    ap.add_argument("--no-adb", action="store_true")
    add_level_arg(ap)
    args = ap.parse_args()
    setup("mae.orchestrator", args.log_level)

    Config.token = args.token
    Config.ui_base = f"http://127.0.0.1:{args.ui_port}"
    Config.adb_enabled = not args.no_adb
    Config.serial = args.serial or (detect_device() if Config.adb_enabled else "")

    log.info(f"[*] UI 通道      → {Config.ui_base}")
    if Config.adb_enabled:
        log.info(f"[*] Shell 通道   → adb -s {Config.serial or '(第一台)'}")
    else:
        log.info("[*] Shell 通道   → 已禁用（--no-adb）")
    log.info(f"[*] 本服务监听   → http://127.0.0.1:{args.port}")
    log.info(f"[*] 令牌         → {args.token}")

    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler())
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        httpd.shutdown()


if __name__ == "__main__":
    main()
