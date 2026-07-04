package io.chronos.app.web;

import io.chronos.app.persistence.CollectionLogEntity;
import io.chronos.app.service.CollectionLogService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Collection-log view (§10): recent task runs, optionally filtered by task. */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private final CollectionLogService logs;

    public LogsController(CollectionLogService logs) {
        this.logs = logs;
    }

    @GetMapping
    public List<CollectionLogEntity> recent(
            @RequestParam(required = false) UUID taskId,
            @RequestParam(defaultValue = "100") int limit) {
        return logs.recent(taskId, limit);
    }
}
