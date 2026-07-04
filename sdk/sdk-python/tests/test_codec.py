"""Validates the generated protobuf classes + frame codec (raw and gzip) without a live server."""

import gzip
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chronos_sdk import chronos_pb2 as pb  # noqa: E402
from chronos_sdk.client import _decode, _frame  # noqa: E402


def _server_update():
    msg = pb.ServerMessage()
    tv = msg.update.values.add()
    tv.tag = "plant1.temperature"
    tv.value = "21.5"
    tv.quality = pb.GOOD
    tv.ts_epoch_millis = 1000
    return msg


def test_decode_raw_frame():
    frame = b"\x00" + _server_update().SerializeToString()
    decoded = _decode(frame)
    assert decoded.WhichOneof("body") == "update"
    assert decoded.update.values[0].tag == "plant1.temperature"
    assert decoded.update.values[0].value == "21.5"


def test_decode_gzip_frame():
    frame = b"\x01" + gzip.compress(_server_update().SerializeToString())
    decoded = _decode(frame)
    assert decoded.update.values[0].value == "21.5"


def test_client_frame_is_raw_and_parseable():
    msg = pb.ClientMessage()
    msg.subscribe.tags.append("plant1.temperature")
    frame = _frame(msg)
    assert frame[0] == 0  # raw flag
    parsed = pb.ClientMessage()
    parsed.ParseFromString(frame[1:])
    assert list(parsed.subscribe.tags) == ["plant1.temperature"]
