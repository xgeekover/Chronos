package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronos.api.history.HistoryStore;
import io.chronos.api.history.TagSample;
import io.chronos.api.parse.Quality;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.TagService;
import io.chronos.engine.broadcast.TagUpdateListener;
import io.chronos.sdk.ChronosClient;
import io.chronos.sdk.TagValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Phase 5 gateway/SDK E2E: the real Java SDK connects over WebSocket (token auth), subscribes by
 * tag name, receives a pushed update, reads it via {@code getValue}, and fetches a Time-Machine
 * snapshot via {@code getSnapshot} (§9, §12 North-Star "Java SDK가 태그명으로 값 수신").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GatewayE2ETest {

    @LocalServerPort int port;
    @Autowired TagUpdateListener broadcast; // GatewayBroadcaster
    @Autowired HistoryStore historyStore;
    @Autowired NodeService nodes;
    @Autowired TagService tags;

    @Test
    void sdkSubscribesReceivesPushAndFetchesSnapshot() throws Exception {
        String url = "ws://localhost:" + port + "/ws/gateway";
        try (ChronosClient client = new ChronosClient(url, "chronos-dev-token")) {
            client.connect();

            // 1) subscribe by tag name and receive a pushed update
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<TagValue> received = new AtomicReference<>();
            client.subscribe("gw.temperature", v -> {
                received.set(v);
                latch.countDown();
            });

            var pushed = new io.chronos.api.parse.TagValue("gw.temperature", 42.0, Quality.GOOD, Instant.now());
            for (int i = 0; i < 25 && latch.getCount() > 0; i++) {
                broadcast.onUpdates("node-x", List.of(pushed)); // re-push until subscription is registered
                latch.await(200, TimeUnit.MILLISECONDS);
            }
            assertThat(latch.getCount()).as("update should be received by the SDK").isZero();
            assertThat(received.get().asDouble()).isEqualTo(42.0);

            // 2) getValue returns the latest pushed value
            assertThat(client.getValue("gw.temperature").orElseThrow().asDouble()).isEqualTo(42.0);

            // 3) getSnapshot returns the node's Time-Machine state at an instant
            NodeEntity node = nodes.create("gw-node", "gateway e2e", 24);
            TagEntity tag = tags.create(node.getId(), "temperature", "NUMBER", "C", null);
            historyStore.append(node.getId().toString(),
                    List.of(new TagSample(tag.getCanonicalKey(), "19.5", Quality.GOOD, Instant.now().toEpochMilli())));

            Map<String, TagValue> snap = client.getSnapshot(node.getId().toString(), Instant.now());
            assertThat(snap).containsKey("gw-node.temperature");
            assertThat(snap.get("gw-node.temperature").value()).isEqualTo("19.5");
        }
    }
}
