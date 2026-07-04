package io.chronos.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.parse.TagValue;
import io.chronos.engine.cache.CurrentValueCache;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CurrentValueCacheTest {

    private static final String TAG = "plant1.line2.temperature";

    @Test
    void storesAndReturnsLatest() {
        CurrentValueCache cache = new CurrentValueCache();
        cache.put(TagValue.good(TAG, 20.0, Instant.ofEpochSecond(100)));
        cache.put(TagValue.good(TAG, 21.0, Instant.ofEpochSecond(200)));

        assertEquals(21.0, cache.get(TAG).orElseThrow().value());
        assertEquals(1, cache.size());
    }

    @Test
    void ignoresOutOfOrderOlderUpdate() {
        CurrentValueCache cache = new CurrentValueCache();
        cache.put(TagValue.good(TAG, 21.0, Instant.ofEpochSecond(200)));
        cache.put(TagValue.good(TAG, 99.0, Instant.ofEpochSecond(100))); // older, must be dropped

        assertEquals(21.0, cache.get(TAG).orElseThrow().value());
    }

    @Test
    void missingTagReturnsEmpty() {
        assertTrue(new CurrentValueCache().get("nope").isEmpty());
    }
}
