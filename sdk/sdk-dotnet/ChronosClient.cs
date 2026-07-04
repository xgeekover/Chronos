using System;
using System.Collections.Concurrent;
using System.IO;
using System.IO.Compression;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;
using Chronos.Proto; // generated from proto/chronos.proto by Grpc.Tools
using Google.Protobuf;

namespace Chronos.Sdk;

/// <summary>
/// Minimal Chronos gateway client (§9): subscribe to and query tags by name over the protobuf
/// WebSocket gateway. Mirrors the Java/Python SDK API.
/// </summary>
public sealed class ChronosClient : IAsyncDisposable
{
    private readonly Uri _uri;
    private readonly ClientWebSocket _ws = new();
    private readonly ConcurrentDictionary<string, TagValue> _latest = new();
    private readonly ConcurrentDictionary<string, Action<TagValue>> _callbacks = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<ConcurrentDictionary<string, TagValue>>> _snapshots = new();
    private readonly CancellationTokenSource _cts = new();

    public ChronosClient(string url, string token)
    {
        var sep = url.Contains('?') ? "&" : "?";
        _uri = new Uri($"{url}{sep}token={token}");
    }

    public async Task ConnectAsync()
    {
        await _ws.ConnectAsync(_uri, CancellationToken.None);
        _ = Task.Run(ReceiveLoop);
    }

    public async Task SubscribeAsync(string tagKey, Action<TagValue> onUpdate)
    {
        _callbacks[tagKey] = onUpdate;
        var msg = new ClientMessage { Subscribe = new Subscribe() };
        msg.Subscribe.Tags.Add(tagKey);
        await SendAsync(msg);
    }

    public TagValue? GetValue(string tagKey) => _latest.TryGetValue(tagKey, out var v) ? v : null;

    public async Task<ConcurrentDictionary<string, TagValue>> GetSnapshotAsync(string nodeId, long atEpochMillis)
    {
        var correlationId = Guid.NewGuid().ToString();
        var tcs = new TaskCompletionSource<ConcurrentDictionary<string, TagValue>>();
        _snapshots[correlationId] = tcs;
        var msg = new ClientMessage
        {
            Snapshot = new SnapshotReq { CorrelationId = correlationId, Node = nodeId, AtEpochMillis = atEpochMillis }
        };
        await SendAsync(msg);
        return await tcs.Task.WaitAsync(TimeSpan.FromSeconds(10));
    }

    private async Task SendAsync(ClientMessage message)
    {
        // frame = [0 = raw][protobuf]; the server handles raw and gzip frames.
        var payload = message.ToByteArray();
        var frame = new byte[payload.Length + 1];
        Buffer.BlockCopy(payload, 0, frame, 1, payload.Length);
        await _ws.SendAsync(frame, WebSocketMessageType.Binary, true, CancellationToken.None);
    }

    private async Task ReceiveLoop()
    {
        var buffer = new byte[1 << 16];
        var acc = new MemoryStream();
        while (_ws.State == WebSocketState.Open && !_cts.IsCancellationRequested)
        {
            var result = await _ws.ReceiveAsync(buffer, _cts.Token);
            if (result.MessageType == WebSocketMessageType.Close) break;
            acc.Write(buffer, 0, result.Count);
            if (!result.EndOfMessage) continue;
            Dispatch(Decode(acc.ToArray()));
            acc.SetLength(0);
        }
    }

    private void Dispatch(ServerMessage msg)
    {
        switch (msg.BodyCase)
        {
            case ServerMessage.BodyOneofCase.Update:
                foreach (var v in msg.Update.Values)
                {
                    _latest[v.Tag] = v;
                    if (_callbacks.TryGetValue(v.Tag, out var cb)) cb(v);
                }
                break;
            case ServerMessage.BodyOneofCase.Snapshot:
                if (_snapshots.TryRemove(msg.Snapshot.CorrelationId, out var tcs))
                {
                    var map = new ConcurrentDictionary<string, TagValue>();
                    foreach (var v in msg.Snapshot.Values) map[v.Tag] = v;
                    tcs.TrySetResult(map);
                }
                break;
        }
    }

    private static ServerMessage Decode(byte[] frame)
    {
        if (frame.Length == 0) return new ServerMessage();
        var body = frame[1..];
        if (frame[0] == 1) // gzip
        {
            using var gz = new GZipStream(new MemoryStream(body), CompressionMode.Decompress);
            using var outMs = new MemoryStream();
            gz.CopyTo(outMs);
            body = outMs.ToArray();
        }
        return ServerMessage.Parser.ParseFrom(body);
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        if (_ws.State == WebSocketState.Open)
            await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
        _ws.Dispose();
    }
}
