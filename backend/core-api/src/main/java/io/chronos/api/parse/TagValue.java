package io.chronos.api.parse;

import java.time.Instant;
import java.util.Objects;

/**
 * A single normalized data point produced by the collection pipeline.
 *
 * @param tagKey  canonical tag key (e.g. {@code "plant1.line2.temperature"}, ADR-012)
 * @param value   the value; concrete type matches the Tag's declared data type
 * @param quality quality flag (ADR-013)
 * @param ts      timestamp the value is considered effective at
 */
public record TagValue(String tagKey, Object value, Quality quality, Instant ts) {

    public TagValue {
        Objects.requireNonNull(tagKey, "tagKey");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(ts, "ts");
    }

    public static TagValue good(String tagKey, Object value, Instant ts) {
        return new TagValue(tagKey, value, Quality.GOOD, ts);
    }

    public static TagValue bad(String tagKey, Instant ts) {
        return new TagValue(tagKey, null, Quality.BAD, ts);
    }
}
