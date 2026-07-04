package io.chronos.engine.broadcast;

import io.chronos.api.parse.TagValue;
import java.util.List;

/**
 * Receives tag updates after each collection (§7 step 4) for real-time fan-out — the FE
 * dashboard WS and, in Phase 5, the external gateway. Phase 2 uses a no-op by default.
 */
@FunctionalInterface
public interface TagUpdateListener {

    void onUpdates(String nodeId, List<TagValue> updates);

    TagUpdateListener NO_OP = (nodeId, updates) -> {};
}
