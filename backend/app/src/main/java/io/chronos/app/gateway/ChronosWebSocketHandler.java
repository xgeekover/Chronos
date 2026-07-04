package io.chronos.app.gateway;

import io.chronos.app.service.HistoryService;
import io.chronos.engine.cache.CurrentValueCache;
import io.chronos.gateway.ClientSink;
import io.chronos.gateway.FrameCodec;
import io.chronos.gateway.ProtoMappers;
import io.chronos.gateway.SubscriptionManager;
import io.chronos.proto.ClientMessage;
import io.chronos.proto.ServerMessage;
import io.chronos.proto.SnapshotReq;
import io.chronos.proto.Subscribe;
import io.chronos.proto.TagUpdate;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * WebSocket endpoint speaking the protobuf gateway protocol (§9). Handles Subscribe / Unsubscribe
 * / SnapshotReq / Heartbeat; on subscribe it immediately pushes current cached values so the SDK's
 * {@code getValue} works right away. Tag updates are pushed by {@link io.chronos.gateway.GatewayBroadcaster}.
 */
@Component
public class ChronosWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChronosWebSocketHandler.class);
    private static final String SINK = "chronos.sink";

    private final SubscriptionManager subscriptions;
    private final FrameCodec codec;
    private final CurrentValueCache cache;
    private final HistoryService history;

    public ChronosWebSocketHandler(SubscriptionManager subscriptions, FrameCodec codec,
            CurrentValueCache cache, HistoryService history) {
        this.subscriptions = subscriptions;
        this.codec = codec;
        this.cache = cache;
        this.history = history;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Decorate for thread-safe concurrent sends (broadcaster threads + handler thread).
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 1 << 20);
        ClientSink sink = frame -> {
            try {
                safe.sendMessage(new BinaryMessage(frame));
            } catch (IOException e) {
                throw new IllegalStateException("send failed", e);
            }
        };
        session.getAttributes().put(SINK, sink);
        subscriptions.register(sink);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ClientSink sink = (ClientSink) session.getAttributes().get(SINK);
        try {
            ByteBuffer buf = message.getPayload();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            ClientMessage msg = codec.decode(data);
            switch (msg.getBodyCase()) {
                case SUBSCRIBE -> onSubscribe(sink, msg.getSubscribe());
                case UNSUBSCRIBE -> subscriptions.unsubscribe(sink, msg.getUnsubscribe().getTagsList());
                case SNAPSHOT -> onSnapshot(sink, msg.getSnapshot());
                case HEARTBEAT -> sink.send(codec.encode(ServerMessage.newBuilder()
                        .setHeartbeat(msg.getHeartbeat()).build()));
                default -> { /* ignore */ }
            }
        } catch (Exception e) {
            log.warn("gateway message handling failed: {}", e.getMessage());
        }
    }

    private void onSubscribe(ClientSink sink, Subscribe sub) {
        subscriptions.subscribe(sink, sub.getTagsList());
        // Initial push so the client has current values immediately.
        Set<String> wanted = Set.copyOf(sub.getTagsList());
        TagUpdate.Builder update = TagUpdate.newBuilder().setNode(sub.getNode());
        cache.all().stream()
                .filter(v -> wanted.isEmpty() || wanted.contains(v.tagKey()))
                .forEach(v -> update.addValues(ProtoMappers.toProto(v)));
        if (update.getValuesCount() > 0) {
            sink.send(codec.encode(ServerMessage.newBuilder().setUpdate(update).build()));
        }
    }

    private void onSnapshot(ClientSink sink, SnapshotReq req) {
        var snap = history.snapshotAt(UUID.fromString(req.getNode()),
                Instant.ofEpochMilli(req.getAtEpochMillis()), Set.copyOf(req.getTagsList()));
        io.chronos.proto.Snapshot.Builder out = io.chronos.proto.Snapshot.newBuilder()
                .setCorrelationId(req.getCorrelationId())
                .setNode(req.getNode())
                .setAtEpochMillis(req.getAtEpochMillis());
        snap.values().values().forEach(v -> out.addValues(ProtoMappers.toProto(v)));
        sink.send(codec.encode(ServerMessage.newBuilder().setSnapshot(out).build()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientSink sink = (ClientSink) session.getAttributes().get(SINK);
        if (sink != null) {
            subscriptions.remove(sink);
        }
    }
}
