package io.chronos.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.history.Snapshot;
import io.chronos.api.history.TagSample;
import io.chronos.api.parse.Quality;
import io.chronos.engine.history.SqliteHistoryStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteHistoryStoreTest {

    private static final String NODE = "node-1";
    private static final String TAG = "plant1.temp";

    @Test
    void appendThenSnapshotReturnsLatestAtOrBefore(@TempDir Path dir) {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir)) {
            store.append(NODE, List.of(
                    new TagSample(TAG, "20.0", Quality.GOOD, 1000),
                    new TagSample(TAG, "21.0", Quality.GOOD, 2000),
                    new TagSample(TAG, "22.0", Quality.GOOD, 3000)));

            Snapshot snap = store.snapshotAt(NODE, Instant.ofEpochMilli(2500), Set.of(TAG));
            assertEquals("21.0", snap.values().get(TAG).value()); // latest <= 2500
        }
    }

    @Test
    void rangeReturnsOrderedWindow(@TempDir Path dir) {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir)) {
            store.append(NODE, List.of(
                    new TagSample(TAG, "1", Quality.GOOD, 1000),
                    new TagSample(TAG, "2", Quality.GOOD, 2000),
                    new TagSample(TAG, "3", Quality.GOOD, 3000)));

            List<TagSample> range = store.range(NODE, TAG, Instant.ofEpochMilli(1500), Instant.ofEpochMilli(3000));
            assertEquals(2, range.size());
            assertEquals("2", range.get(0).value());
            assertEquals("3", range.get(1).value());
        }
    }

    @Test
    void retentionDeletesOldSamples(@TempDir Path dir) {
        try (SqliteHistoryStore store = new SqliteHistoryStore(dir)) {
            long now = Instant.now().toEpochMilli();
            store.append(NODE, List.of(
                    new TagSample(TAG, "old", Quality.GOOD, now - Duration.ofHours(48).toMillis()),
                    new TagSample(TAG, "new", Quality.GOOD, now)));

            store.enforceRetention(NODE, Duration.ofHours(24));

            List<TagSample> all = store.range(NODE, TAG, Instant.ofEpochMilli(0), Instant.ofEpochMilli(now + 1));
            assertEquals(1, all.size());
            assertEquals("new", all.get(0).value());
            assertTrue(all.stream().noneMatch(s -> "old".equals(s.value())));
        }
    }
}
