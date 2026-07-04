package io.chronos.api.history;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Per-node time-series store backing the Time Machine (§6, §8). The default implementation
 * is SQLite-per-node (ADR-004); DuckDB/RocksDB can replace it without touching the core
 * since everything goes through this interface.
 */
public interface HistoryStore {

    /** Append a batch of samples for a node (§7 step 4). */
    void append(String nodeId, List<TagSample> samples);

    /** Reconstruct node state at instant {@code t}: latest value &le; {@code t} per tag. */
    Snapshot snapshotAt(String nodeId, Instant t, Set<String> tags);

    /** All samples for one tag within {@code [from, to]}, ordered by time. */
    List<TagSample> range(String nodeId, String tag, Instant from, Instant to);

    /** Delete samples older than {@code now - retention} for the node (§8 정리 잡). */
    void enforceRetention(String nodeId, Duration retention);
}
