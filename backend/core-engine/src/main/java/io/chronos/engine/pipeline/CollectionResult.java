package io.chronos.engine.pipeline;

import io.chronos.api.parse.TagValue;
import java.util.List;

/**
 * Outcome of one Task execution through the pipeline (feeds CollectionLog, §7 step 5).
 *
 * @param status     OK / TIMEOUT / ERROR
 * @param tagCount   number of tag values produced
 * @param durationMs wall-clock duration of the collection
 * @param error      error message when status != OK, else null
 * @param values     the produced tag values (also pushed to cache/history/broadcast)
 */
public record CollectionResult(
        Status status, int tagCount, long durationMs, String error, List<TagValue> values) {

    public enum Status { OK, TIMEOUT, ERROR }

    public boolean ok() {
        return status == Status.OK;
    }
}
