package io.chronos.app.web;

import io.chronos.app.persistence.CollectionLogRepository;
import io.chronos.app.persistence.DeviceRepository;
import io.chronos.app.persistence.NodeRepository;
import io.chronos.app.persistence.TagRepository;
import io.chronos.app.persistence.TaskRepository;
import io.chronos.engine.cache.CurrentValueCache;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard summary (§10): inventory counts, collection success rate, live value count. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DeviceRepository devices;
    private final NodeRepository nodes;
    private final TagRepository tags;
    private final TaskRepository tasks;
    private final CollectionLogRepository logs;
    private final CurrentValueCache cache;

    public DashboardController(DeviceRepository devices, NodeRepository nodes, TagRepository tags,
            TaskRepository tasks, CollectionLogRepository logs,
            CurrentValueCache cache) {
        this.devices = devices;
        this.nodes = nodes;
        this.tags = tags;
        this.tasks = tasks;
        this.logs = logs;
        this.cache = cache;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        long ok = logs.countByStatus("OK");
        long error = logs.countByStatus("ERROR") + logs.countByStatus("TIMEOUT");
        long total = ok + error;
        double successRate = total == 0 ? 1.0 : (double) ok / total;
        return Map.of(
                "devices", devices.count(),
                "nodes", nodes.count(),
                "tags", tags.count(),
                "tasks", tasks.count(),
                "liveValues", cache.size(),
                "collections", Map.of("ok", ok, "error", error, "total", total),
                "successRate", successRate);
    }
}
