package io.chronos.engine.cache;

import io.chronos.api.parse.TagValue;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory latest-value-per-tag cache feeding the real-time dashboard and WS broadcast
 * (§7 step 4, ADR-011). Single-backend-instance assumption (Q1); a distributed
 * implementation (e.g. Redis) can replace this behind the same shape if needed.
 *
 * <p>A value is only replaced when the incoming sample is at least as new as the stored one,
 * so out-of-order updates never move a tag backwards in time.
 */
public final class CurrentValueCache {

    private final Map<String, TagValue> latest = new ConcurrentHashMap<>();

    /** Insert/replace the latest value for a tag if {@code value} is not older than the stored one. */
    public void put(TagValue value) {
        latest.merge(value.tagKey(), value, (existing, incoming) ->
                incoming.ts().isBefore(existing.ts()) ? existing : incoming);
    }

    public Optional<TagValue> get(String tagKey) {
        return Optional.ofNullable(latest.get(tagKey));
    }

    /** Drop a tag's cached value (e.g. when the tag is deleted), so stale values don't linger. */
    public void evict(String tagKey) {
        latest.remove(tagKey);
    }

    public Collection<TagValue> all() {
        return latest.values();
    }

    public int size() {
        return latest.size();
    }
}
