"""极简 WebSocket 服务端/客户端实现（仅支持本项目需要的子集）。

不引第三方库的原因：整个 PC 侧坚持"标准库 + 零依赖"，方便直接拷到任何环境跑。

只实现：
  - 握手（Sec-WebSocket-Accept）
  - 服务端发送文本帧（不掩码）
  - 客户端发送掩码文本帧、读取服务端帧
不支持：分片、二进制、扩展协商。
"""

from __future__ import annotations

import base64
import hashlib
import os
import struct

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

OP_TEXT = 0x1
OP_CLOSE = 0x8
OP_PING = 0x9
OP_PONG = 0xA


def accept_key(client_key: str) -> str:
    raw = hashlib.sha1((client_key + GUID).encode("ascii")).digest()
    return base64.b64encode(raw).decode("ascii")


def encode_frame(payload: bytes, opcode: int = OP_TEXT, mask: bool = False) -> bytes:
    header = bytearray()
    header.append(0x80 | opcode)  # FIN + opcode

    length = len(payload)
    mask_bit = 0x80 if mask else 0x00
    if length < 126:
        header.append(mask_bit | length)
    elif length < 65536:
        header.append(mask_bit | 126)
        header += struct.pack(">H", length)
    else:
        header.append(mask_bit | 127)
        header += struct.pack(">Q", length)

    if not mask:
        return bytes(header) + payload

    key = os.urandom(4)
    masked = bytes(b ^ key[i % 4] for i, b in enumerate(payload))
    return bytes(header) + key + masked


def read_frame(stream) -> tuple[int, bytes] | None:
    """从文件对象读一帧；连接断开返回 None。"""
    head = stream.read(2)
    if not head or len(head) < 2:
        return None

    b1, b2 = head[0], head[1]
    opcode = b1 & 0x0F
    masked = bool(b2 & 0x80)
    length = b2 & 0x7F

    if length == 126:
        length = struct.unpack(">H", _read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", _read_exact(stream, 8))[0]

    key = _read_exact(stream, 4) if masked else b""
    payload = _read_exact(stream, length)

    if masked:
        payload = bytes(b ^ key[i % 4] for i, b in enumerate(payload))

    return opcode, payload


def _read_exact(stream, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = stream.read(n - len(buf))
        if not chunk:
            raise ConnectionError("连接已断开")
        buf += chunk
    return buf


def handshake_response(client_key: str) -> bytes:
    return (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {accept_key(client_key)}\r\n"
        "\r\n"
    ).encode("ascii")


def client_handshake_request(host: str, port: int, path: str, token: str) -> tuple[bytes, str]:
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        f"X-Auth-Token: {token}\r\n"
        "\r\n"
    ).encode("ascii")
    return req, key
