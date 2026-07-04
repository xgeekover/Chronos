package io.chronos.api.adapter;

import java.util.Set;

/**
 * Declares what an adapter supports so the engine/UI can validate Task definitions
 * before execution.
 *
 * @param supportedTaskTypes task types this adapter can execute
 * @param streaming          whether the adapter can stream large results incrementally
 *                           (reserved for a future streaming collect path)
 */
public record Capabilities(Set<TaskType> supportedTaskTypes, boolean streaming) {

    public Capabilities {
        supportedTaskTypes = supportedTaskTypes == null ? Set.of() : Set.copyOf(supportedTaskTypes);
    }

    public static Capabilities of(TaskType... types) {
        return new Capabilities(Set.of(types), false);
    }
}
