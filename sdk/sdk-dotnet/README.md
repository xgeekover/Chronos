# Chronos .NET SDK

Subscribe to and query Chronos tags by name over the WebSocket + protobuf gateway (§9).

> ⚠️ Provided as source. It was **not compiled in this environment** (no .NET SDK installed here).
> `Grpc.Tools` generates `Chronos.Proto.*` from `../../proto/chronos.proto` at build time; verify
> the `Google.Protobuf` version matches your protoc train before shipping.

## Build

```bash
dotnet build            # generates protobuf classes + compiles
dotnet pack             # → Chronos.Sdk.dll / NuGet package
```

## Quickstart (5 lines)

```csharp
await using var client = new ChronosClient("ws://localhost:8080/ws/gateway", "chronos-dev-token");
await client.ConnectAsync();
await client.SubscribeAsync("plant1.line2.temperature", v => Console.WriteLine($"{v.Tag} = {v.Value}"));
var latest = client.GetValue("plant1.line2.temperature");
var snap = await client.GetSnapshotAsync(nodeId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
```

## API

| Method | Purpose |
|--------|---------|
| `ConnectAsync()` | open the WebSocket (token in the constructor URL) |
| `SubscribeAsync(tagKey, onUpdate)` | stream updates for a tag |
| `GetValue(tagKey)` | latest received `TagValue` (or `null`) |
| `GetSnapshotAsync(nodeId, atEpochMillis)` | point-in-time values for a node |
| `DisposeAsync()` | close the connection |
