package io.chronos.flow;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The message-passing flow runtime (Node-RED-style). A {@link FlowGraph} is deployed, sources start
 * emitting, and messages travel along wires through a single-threaded worker queue (so node code is
 * never run concurrently with itself, and cycles can't blow the stack). Debug nodes append to a
 * capped ring buffer the UI polls.
 *
 * <p>Single active flow at a time (Phase 1). Thread-safe deploy/start/stop.
 */
public final class FlowRuntime {

    private static final int MAX_HOPS = 10_000; // cycle/runaway guard
    private static final int DEBUG_CAP = 500;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(daemon("chronos-flow-worker"));
    private final ScheduledExecutorService sched =
            Executors.newScheduledThreadPool(2, daemon("chronos-flow-sched"));
    private final BlockingQueue<Delivery> queue = new LinkedBlockingQueue<>();
    private final Map<String, FlowNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, List<String>> wires = new ConcurrentHashMap<>(); // "src#port" -> targets
    private final Deque<DebugRecord> debug = new ConcurrentLinkedDeque<>();
    // shared across ALL runtimes so the manager's merged debug feed (many flows) sorts/paginates on one
    // monotonic sequence — a per-instance counter made cross-flow ordering and ?since cursors meaningless.
    private static final AtomicLong seq = new AtomicLong();
    private final Map<String, String> httpInPaths = new ConcurrentHashMap<>(); // path -> http-in nodeId
    private final Map<String, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.LongAdder> counts =
            new ConcurrentHashMap<>(); // nodeId -> messages received (live throughput)
    private final Map<String, String> nodeTypes = new ConcurrentHashMap<>(); // nodeId -> type
    // catch/complete/status handlers: nodeId + scope (empty scope = applies to every node in the flow).
    // CopyOnWriteArrayList: iterated from async callback threads (http/mqtt/tcp/ws) while deploy() clears+
    // repopulates → a plain ArrayList would throw ConcurrentModificationException on redeploy.
    private final List<Handler> catchers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Handler> completers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Handler> statusNodes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, List<String>> links = new ConcurrentHashMap<>(); // link-out id → link-in ids
    private final Map<String, List<String>> callTargets = new ConcurrentHashMap<>(); // link-call id → link-in ids
    private final Map<String, String> statuses = new ConcurrentHashMap<>(); // nodeId → status text
    private final Map<String, Object> flowContext = new ConcurrentHashMap<>(); // cleared on deploy
    private final Map<String, Object> globalContext; // shared across runtimes + persisted (see ctor)
    private volatile Map<String, String> env = Map.of(); // environment variables (env.X in expressions)
    private final java.util.concurrent.atomic.LongAdder totalMessages =
            new java.util.concurrent.atomic.LongAdder(); // cumulative since deploy
    private final java.util.concurrent.atomic.LongAdder totalErrors =
            new java.util.concurrent.atomic.LongAdder(); // node onMessage/start failures
    private volatile long startedAt; // epoch ms of the last start(), for uptime
    private volatile String deployName = ""; // human label of the deployed flow (set by caller)
    private volatile String deployBy = ""; // username that deployed it
    private volatile boolean running;

    // app-layer bridges (injected by the manager; no-op defaults for standalone/test runtimes):
    //  - historianWrite(canonicalKey, value) → true if the tag exists (the "tag" node)
    //  - adapterRead(nodeConfig) → raw payload from a pull adapter (the "device read" node)
    private final java.util.function.BiFunction<String, Object, Boolean> historianWrite;
    private final java.util.function.Function<Map<String, Object>, Object> adapterRead;

    /** Standalone runtime with its own (non-persisted) global context — used by tests. */
    public FlowRuntime() {
        this(new ConcurrentHashMap<>());
    }

    /** Runtime sharing a global context (e.g. persisted + shared across all flows by the manager). */
    public FlowRuntime(Map<String, Object> sharedGlobalContext) {
        this(sharedGlobalContext, (k, v) -> false, cfg -> {
            throw new IllegalStateException("no adapter runtime");
        });
    }

    /** Runtime with a shared global context + app-layer historian-write and adapter-read bridges. */
    public FlowRuntime(
            Map<String, Object> sharedGlobalContext,
            java.util.function.BiFunction<String, Object, Boolean> historianWrite,
            java.util.function.Function<Map<String, Object>, Object> adapterRead) {
        this.globalContext = sharedGlobalContext;
        this.historianWrite = historianWrite;
        this.adapterRead = adapterRead;
    }

    private record Delivery(String target, FlowMsg msg, int hop) {}

    /** A catch/complete node and the set of node ids it watches (empty = all nodes in the flow). */
    private record Handler(String id, java.util.Set<String> scope) {
        boolean watches(String nodeId) {
            return scope.isEmpty() || scope.contains(nodeId);
        }
    }

    /** Replace the active flow (stops the previous one). */
    public synchronized void deploy(FlowGraph graph) {
        stop();
        nodes.clear();
        wires.clear();
        debug.clear();
        httpInPaths.clear();
        pending.clear();
        counts.clear();
        totalMessages.reset();
        totalErrors.reset();
        nodeTypes.clear();
        catchers.clear();
        completers.clear();
        statusNodes.clear();
        links.clear();
        callTargets.clear();
        statuses.clear();
        flowContext.clear(); // flow context is per-deploy; globalContext persists
        env = graph.env(); // environment variables for this deploy
        for (FlowGraph.NodeDef nd : graph.nodes()) {
            nodes.put(nd.id(), FlowNodes.create(nd, sched, this::pushDebug, seq, this::completeHttp,
                    flowContext, globalContext, env, historianWrite, adapterRead));
            nodeTypes.put(nd.id(), nd.type());
            if ("httpin".equals(nd.type()) && nd.config().get("path") != null) {
                httpInPaths.put(String.valueOf(nd.config().get("path")), nd.id());
            }
            if ("catch".equals(nd.type())) {
                catchers.add(new Handler(nd.id(), scopeOf(nd.config(), "scope")));
            } else if ("complete".equals(nd.type())) {
                completers.add(new Handler(nd.id(), scopeOf(nd.config(), "scope")));
            } else if ("status".equals(nd.type())) {
                statusNodes.add(new Handler(nd.id(), scopeOf(nd.config(), "scope")));
            } else if ("linkout".equals(nd.type())) {
                links.put(nd.id(), new ArrayList<>(scopeOf(nd.config(), "links")));
            } else if ("linkcall".equals(nd.type())) {
                callTargets.put(nd.id(), new ArrayList<>(scopeOf(nd.config(), "links")));
            }
        }
        for (FlowGraph.WireDef w : graph.wires()) {
            wires.computeIfAbsent(w.source() + "#" + w.port(), k -> new ArrayList<>()).add(w.target());
        }
    }

    /**
     * Inject an inbound HTTP request into the flow's matching {@code http-in} node and return a future
     * completed by an {@code http-response} node (or auto-accepted after a short timeout).
     */
    /** True if this running flow has an http-in node registered for {@code path}. */
    public boolean hasHttpPath(String path) {
        return running && httpInPaths.containsKey(path);
    }

    public CompletableFuture<Object> injectHttp(String method, String path, Object payload) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        String nodeId = httpInPaths.get(path);
        if (nodeId == null || !running) {
            future.complete(Map.of("error", "no running http-in node for path: " + path));
            return future;
        }
        String corr = UUID.randomUUID().toString();
        pending.put(corr, future);
        route(nodeId, 0,
                FlowMsg.of(payload).set("_corr", corr).set("method", method).set("topic", path), 0);
        sched.schedule(() -> {
            CompletableFuture<Object> f = pending.remove(corr);
            if (f != null) {
                f.complete(Map.of("status", "accepted")); // webhook-style: no http-response wired
            }
        }, 5, TimeUnit.SECONDS);
        return future;
    }

    /** Snapshot of flow + global context (for the Inspector Context tab). */
    public Map<String, Object> context() {
        return Map.of(
                "flow", new java.util.LinkedHashMap<>(flowContext),
                "global", new java.util.LinkedHashMap<>(globalContext));
    }

    void completeHttp(String corr, Object payload) {
        CompletableFuture<Object> f = pending.remove(corr);
        if (f != null) {
            f.complete(payload == null ? "" : payload);
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        worker.submit(this::loop);
        for (Map.Entry<String, FlowNode> e : nodes.entrySet()) {
            String id = e.getKey();
            try {
                e.getValue().start(emitFor(id, 0));
            } catch (RuntimeException ex) {
                totalErrors.increment();
                pushDebug(new DebugRecord(seq.incrementAndGet(), System.currentTimeMillis(),
                        id, "start-error", null, ex.getMessage()));
            }
        }
    }

    public synchronized void stop() {
        running = false;
        for (FlowNode n : nodes.values()) {
            try {
                n.stop();
            } catch (RuntimeException ignored) {
                // best-effort on re-deploy
            }
        }
        queue.clear();
    }

    /**
     * Permanently release this runtime's own executor threads. Unlike {@link #stop()} (which is also
     * called by {@link #deploy} to reset state and MUST leave the executors usable for the next
     * {@code start()}), {@code close()} shuts down the worker + scheduler so a discarded runtime doesn't
     * leak threads. The shared {@code Func.FN_EXEC} pool is static across runtimes and is NOT touched.
     * The manager calls this when it drops a runtime (replace-on-deploy / stop / stopAll).
     */
    public synchronized void close() {
        stop();
        worker.shutdownNow();
        sched.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    public int nodeCount() {
        return nodes.size();
    }

    /** Per-node count of messages received (live throughput), for the editor's node status. */
    public Map<String, Long> counts() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        counts.forEach((id, adder) -> out.put(id, adder.sum()));
        return out;
    }

    public long messageCount() {
        return totalMessages.sum();
    }

    public long errorCount() {
        return totalErrors.sum();
    }

    public int queueDepth() {
        return queue.size();
    }

    /** Record who deployed the active flow and under what name (for runtime visibility). */
    public void setDeployInfo(String name, String by) {
        this.deployName = name == null ? "" : name;
        this.deployBy = by == null ? "" : by;
    }

    public String deployName() {
        return deployName;
    }

    public String deployBy() {
        return deployBy;
    }

    /** Aggregate runtime metrics for the monitor panel and Prometheus gauges. */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("running", running);
        m.put("uptimeMs", running ? System.currentTimeMillis() - startedAt : 0L);
        m.put("nodes", nodes.size());
        m.put("totalMessages", totalMessages.sum());
        m.put("totalErrors", totalErrors.sum());
        m.put("queueDepth", queue.size());
        m.put("deployName", deployName);
        m.put("deployBy", deployBy);
        return m;
    }

    /** Manually fire one message from an inject node (Node-RED's inject "now"). */
    public boolean injectNow(String nodeId) {
        FlowNode node = nodes.get(nodeId);
        if (node == null || !running) {
            return false;
        }
        node.trigger();
        return true;
    }

    /** Recent debug messages, newest first (optionally only those after {@code sinceSeq}). */
    public List<DebugRecord> debugSince(long sinceSeq) {
        List<DebugRecord> out = new ArrayList<>();
        for (DebugRecord r : debug) { // iteration order = newest first (addFirst)
            if (r.seq() > sinceSeq) {
                out.add(r);
            }
        }
        return out;
    }

    public void clearDebug() {
        debug.clear();
    }

    private void route(String srcId, int port, FlowMsg msg, int hop) {
        if (!running || hop > MAX_HOPS) {
            return;
        }
        List<String> targets = wires.get(srcId + "#" + port);
        if (targets == null) {
            return;
        }
        for (String t : targets) {
            queue.offer(new Delivery(t, msg, hop + 1));
        }
    }

    private void loop() {
        while (running) {
            Delivery d;
            try {
                d = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (d == null) {
                continue;
            }
            FlowNode node = nodes.get(d.target());
            if (node == null) {
                continue;
            }
            counts.computeIfAbsent(d.target(), k -> new java.util.concurrent.atomic.LongAdder())
                    .increment();
            totalMessages.increment();
            try {
                node.onMessage(d.msg(), emitFor(d.target(), d.hop()));
                routeComplete(d.target(), d.msg(), d.hop()); // fire complete handlers on success
            } catch (RuntimeException ex) {
                // a misbehaving node must not kill the worker
                dispatchError(d.target(), d.msg(),
                        ex.getMessage() == null ? ex.toString() : ex.getMessage(), d.hop());
            }
        }
    }

    /** An Emit bound to one node: send routes outputs; error logs + routes to catch nodes. */
    private FlowNode.Emit emitFor(String nodeId, int hop) {
        return new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg msg) {
                route(nodeId, port, msg, hop);
            }

            @Override
            public void error(FlowMsg msg, String message) {
                dispatchError(nodeId, msg, message, hop);
            }

            @Override
            public void link(FlowMsg msg) {
                if (hop > MAX_HOPS) {
                    return;
                }
                for (String target : links.getOrDefault(nodeId, List.of())) {
                    queue.offer(new Delivery(target, msg, hop + 1));
                }
            }

            @Override
            public void status(String text) {
                if (text == null) {
                    statuses.remove(nodeId);
                } else {
                    statuses.put(nodeId, text);
                }
                routeStatus(nodeId, text, hop);
            }

            @Override
            public void linkCall(FlowMsg msg) {
                if (hop > MAX_HOPS) {
                    return;
                }
                Object s = msg.get("_linkStack");
                List<Object> stack = s instanceof List<?> l
                        ? new ArrayList<>(l) : new ArrayList<>();
                stack.add(nodeId); // push return address
                FlowMsg m = msg.set("_linkStack", stack);
                for (String target : callTargets.getOrDefault(nodeId, List.of())) {
                    queue.offer(new Delivery(target, m, hop + 1));
                }
            }

            @Override
            public void linkReturn(FlowMsg msg) {
                if (hop > MAX_HOPS) {
                    return;
                }
                Object s = msg.get("_linkStack");
                if (!(s instanceof List<?> l) || l.isEmpty()) {
                    return; // no pending link-call to return to
                }
                List<Object> stack = new ArrayList<>(l);
                String callId = String.valueOf(stack.remove(stack.size() - 1)); // pop
                route(callId, 0, msg.set("_linkStack", stack), hop + 1); // emit from the call's output
            }
        };
    }

    /** Per-node status text (Node-RED node.status), for the editor overlay. */
    public Map<String, String> statuses() {
        return new java.util.LinkedHashMap<>(statuses);
    }

    /** Log an error (debug feed + counter) and route it to every catch node watching {@code failedId}. */
    private void dispatchError(String failedId, FlowMsg msg, String message, int hop) {
        if (!running) {
            return; // a late async callback (http/mqtt/…) from a stopped/redeployed flow
        }
        totalErrors.increment();
        pushDebug(new DebugRecord(seq.incrementAndGet(), System.currentTimeMillis(), failedId,
                "error", null, message));
        String failedType = nodeTypes.getOrDefault(failedId, "");
        // a catch/complete node's own failure must not re-enter error handling (loop guard)
        boolean isHandler = "catch".equals(failedType) || "complete".equals(failedType);
        if (isHandler || catchers.isEmpty() || hop > MAX_HOPS) {
            return;
        }
        FlowMsg errMsg = msg.set("error", Map.of(
                "message", message == null ? "error" : message,
                "source", Map.of("id", failedId, "type", failedType)));
        for (Handler h : catchers) {
            if (h.watches(failedId)) {
                queue.offer(new Delivery(h.id(), errMsg, hop + 1));
            }
        }
    }

    /** Route a successful completion of {@code nodeId} to complete nodes watching it (Node-RED complete). */
    private void routeComplete(String nodeId, FlowMsg msg, int hop) {
        if (completers.isEmpty() || hop > MAX_HOPS) {
            return;
        }
        String type = nodeTypes.getOrDefault(nodeId, "");
        if ("catch".equals(type) || "complete".equals(type)) {
            return;
        }
        for (Handler h : completers) {
            if (h.watches(nodeId)) {
                queue.offer(new Delivery(h.id(), msg, hop + 1));
            }
        }
    }

    /** Route a status event from {@code nodeId} to status nodes watching it (Node-RED status). */
    private void routeStatus(String nodeId, String text, int hop) {
        if (!running || statusNodes.isEmpty() || hop > MAX_HOPS) {
            return;
        }
        String type = nodeTypes.getOrDefault(nodeId, "");
        if ("status".equals(type)) {
            return; // a status node's own status must not re-enter routing
        }
        FlowMsg sm = FlowMsg.of(text == null ? "" : text).set("status", Map.of(
                "text", text == null ? "" : text,
                "source", Map.of("id", nodeId, "type", type)));
        for (Handler h : statusNodes) {
            if (h.watches(nodeId)) {
                queue.offer(new Delivery(h.id(), sm, hop + 1));
            }
        }
    }

    private static java.util.Set<String> scopeOf(Map<String, Object> config, String key) {
        Object s = config.get(key);
        if (s instanceof List<?> list) {
            java.util.Set<String> out = new java.util.HashSet<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        }
        return java.util.Set.of();
    }

    private void pushDebug(DebugRecord r) {
        debug.addFirst(r);
        while (debug.size() > DEBUG_CAP) {
            debug.pollLast();
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
