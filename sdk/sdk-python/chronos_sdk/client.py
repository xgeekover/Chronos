"""Minimal Chronos gateway client (§9): subscribe to and query tags by name over
WebSocket + protobuf. Mirrors the Java/.NET SDK API.

Quickstart:
    from chronos_sdk import ChronosClient
    client = ChronosClient("ws://localhost:8080/ws/gateway", "chronos-dev-token")
    client.connect()
    client.subscribe("plant1.line2.temperature", lambda v: print(v.tag, v.value))
    value = client.get_value("plant1.line2.temperature")
    snap = client.get_snapshot(node_id, at_epoch_millis)
"""

from __future__ import annotations

import gzip
import threading
import time
import uuid
from typing import Callable, Dict, Optional

import websocket  # websocket-client

from . import chronos_pb2 as pb


def _decode(frame: bytes) -> pb.ServerMessage:
    if not frame:
        return pb.ServerMessage()
    flag, body = frame[0], frame[1:]
    if flag == 1:  # gzip
        body = gzip.decompress(body)
    msg = pb.ServerMessage()
    msg.ParseFromString(body)
    return msg


def _frame(message: pb.ClientMessage) -> bytes:
    # client always sends raw (flag 0); the server handles raw and gzip.
    return b"\x00" + message.SerializeToString()


class ChronosClient:
    def __init__(self, url: str, token: str):
        sep = "&" if "?" in url else "?"
        self._url = f"{url}{sep}token={token}"
        self._ws: Optional[websocket.WebSocketApp] = None
        self._latest: Dict[str, pb.TagValue] = {}
        self._callbacks: Dict[str, list] = {}
        self._snapshots: Dict[str, dict] = {}
        self._events: Dict[str, threading.Event] = {}
        self._open = threading.Event()
        self._lock = threading.Lock()

    def connect(self, timeout: float = 10.0) -> None:
        self._ws = websocket.WebSocketApp(
            self._url,
            on_open=lambda ws: self._open.set(),
            on_message=self._on_message,
        )
        threading.Thread(target=self._ws.run_forever, daemon=True).start()
        if not self._open.wait(timeout):
            raise TimeoutError("gateway connection timed out")

    def subscribe(self, tag_key: str, on_update: Callable[[pb.TagValue], None]) -> None:
        self._callbacks.setdefault(tag_key, []).append(on_update)
        msg = pb.ClientMessage()
        msg.subscribe.tags.append(tag_key)
        self._send(msg)

    def get_value(self, tag_key: str) -> Optional[pb.TagValue]:
        return self._latest.get(tag_key)

    def get_snapshot(self, node_id: str, at_epoch_millis: int, timeout: float = 10.0) -> Dict[str, pb.TagValue]:
        correlation_id = str(uuid.uuid4())
        event = threading.Event()
        self._events[correlation_id] = event
        msg = pb.ClientMessage()
        msg.snapshot.correlation_id = correlation_id
        msg.snapshot.node = node_id
        msg.snapshot.at_epoch_millis = at_epoch_millis
        self._send(msg)
        if not event.wait(timeout):
            raise TimeoutError("snapshot request timed out")
        return self._snapshots.pop(correlation_id, {})

    def close(self) -> None:
        if self._ws is not None:
            self._ws.close()

    # internal

    def _send(self, message: pb.ClientMessage) -> None:
        with self._lock:
            self._ws.send(_frame(message), opcode=websocket.ABNF.OPCODE_BINARY)

    def _on_message(self, ws, data) -> None:
        frame = data if isinstance(data, (bytes, bytearray)) else bytes(data, "latin1")
        msg = _decode(frame)
        kind = msg.WhichOneof("body")
        if kind == "update":
            for v in msg.update.values:
                self._latest[v.tag] = v
                for cb in self._callbacks.get(v.tag, []):
                    cb(v)
        elif kind == "snapshot":
            self._snapshots[msg.snapshot.correlation_id] = {v.tag: v for v in msg.snapshot.values}
            ev = self._events.pop(msg.snapshot.correlation_id, None)
            if ev:
                ev.set()


def now_millis() -> int:
    return int(time.time() * 1000)
