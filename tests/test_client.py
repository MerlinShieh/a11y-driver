import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "client"))

from engine_client import EngineClient  # noqa: E402


class FakeClient(EngineClient):
    """不发真实请求，只记录调用。"""

    def __init__(self):
        super().__init__("127.0.0.1", 7912, "token")
        self.calls = []
        self.reply = {"ok": True}

    def _json(self, path, method="GET", payload=None, timeout=None):
        self.calls.append((path, method, payload))
        return self.reply


class EngineClientTest(unittest.TestCase):
    def test_click_with_selector_sends_timeout(self):
        c = FakeClient()
        self.assertTrue(c.click({"text": "确定"}, timeout=2.5))
        path, method, payload = c.calls[-1]
        self.assertEqual(path, "/v1/click")
        self.assertEqual(method, "POST")
        self.assertEqual(payload["selector"], {"text": "确定"})
        self.assertEqual(payload["timeoutMs"], 2500)

    def test_click_with_coordinates(self):
        c = FakeClient()
        c.click(x=100, y=200)
        payload = c.calls[-1][2]
        self.assertEqual((payload["x"], payload["y"]), (100, 200))
        self.assertNotIn("selector", payload)

    def test_swipe_payload(self):
        c = FakeClient()
        c.swipe(1, 2, 3, 4, 500)
        payload = c.calls[-1][2]
        self.assertEqual(payload, {"x1": 1, "y1": 2, "x2": 3, "y2": 4, "durationMs": 500})

    def test_find_all_returns_empty_when_failed(self):
        c = FakeClient()
        c.reply = {"ok": False, "error": "NOT_FOUND"}
        self.assertEqual(c.find_all({"text": "x"}), [])

    def test_wait_gone_uses_state(self):
        c = FakeClient()
        c.wait_gone({"text": "弹窗"}, timeout=1)
        path, _, payload = c.calls[-1]
        self.assertEqual(path, "/v1/wait")
        self.assertEqual(payload["state"], "gone")
        self.assertEqual(payload["timeoutMs"], 1000)

    def test_base_url_built_from_host_port(self):
        c = EngineClient("127.0.0.1", 7920, "t")
        self.assertEqual(c.base, "http://127.0.0.1:7920")

    # ---------- REQ-011：重试与取消 ----------

    def test_click_retries_payload(self):
        c = FakeClient()
        c.click({"text": "确定"}, retries=2, retry_interval=0.5, op_id="op-1")
        payload = c.calls[-1][2]
        self.assertEqual(payload["retries"], 2)
        self.assertEqual(payload["retryIntervalMs"], 500)
        self.assertEqual(payload["opId"], "op-1")

    def test_click_without_retries_keeps_payload_minimal(self):
        c = FakeClient()
        c.click({"text": "确定"})
        payload = c.calls[-1][2]
        self.assertNotIn("retries", payload)
        self.assertNotIn("opId", payload)

    def test_cancel_single(self):
        c = FakeClient()
        c.reply = {"ok": True, "cancelled": 1}
        self.assertEqual(c.cancel("op-9"), 1)
        path, _, payload = c.calls[-1]
        self.assertEqual(path, "/v1/cancel")
        self.assertEqual(payload["opId"], "op-9")
        self.assertNotIn("all", payload)

    def test_cancel_all(self):
        c = FakeClient()
        c.reply = {"ok": True, "cancelled": 3}
        self.assertEqual(c.cancel(all_ops=True), 3)
        self.assertTrue(c.calls[-1][2]["all"])

    def test_click_detail_returns_error_code(self):
        c = FakeClient()
        c.reply = {"ok": False, "error": "NOT_FOUND", "attempts": 3, "elapsedMs": 900}
        r = c.click_detail({"text": "无"}, retries=2)
        self.assertEqual(r["error"], "NOT_FOUND")
        self.assertEqual(r["attempts"], 3)


if __name__ == "__main__":
    unittest.main()
