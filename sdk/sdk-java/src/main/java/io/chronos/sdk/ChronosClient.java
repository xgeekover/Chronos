package io.chronos.sdk;

import com.google.protobuf.InvalidProtocolBufferException;
import io.chronos.proto.ClientMessage;
import io.chronos.proto.ServerMessage;
import io.chronos.proto.SnapshotReq;
import io.chronos.proto.Subscribe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Minimal Chronos gateway client (§9): connect with a token, subscribe to tags by name, read the
 * latest value, and fetch a Time-Machine snapshot. Talks protobuf over the JDK WebSocket.
 *
 * <pre>{@code
 * ChronosClient client = new ChronosClient("ws://localhost:8080/ws/gateway", "chronos-dev-token");
 * client.connect();
 * client.subscribe("plant1.temperature", v -> System.out.println(v.tag() + " = " + v.value()));
 * Optional<TagValue> v = client.getValue("plant1.temperature");
 * }</pre>
 */
public final class ChronosClient implements AutoCloseable {

    private final URI uri;
    private final HttpClient http = HttpClient.newHttpClient();

    private final Map<String, TagValue> latest = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<TagValue>>> callbacks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<TagValue>> allCallbacks = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Map<String, TagValue>>> snapshots = new ConcurrentHashMap<>();

    private volatile WebSocket ws;
    private final Object sendLock = new Object();

    public ChronosClient(String url, String token) {
        this.uri = URI.create(url + (url.contains("?") ? "&" : "?") + "token=" + token);
    }

    /** Open the connection (blocks until the handshake completes). */
    public void connect() {
        this.ws = http.newWebSocketBuilder()
                .buildAsync(uri, new Listener())
                .join();
    }

    /** Subscribe to one tag by canonical key; {@code onUpdate} fires on every value. */
    public void subscribe(String tagKey, Consumer<TagValue> onUpdate) {
        callbacks.computeIfAbsent(tagKey, k -> new CopyOnWriteArrayList<>()).add(onUpdate);
        send(ClientMessage.newBuilder()
                .setSubscribe(Subscribe.newBuilder().addTags(tagKey))
                .build());
    }

    /** Subscribe to every update (or pass specific tags); {@code onUpdate} fires for each value. */
    public void subscribeAll(Consumer<TagValue> onUpdate, String... tags) {
        allCallbacks.add(onUpdate);
        Subscribe.Builder sub = Subscribe.newBuilder();
        for (String t : tags) {
            sub.addTags(t);
        }
        send(ClientMessage.newBuilder().setSubscribe(sub).build());
    }

    /** Latest value received for a tag (after subscribing). */
    public Optional<TagValue> getValue(String tagKey) {
        return Optional.ofNullable(latest.get(tagKey));
    }

    /** Time-Machine snapshot of a node at an instant: canonical tag key → value. */
    public Map<String, TagValue> getSnapshot(String nodeId, Instant at) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<Map<String, TagValue>> future = new CompletableFuture<>();
        snapshots.put(correlationId, future);
        send(ClientMessage.newBuilder()
                .setSnapshot(SnapshotReq.newBuilder()
                        .setCorrelationId(correlationId)
                        .setNode(nodeId)
                        .setAtEpochMillis(at.toEpochMilli()))
                .build());
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            snapshots.remove(correlationId);
            throw new IllegalStateException("snapshot request failed: " + e.getMessage(), e);
        }
    }

    private void send(ClientMessage message) {
        // frame = [0 = raw][protobuf]; the server handles raw and gzip frames.
        byte[] payload = message.toByteArray();
        byte[] frame = new byte[payload.length + 1];
        System.arraycopy(payload, 0, frame, 1, payload.length);
        synchronized (sendLock) {
            ws.sendBinary(ByteBuffer.wrap(frame), true).join();
        }
    }

    @Override
    public void close() {
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private void dispatch(byte[] frame) throws InvalidProtocolBufferException {
        ServerMessage msg = decode(frame);
        switch (msg.getBodyCase()) {
            case UPDATE -> msg.getUpdate().getValuesList().forEach(pv -> {
                TagValue tv = toTagValue(pv);
                latest.put(tv.tag(), tv);
                callbacks.getOrDefault(tv.tag(), new CopyOnWriteArrayList<>()).forEach(cb -> cb.accept(tv));
                allCallbacks.forEach(cb -> cb.accept(tv));
            });
            case SNAPSHOT -> {
                var snap = msg.getSnapshot();
                CompletableFuture<Map<String, TagValue>> f = snapshots.remove(snap.getCorrelationId());
                if (f != null) {
                    Map<String, TagValue> values = new ConcurrentHashMap<>();
                    snap.getValuesList().forEach(pv -> values.put(pv.getTag(), toTagValue(pv)));
                    f.complete(values);
                }
            }
            default -> { /* heartbeat / error ignored by the minimal client */ }
        }
    }

    private static TagValue toTagValue(io.chronos.proto.TagValue pv) {
        return new TagValue(pv.getTag(), pv.getValue(), pv.getQuality().name(), pv.getTsEpochMillis());
    }

    private static ServerMessage decode(byte[] frame) throws InvalidProtocolBufferException {
        if (frame.length == 0) {
            return ServerMessage.getDefaultInstance();
        }
        byte flag = frame[0];
        byte[] body = new byte[frame.length - 1];
        System.arraycopy(frame, 1, body, 0, body.length);
        if (flag == 1) { // gzip
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gz.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("gunzip failed", e);
            }
        }
        return ServerMessage.parseFrom(body);
    }

    /** Accumulates binary frames (which may arrive in parts) and dispatches complete messages. */
    private final class Listener implements WebSocket.Listener {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buffer.writeBytes(chunk);
            if (last) {
                byte[] frame = buffer.toByteArray();
                buffer.reset();
                try {
                    dispatch(frame);
                } catch (Exception ignored) {
                    // a malformed frame must not kill the read loop
                }
            }
            webSocket.request(1);
            return null;
        }
    }

    /** Connection timeout helper (unused default kept for callers that want a custom client). */
    public static Duration defaultTimeout() {
        return Duration.ofSeconds(10);
    }
}
