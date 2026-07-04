package io.chronos.app.web;

import io.chronos.app.persistence.FlowAuditEntity;
import io.chronos.app.persistence.FlowAuditRepository;
import io.chronos.app.service.FlowRuntimeManager;
import io.chronos.flow.DebugRecord;
import io.chronos.flow.FlowGraph;
import io.chronos.flow.FlowRuntime;
import io.chronos.flow.HttpReply;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the Node-RED-style flow runtime ({@code core-flow}). Many flows run at once —
 * one {@link FlowRuntime} per {@code flowId}, managed by {@link FlowRuntimeManager}. Deploy/stop,
 * status, metrics and the debug feed are all scoped by {@code flowId}.
 */
@RestController
@RequestMapping("/api/flows")
public class FlowController {

    private final FlowRuntimeManager manager;
    private final FlowAuditRepository audit;

    public FlowController(FlowRuntimeManager manager, MeterRegistry registry, FlowAuditRepository audit) {
        this.manager = manager;
        this.audit = audit;
        // aggregate flow-runtime health across all running flows → Prometheus (/actuator/prometheus)
        Gauge.builder("chronos.flow.messages", manager, FlowRuntimeManager::totalMessages)
                .description("Total messages processed across all running flows").register(registry);
        Gauge.builder("chronos.flow.errors", manager, FlowRuntimeManager::totalErrors)
                .description("Total node errors across all running flows").register(registry);
        Gauge.builder("chronos.flow.nodes", manager, m -> m.totalNodes())
                .description("Deployed node count across all running flows").register(registry);
        Gauge.builder("chronos.flow.running", manager, m -> m.runningFlowCount())
                .description("Number of flows currently running").register(registry);
    }

    /** Deploy + start a flow; replaces only this {@code flowId}, other flows keep running. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestBody FlowGraph graph,
            @RequestParam(required = false) String name,
            @RequestParam(required = false, defaultValue = "default") String flowId,
            @AuthenticationPrincipal Jwt jwt) {
        String actor = jwt.getSubject();
        int nodes = manager.deploy(flowId, name, actor, graph);
        FlowRuntime rt = manager.runtime(flowId);
        long startErrors = rt == null ? 0 : rt.errorCount();
        audit.save(new FlowAuditEntity(actor, "DEPLOY", name, nodes,
                startErrors > 0 ? startErrors + " start error(s)" : "ok"));
        if (startErrors > 0) {
            audit.save(new FlowAuditEntity(actor, "ERROR", name, nodes,
                    startErrors + " node(s) failed to start"));
        }
        return ResponseEntity.ok(Map.of("running", true, "nodes", nodes, "flowId", flowId));
    }

    /** Stop one flow ({@code ?flowId=}), or every running flow when no id is given. */
    @PostMapping("/stop")
    public Map<String, Object> stop(
            @RequestParam(required = false) String flowId,
            @AuthenticationPrincipal Jwt jwt) {
        if (flowId == null) {
            List<String> stopped = manager.stopAll();
            if (!stopped.isEmpty()) {
                audit.save(new FlowAuditEntity(jwt.getSubject(), "STOP", "(all)", stopped.size(), null));
            }
            return Map.of("running", false, "stopped", stopped.size());
        }
        String name = manager.nameOf(flowId);
        boolean was = manager.stop(flowId);
        if (was) {
            audit.save(new FlowAuditEntity(jwt.getSubject(), "STOP", name, null, null));
        }
        return Map.of("running", false, "stopped", was ? 1 : 0);
    }

    /** Status of one flow ({@code ?flowId=}), or the list of all running flows. */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String flowId) {
        if (flowId == null) {
            return Map.of("flows", manager.statusAll());
        }
        FlowRuntime rt = manager.runtime(flowId);
        if (rt == null) {
            return Map.of("running", false, "nodes", 0, "counts", Map.of());
        }
        return Map.of(
                "running", rt.isRunning(),
                "nodes", rt.nodeCount(),
                "counts", rt.counts(),
                "statuses", rt.statuses(),
                "deployName", rt.deployName(),
                "deployBy", rt.deployBy());
    }

    /** Recent flow runtime audit events (deploys/stops/errors), newest first. */
    @GetMapping("/audit")
    public List<FlowAuditEntity> auditTrail() {
        return audit.findTop50ByOrderByAtDesc();
    }

    /** Metrics for one flow ({@code ?flowId=}); without an id, aggregate across running flows. */
    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam(required = false) String flowId) {
        if (flowId != null) {
            FlowRuntime rt = manager.runtime(flowId);
            return rt == null
                    ? Map.of("running", false, "uptimeMs", 0L, "nodes", 0,
                            "totalMessages", 0L, "totalErrors", 0L, "queueDepth", 0)
                    : rt.metrics();
        }
        return Map.of(
                "running", manager.runningFlowCount() > 0,
                "flows", manager.runningFlowCount(),
                "totalMessages", manager.totalMessages(),
                "totalErrors", manager.totalErrors(),
                "nodes", manager.totalNodes());
    }

    /** Manually fire one message from an inject node (inject "now" button). */
    @PostMapping("/inject/{nodeId}")
    public Map<String, Object> inject(
            @PathVariable String nodeId, @RequestParam(required = false) String flowId) {
        return Map.of("fired", manager.injectNow(flowId, nodeId));
    }

    /** Debug messages newest-first for one flow ({@code ?flowId=}), or merged across all flows. */
    @GetMapping("/debug")
    public List<DebugRecord> debug(
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(required = false) String flowId) {
        if (flowId != null) {
            FlowRuntime rt = manager.runtime(flowId);
            return rt == null ? List.of() : rt.debugSince(since);
        }
        return manager.debugSince(since);
    }

    /** Snapshot of flow + global context variables (Inspector Context tab). */
    @GetMapping("/context")
    public Map<String, Object> context(@RequestParam(required = false) String flowId) {
        FlowRuntime rt = flowId == null ? null : manager.runtime(flowId);
        return rt == null ? Map.of("flow", Map.of(), "global", Map.of()) : rt.context();
    }

    @PostMapping("/debug/clear")
    public Map<String, Object> clearDebug(@RequestParam(required = false) String flowId) {
        FlowRuntime rt = flowId == null ? null : manager.runtime(flowId);
        if (rt != null) {
            rt.clearDebug();
        }
        return Map.of("cleared", true);
    }

    /**
     * Inbound endpoint for {@code http-in} nodes (webhook-style). An external caller hits
     * {@code /api/flows/in/<path>}; the manager routes it to whichever running flow registered the
     * path and returns the {@code http-response} reply (or an auto-ack). Public (no JWT).
     */
    @RequestMapping(
            value = "/in/{*path}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
    public ResponseEntity<Object> httpIn(
            @PathVariable String path,
            @RequestBody(required = false) String body,
            HttpServletRequest req)
            throws Exception {
        String p = path.startsWith("/") ? path.substring(1) : path;
        Object out = manager.injectHttp(req.getMethod(), p, body).get(8, TimeUnit.SECONDS);
        if (out instanceof HttpReply r) {
            ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status());
            r.headers().forEach(b::header);
            return b.body(r.body());
        }
        return ResponseEntity.ok(out);
    }
}
