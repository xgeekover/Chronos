package io.chronos.gateway;

import io.chronos.api.parse.TagValue;
import io.chronos.engine.broadcast.TagUpdateListener;
import io.chronos.proto.ServerMessage;
import io.chronos.proto.TagUpdate;
import java.util.List;

/**
 * Bridges the collection pipeline to connected SDK clients (§7 step 4, §9): on each batch of tag
 * updates it pushes a compressed protobuf frame to every client subscribed to those tags.
 */
public final class GatewayBroadcaster implements TagUpdateListener {

    private final SubscriptionManager subscriptions;
    private final FrameCodec codec;

    public GatewayBroadcaster(SubscriptionManager subscriptions, FrameCodec codec) {
        this.subscriptions = subscriptions;
        this.codec = codec;
    }

    @Override
    public void onUpdates(String node, List<TagValue> updates) {
        subscriptions.all().forEach((sink, sub) -> {
            List<io.chronos.proto.TagValue> matched = updates.stream()
                    .filter(v -> sub.matches(v.tagKey()))
                    .map(ProtoMappers::toProto)
                    .toList();
            if (!matched.isEmpty()) {
                ServerMessage msg = ServerMessage.newBuilder()
                        .setUpdate(TagUpdate.newBuilder()
                                .setNode(node == null ? "" : node)
                                .addAllValues(matched))
                        .build();
                try {
                    sink.send(codec.encode(msg));
                } catch (RuntimeException ignored) {
                    // a slow/broken client must not break the pipeline; it is reaped on close
                }
            }
        });
    }
}
