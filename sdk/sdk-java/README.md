# Chronos Java SDK

Subscribe to and query Chronos tags by name over the WebSocket + protobuf gateway (§9).
Standalone `.jar` (depends only on `protobuf-java` + the JDK).

## Build

```bash
./gradlew :sdk-java:jar   # → sdk/sdk-java/build/libs/sdk-java-*.jar
```

## Quickstart (5 lines)

```java
ChronosClient client = new ChronosClient("ws://localhost:8080/ws/gateway", "chronos-dev-token");
client.connect();
client.subscribe("plant1.line2.temperature", v -> System.out.println(v.tag() + " = " + v.value()));
double now = client.getValue("plant1.line2.temperature").map(TagValue::asDouble).orElse(Double.NaN);
Map<String, TagValue> snap = client.getSnapshot(nodeId, Instant.now()); // Time-Machine
```

## API

| Method | Purpose |
|--------|---------|
| `connect()` | open the WebSocket (token passed in the constructor URL) |
| `subscribe(tagKey, onUpdate)` | stream updates for a tag; callback per value |
| `subscribeAll(onUpdate, tags...)` | subscribe to all (or several) tags |
| `getValue(tagKey)` | latest received value (`Optional<TagValue>`) |
| `getSnapshot(nodeId, at)` | point-in-time values for a node (`Map<tagKey, TagValue>`) |
| `close()` | close the connection |

Frames are protobuf (`proto/chronos.proto`), gzip-decompressed transparently.
