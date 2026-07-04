package io.chronos.api.history;

import io.chronos.api.parse.TagValue;
import java.time.Instant;
import java.util.Map;

/**
 * Reconstructed node state at an instant: for each requested tag, its latest value at or
 * before {@code at} (§8 "스냅샷 조회").
 *
 * @param nodeId node the snapshot belongs to
 * @param at     the requested instant
 * @param values tag key → most-recent {@link TagValue} at {@code at}
 */
public record Snapshot(String nodeId, Instant at, Map<String, TagValue> values) {

    public Snapshot {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
