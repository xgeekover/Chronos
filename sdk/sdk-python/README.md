# Chronos Python SDK

Subscribe to and query Chronos tags by name over the WebSocket + protobuf gateway (§9).

## Install

```bash
pip install -r requirements.txt   # protobuf, websocket-client
# chronos_sdk/chronos_pb2.py is generated from proto/chronos.proto (protoc --python_out)
```

## Quickstart (5 lines)

```python
from chronos_sdk import ChronosClient, now_millis
client = ChronosClient("ws://localhost:8080/ws/gateway", "chronos-dev-token")
client.connect()
client.subscribe("plant1.line2.temperature", lambda v: print(v.tag, "=", v.value))
value = client.get_value("plant1.line2.temperature")          # latest TagValue
snap = client.get_snapshot(node_id, now_millis())             # Time-Machine snapshot
```

## API

| Method | Purpose |
|--------|---------|
| `connect(timeout=10)` | open the WebSocket (token in the constructor URL) |
| `subscribe(tag_key, on_update)` | stream updates for a tag |
| `get_value(tag_key)` | latest received `TagValue` (or `None`) |
| `get_snapshot(node_id, at_epoch_millis)` | `{tag_key: TagValue}` at an instant |
| `close()` | close the connection |

## Test

```bash
python -m pytest tests/      # codec round-trip (raw + gzip), no server needed
```
