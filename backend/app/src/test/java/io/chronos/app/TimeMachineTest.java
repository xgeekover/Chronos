package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronos.api.history.HistoryStore;
import io.chronos.api.history.Snapshot;
import io.chronos.api.history.TagSample;
import io.chronos.api.parse.Quality;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.service.HistoryService;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.RetentionService;
import io.chronos.app.service.TagService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Phase 3 Time Machine: snapshot/range queries and the retention cleanup (§8). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TimeMachineTest {

    @Autowired HistoryStore store;
    @Autowired HistoryService history;
    @Autowired RetentionService retention;
    @Autowired NodeService nodes;
    @Autowired TagService tags;

    @Test
    void snapshotReturnsLatestAtOrBeforeAndRangeReturnsWindow() {
        NodeEntity node = nodes.create("tm-node", "time machine", 24);
        TagEntity tag = tags.create(node.getId(), "temperature", "NUMBER", "C", null);
        String key = tag.getCanonicalKey(); // tm-node.temperature
        store.append(node.getId().toString(), List.of(
                new TagSample(key, "10", Quality.GOOD, 1000),
                new TagSample(key, "20", Quality.GOOD, 2000),
                new TagSample(key, "30", Quality.GOOD, 3000)));

        Snapshot snap = history.snapshotAt(node.getId(), Instant.ofEpochMilli(2500), Set.of());
        assertThat(snap.values().get(key).value()).isEqualTo("20"); // latest <= 2500

        List<TagSample> range = history.range(node.getId(), key,
                Instant.ofEpochMilli(0), Instant.ofEpochMilli(2500));
        assertThat(range).extracting(s -> s.value()).containsExactly("10", "20");
    }

    @Test
    void retentionCleanupDeletesSamplesOlderThanNodeWindow() {
        NodeEntity node = nodes.create("tm-ret-node", "retention", 1); // 1h retention
        TagEntity tag = tags.create(node.getId(), "v", "NUMBER", null, null);
        String key = tag.getCanonicalKey();
        long now = Instant.now().toEpochMilli();
        store.append(node.getId().toString(), List.of(
                new TagSample(key, "old", Quality.GOOD, now - Duration.ofHours(2).toMillis()),
                new TagSample(key, "new", Quality.GOOD, now)));

        retention.enforceAllRetention();

        List<TagSample> all = history.range(node.getId(), key,
                Instant.ofEpochMilli(0), Instant.ofEpochMilli(now + 1));
        assertThat(all).extracting(s -> s.value()).containsExactly("new");
    }
}
