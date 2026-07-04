package io.chronos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.Secrets;
import io.chronos.api.adapter.TaskType;
import io.chronos.api.parse.Quality;
import io.chronos.api.parse.TagValue;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreApiTest {

    @Test
    void secretsAreMaskedInToString() {
        Secrets s = new Secrets(Map.of("password", "hunter2", "apiKey", "abc123"));
        String rendered = s.toString();
        assertFalse(rendered.contains("hunter2"), "secret value must not appear in toString");
        assertFalse(rendered.contains("abc123"), "secret value must not appear in toString");
        assertTrue(rendered.contains("password"), "key names may appear");
        assertEquals("hunter2", s.get("password"), "value is still retrievable via get()");
    }

    @Test
    void tagValueGoodFactorySetsQuality() {
        TagValue v = TagValue.good("plant1.line2.temperature", 21.5, Instant.EPOCH);
        assertEquals(Quality.GOOD, v.quality());
        assertEquals(21.5, v.value());
    }

    @Test
    void capabilitiesOfBuildsImmutableSet() {
        Capabilities c = Capabilities.of(TaskType.QUERY);
        assertTrue(c.supportedTaskTypes().contains(TaskType.QUERY));
        assertFalse(c.streaming());
    }
}
