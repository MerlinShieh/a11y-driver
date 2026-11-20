"""手机端执行引擎的 Python 客户端（纯标准库，无第三方依赖）。"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request


class EngineError(RuntimeError):
    pass


class EngineClient:
    def __init__(self, host: str, port: int = 7912, token: str = "", timeout: float = 15.0):
        self.base = f"http://{host}:{port}"
        self.token = token
        self.timeout = timeout

    # ---------- 底层 ----------

    def _request(self, path: str, method: str = "GET", payload: dict | None = None,
                 timeout: float | None = None) -> bytes:
        data = None
        headers = {"X-Auth-Token": self.token}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            raise EngineError(f"HTTP {e.code}: {body}") from e
        except urllib.error.URLError as e:
            raise EngineError(f"连接失败 {self.base}: {e.reason}") from e

    def _json(self, path: str, method: str = "GET", payload: dict | None = None,
              timeout: float | None = None) -> dict:
        return json.loads(self._request(path, method, payload, timeout).decode("utf-8"))

    # ---------- 查询 ----------

    def ping(self) -> dict:
        return self._json("/v1/ping")

    def capabilities(self) -> dict:
        return self._json("/v1/capabilities")

    def wait_ready(self, timeout: float = 10.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                info = self.ping()
                if info.get("accessibility"):
                    return
            except EngineError:
                pass
            time.sleep(0.3)
        raise EngineError("引擎未就绪（检查无障碍服务是否开启）")

    def hierarchy(self) -> dict:
        return self._json("/v1/hierarchy")

    def find(self, selector: dict, timeout: float = 0) -> dict | None:
        r = self._json("/v1/find", "POST", {"selector": selector, "timeoutMs": int(timeout * 1000)})
        return r.get("node") if r.get("ok") else None

    def find_all(self, selector: dict, timeout: float = 0, limit: int = 200) -> list[dict]:
        r = self._json("/v1/findall", "POST", {
            "selector": selector, "timeoutMs": int(timeout * 1000), "limit": limit})
        return r.get("nodes", []) if r.get("ok") else []

    def count(self, selector: dict) -> int:
        return len(self.find_all(selector, 0))

    def exists(self, selector: dict) -> bool:
        return self.find(selector, 0) is not None

    def current_app(self) -> str:
        return self._json("/v1/app/current").get("package", "")

    # ---------- 等待 ----------

    def wait_for(self, selector: dict, timeout: float = 10.0) -> dict:
        r = self._json("/v1/wait", "POST",
                       {"selector": selector, "state": "appear", "timeoutMs": int(timeout * 1000)})
        if not r.get("ok"):
            raise EngineError(f"等待出现超时: {selector}")
        return self.find(selector, 0) or {}

    def wait_gone(self, selector: dict, timeout: float = 10.0) -> bool:
        r = self._json("/v1/wait", "POST",
                       {"selector": selector, "state": "gone", "timeoutMs": int(timeout * 1000)})
        return bool(r.get("ok"))

    def wait_stable(self, timeout: float = 5.0, quiet: float = 0.3) -> bool:
        r = self._json("/v1/waitstable", "POST",
                       {"timeoutMs": int(timeout * 1000), "quietMs": int(quiet * 1000)})
        return bool(r.get("ok"))

    # ---------- 动作 ----------

    def click(self, selector: dict | None = None, x: int | None = None, y: int | None = None,
              timeout: float = 3.0) -> bool:
        payload: dict = {"timeoutMs": int(timeout * 1000)}
        if selector:
            payload["selector"] = selector
        if x is not None and y is not None:
            payload["x"], payload["y"] = x, y
        return bool(self._json("/v1/click", "POST", payload).get("ok"))

    def long_click(self, selector: dict, timeout: float = 3.0) -> bool:
        return bool(self._json("/v1/longclick", "POST",
                               {"selector": selector, "timeoutMs": int(timeout * 1000)}).get("ok"))

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> bool:
        return bool(self._json("/v1/swipe", "POST",
                               {"x1": x1, "y1": y1, "x2": x2, "y2": y2,
                                "durationMs": duration_ms}).get("ok"))

    def scroll_into_view(self, selector: dict, down: bool = True,
                         max_swipes: int = 8, timeout: float = 2.0) -> dict | None:
        r = self._json("/v1/scroll", "POST", {
            "selector": selector, "down": down, "maxSwipes": max_swipes,
            "timeoutMs": int(timeout * 1000)}, timeout=40)
        return r.get("node") if r.get("ok") else None

    def input(self, selector: dict, text: str, timeout: float = 3.0) -> bool:
        return bool(self._json("/v1/input", "POST",
                               {"selector": selector, "text": text,
                                "timeoutMs": int(timeout * 1000)}).get("ok"))

    def global_action(self, action: str) -> bool:
        return bool(self._json("/v1/global", "POST", {"action": action}).get("ok"))

    def app_start(self, package: str) -> bool:
        return bool(self._json("/v1/app/start", "POST", {"package": package}).get("ok"))

    def screenshot(self, path: str) -> str:
        data = self._request("/v1/screenshot")
        with open(path, "wb") as f:
            f.write(data)
        return path

    # ---------- shell（设备侧，需 Shizuku/root） ----------

    def shell(self, cmd: str, timeout: float = 30.0) -> dict:
        return self._json("/v1/shell", "POST", {"cmd": cmd, "timeoutMs": int(timeout * 1000)})


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 4:
        print("用法: python engine_client.py <host> <port> <token>")
        raise SystemExit(1)
    c = EngineClient(sys.argv[1], int(sys.argv[2]), sys.argv[3])
    print(json.dumps(c.ping(), ensure_ascii=False, indent=2))
