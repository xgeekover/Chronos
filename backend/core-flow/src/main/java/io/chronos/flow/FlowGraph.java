package io.chronos.flow;

import java.util.List;
import java.util.Map;

/**
 * A deployable flow: nodes + wires (the editor's serialized graph). A wire connects a source node's
 * output {@code port} to a target node's single input.
 */
public record FlowGraph(List<NodeDef> nodes, List<WireDef> wires, Map<String, String> env) {

    public FlowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        wires = wires == null ? List.of() : List.copyOf(wires);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    /** Convenience for callers/tests that don't use environment variables. */
    public FlowGraph(List<NodeDef> nodes, List<WireDef> wires) {
        this(nodes, wires, Map.of());
    }

    public record NodeDef(String id, String type, Map<String, Object> config) {
        public NodeDef {
            config = config == null ? Map.of() : config;
        }
    }

    public record WireDef(String source, int port, String target) {}
}
