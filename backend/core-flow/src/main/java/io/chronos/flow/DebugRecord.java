package io.chronos.flow;

/** One entry shown in the right Debug sidebar — emitted when a message reaches a Debug node. */
public record DebugRecord(
        long seq, long at, String nodeId, String name, Object topic, Object payload) {}
