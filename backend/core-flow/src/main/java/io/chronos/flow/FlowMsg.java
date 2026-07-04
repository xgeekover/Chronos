package io.chronos.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A flow message — the unit that travels along wires (Node-RED's {@code msg}). The property MAP is
 * immutable (nodes produce new messages with {@link #set}), so replacing a top-level property on one
 * branch never affects a sibling branch. NOTE: the guarantee is <b>shallow</b> — a nested {@code List}/
 * {@code Map} payload is shared by reference across a fan-out, so a node that mutates a nested value
 * <i>in place</i> (only possible in a Java {@code function}) would be seen by other branches. Node
 * implementations must treat payloads as read-only and build new values instead of mutating in place.
 * {@code payload} is the conventional primary field; arbitrary properties (e.g. {@code topic}) are
 * supported.
 */
public record FlowMsg(Map<String, Object> props) {

    public FlowMsg {
        // lenient unmodifiable copy (allows null values, unlike Map.copyOf)
        props = Collections.unmodifiableMap(
                new LinkedHashMap<>(props == null ? Map.of() : props));
    }

    public static FlowMsg of(Object payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payload", payload);
        return new FlowMsg(m);
    }

    public Object payload() {
        return props.get("payload");
    }

    public Object get(String key) {
        return props.get(key);
    }

    /** Return a copy with {@code key} set (or removed when {@code value} is null). */
    public FlowMsg set(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(props);
        if (value == null) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
        return new FlowMsg(m);
    }
}
