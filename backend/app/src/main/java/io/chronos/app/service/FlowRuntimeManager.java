package io.chronos.app.service;

import io.chronos.flow.DebugRecord;
import io.chronos.flow.FlowGraph;
import io.chronos.flow.FlowRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Runs many flows concurrently, one {@link FlowRuntime} per flow id (Node-RED runs every deployed
 * flow at once). Deploying a flow id replaces only that flow's runtime; other flows keep running.
 */
@Component
public class FlowRuntimeManager {

    /** One running flow: its runtime plus who deployed it and under what name. */
    private record Entry(FlowRuntime runtime, String name, String owner) {}

    private final Map<String, Entry> flows = new ConcurrentHashMap<>();
    private final FlowContextStore contextStore;
    private final CollectionService collection; // for the historian-write ("tag") flow node

    public FlowRuntimeManager(FlowContextStore contextStore, CollectionService collection) {
        this.contextStore = contextStore;
        this.collection = collection;
    }

    /** Deploy + start {@code graph} as flow {@code flowId}; replaces that flow if already running. */
    public synchronized int deploy(String flowId, String name, String owner, FlowGraph graph) {
        Entry prev = flows.remove(flowId);
        if (prev != null) {
            prev.runtime().close();
        }
        // all flows share one persisted global context (Node-RED global) + the historian-write &
        // adapter-read bridges to the collection engine (lets flows feed/pull the historian)
        FlowRuntime rt = new FlowRuntime(
                contextStore.global(), collection::ingestValue, collection::readAdapter);
        rt.deploy(graph);
        rt.start();
        rt.setDeployInfo(name, owner);
        flows.put(flowId, new Entry(rt, name, owner));
        return rt.nodeCount();
    }

    /** Stop + drop one flow. Returns true if it was running. */
    public synchronized boolean stop(String flowId) {
        Entry e = flows.remove(flowId);
        if (e == null) {
            return false;
        }
        e.runtime().close();
        return true;
    }

    /** Stop every running flow (used by the no-arg /stop). Returns the ids that were stopped. */
    public synchronized List<String> stopAll() {
        List<String> ids = new ArrayList<>(flows.keySet());
        flows.values().forEach(e -> e.runtime().close());
        flows.clear();
        return ids;
    }

    public FlowRuntime runtime(String flowId) {
        Entry e = flows.get(flowId);
        return e == null ? null : e.runtime();
    }

    public boolean isRunning(String flowId) {
        return flows.containsKey(flowId);
    }

    public String nameOf(String flowId) {
        Entry e = flows.get(flowId);
        return e == null ? null : e.name();
    }

    /** Per-flow status summary for the overview / tab running-indicators. */
    public List<Map<String, Object>> statusAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        flows.forEach((id, e) -> {
            Map<String, Object> m = new LinkedHashMap<>(e.runtime().metrics());
            m.put("flowId", id);
            out.add(m);
        });
        return out;
    }

    /** Merged debug feed across all running flows (each record tagged with its flow id via nodeId). */
    public List<DebugRecord> debugSince(long since) {
        List<DebugRecord> out = new ArrayList<>();
        flows.values().forEach(e -> out.addAll(e.runtime().debugSince(since)));
        out.sort((a, b) -> Long.compare(b.seq(), a.seq())); // newest first
        return out;
    }

    /** Manually fire an inject node; uses {@code flowId} when given, else searches all flows. */
    public boolean injectNow(String flowId, String nodeId) {
        FlowRuntime rt = flowId == null ? null : runtime(flowId);
        if (rt != null) {
            return rt.injectNow(nodeId);
        }
        for (Entry e : flows.values()) {
            if (e.runtime().injectNow(nodeId)) {
                return true;
            }
        }
        return false;
    }

    /** Route an inbound webhook to whichever running flow registered {@code path}. */
    public CompletableFuture<Object> injectHttp(String method, String path, Object payload) {
        for (Entry e : flows.values()) {
            if (e.runtime().hasHttpPath(path)) {
                return e.runtime().injectHttp(method, path, payload);
            }
        }
        CompletableFuture<Object> f = new CompletableFuture<>();
        f.complete(Map.of("error", "no running http-in node for path: " + path));
        return f;
    }

    // ── aggregate metrics for Prometheus gauges ──
    public long totalMessages() {
        return flows.values().stream().mapToLong(e -> e.runtime().messageCount()).sum();
    }

    public long totalErrors() {
        return flows.values().stream().mapToLong(e -> e.runtime().errorCount()).sum();
    }

    public int totalNodes() {
        return flows.values().stream().mapToInt(e -> e.runtime().nodeCount()).sum();
    }

    public int runningFlowCount() {
        return flows.size();
    }
}
