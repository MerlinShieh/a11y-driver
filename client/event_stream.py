"""WebSocket 事件流客户端（标准库实现，零依赖）。

    with EventStream("127.0.0.1", 7912, token) as s:
        for item in s:
            print(item["event"]["type"])

只支持本项目用到的子集：文本帧、服务端 ping、客户端 pong。
"""

from __future__ import annotations

import base64
import json
import os
import socket
import struct


class EventStream:
    def __init__(self, host: str = "127.0.0.1", port: int = 7912,
                 token: str = "", path: str = "/v1/events", timeout: float = 30.0):
        self.host = host
        self.port = port
        self.token = token
        self.path = path
        self.timeout = timeout
        self.sock: socket.socket | None = None
        self._buf = b""

    # ---------- 连接 ----------

    def connect(self) -> "EventStream":
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        req = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {self.host}:{self.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"X-Auth-Token: {self.token}\r\n"
            "\r\n"
        ).encode("ascii")
        self.sock.sendall(req)

        header = b""
        while b"\r\n\r\n" not in header:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("握手失败：连接被关闭")
            header += chunk
        head, _, rest = header.partition(b"\r\n\r\n")
        self._buf = rest

        status = head.split(b"\r\n", 1)[0].decode("latin-1")
        if "101" not in status:
            raise ConnectionError(f"握手失败：{status}")
        return self

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            finally:
                self.sock = None

    # ---------- 收 ----------

    def recv(self) -> dict | None:
        """收一条事件；返回解析后的 dict，连接关闭返回 None"""
        if not self.sock:
            return None

        while True:
            frame = self._read_frame()
            if frame is None:
                return None
            opcode, payload = frame

            if opcode == 0x9:            # 服务端 ping → 回 pong
                self._send_frame(payload, opcode=0xA)
                continue
            if opcode == 0x8:            # close
                return None
            if opcode in (0x1, 0x2):     # text / binary
                if not payload:
                    continue
                return json.loads(payload.decode("utf-8"))

    def __iter__(self):
        while True:
            item = self.recv()
            if item is None:
                return
            yield item

    def __enter__(self) -> "EventStream":
        return self.connect()

    def __exit__(self, *exc) -> None:
        self.close()

    # ---------- 帧处理 ----------

    def _read_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(max(4096, n - len(self._buf)))
            if not chunk:
                raise ConnectionError("连接已断开")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _read_frame(self):
        try:
            head = self._read_exact(2)
        except ConnectionError:
            return None

        opcode = head[0] & 0x0F
        masked = bool(head[1] & 0x80)
        length = head[1] & 0x7F

        if length == 126:
            length = struct.unpack(">H", self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read_exact(8))[0]

        key = self._read_exact(4) if masked else b""
        payload = self._read_exact(length)
        if masked:
            payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
        return opcode, payload

    def _send_frame(self, payload: bytes, opcode: int = 0x1) -> None:
        header = bytearray()
        header.append(0x80 | opcode)
        n = len(payload)
        mask_bit = 0x80
        if n < 126:
            header.append(mask_bit | n)
        elif n < 65536:
            header.append(mask_bit | 126)
            header += struct.pack(">H", n)
        else:
            header.append(mask_bit | 127)
            header += struct.pack(">Q", n)
        key = os.urandom(4)
        masked = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + key + masked)


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 4:
        print("用法: python client/event_stream.py <host> <port> <token>")
        raise SystemExit(1)
    with EventStream(sys.argv[1], int(sys.argv[2]), sys.argv[3]) as stream:
        for ev in stream:
            print(json.dumps(ev, ensure_ascii=False))
