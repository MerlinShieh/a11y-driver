import io
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

import ws  # noqa: E402


class WebSocketProtoTest(unittest.TestCase):
    def test_accept_key_rfc6455_vector(self):
        # RFC 6455 §1.3 的示例
        self.assertEqual(
            ws.accept_key("dGhlIHNhbXBsZSBub25jZQ=="),
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
        )

    def test_frame_roundtrip_small(self):
        frame = ws.encode_frame(b'{"a":1}', mask=True)
        opcode, payload = ws.read_frame(io.BytesIO(frame))
        self.assertEqual(opcode, ws.OP_TEXT)
        self.assertEqual(payload, b'{"a":1}')

    def test_frame_roundtrip_medium(self):
        data = b"x" * 500
        opcode, payload = ws.read_frame(io.BytesIO(ws.encode_frame(data, mask=True)))
        self.assertEqual(payload, data)

    def test_frame_roundtrip_large(self):
        data = b"y" * 70000
        opcode, payload = ws.read_frame(io.BytesIO(ws.encode_frame(data, mask=True)))
        self.assertEqual(len(payload), 70000)

    def test_unmasked_frame_for_server_side(self):
        frame = ws.encode_frame(b"hello", mask=False)
        opcode, payload = ws.read_frame(io.BytesIO(frame))
        self.assertEqual(payload, b"hello")

    def test_chinese_payload(self):
        text = '{"text":"网络和互联网"}'.encode("utf-8")
        opcode, payload = ws.read_frame(io.BytesIO(ws.encode_frame(text, mask=True)))
        self.assertEqual(payload.decode("utf-8"), '{"text":"网络和互联网"}')


if __name__ == "__main__":
    unittest.main()
