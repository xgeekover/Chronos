package io.chronos.api.adapter;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * A single collection request the engine passes to {@code DeviceAdapter.collect()} (§7).
 * The timeout is advisory to the adapter; the engine also enforces it externally.
 *
 * @param taskId     id of the Task being executed
 * @param nodeId     owning Node id
 * @param type       task type
 * @param definition task definition (SQL text / file pattern / HTTP spec / command / script)
 * @param timeout    execution timeout
 */
public record CollectRequest(
        String taskId,
        String nodeId,
        TaskType type,
        Map<String, Object> definition,
        Duration timeout) {

    public CollectRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(type, "type");
        definition = definition == null ? Map.of() : Map.copyOf(definition);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    }
}
