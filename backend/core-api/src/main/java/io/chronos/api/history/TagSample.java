package io.chronos.api.history;

import io.chronos.api.parse.Quality;
import java.util.Objects;

/**
 * One stored time-series sample in a node's local history DB (§8).
 *
 * @param tag      tag key within the node
 * @param value    stored value
 * @param quality  quality flag
 * @param tsMillis epoch milliseconds
 */
public record TagSample(String tag, Object value, Quality quality, long tsMillis) {

    public TagSample {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(quality, "quality");
    }
}
