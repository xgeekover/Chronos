package io.chronos.app.service;

import io.chronos.app.persistence.MappingRuleRepository;
import io.chronos.app.persistence.TaskRepository;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.schedule.TaskSchedulerService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for Tasks; (un)registers the Quartz schedule on each mutation (ADR-003). */
@Service
public class TaskService {

    private final TaskRepository repo;
    private final TaskSchedulerService scheduler;
    private final NodeService nodes;
    private final DeviceService devices;
    private final MappingRuleRepository mappings;

    public TaskService(TaskRepository repo, TaskSchedulerService scheduler,
            NodeService nodes, DeviceService devices, MappingRuleRepository mappings) {
        this.repo = repo;
        this.scheduler = scheduler;
        this.nodes = nodes;
        this.devices = devices;
        this.mappings = mappings;
    }

    @Transactional
    public TaskEntity create(UUID nodeId, UUID deviceId, String type, Map<String, Object> definition,
            String scheduleKind, Long intervalMs, String cronExpr, Integer timeoutMs) {
        nodes.get(nodeId);     // validate existence
        devices.get(deviceId); // validate existence
        if ("INTERVAL".equals(scheduleKind) && (intervalMs == null || intervalMs <= 0)) {
            throw new IllegalArgumentException("INTERVAL schedule requires a positive intervalMs");
        }
        if ("CRON".equals(scheduleKind) && (cronExpr == null || cronExpr.isBlank())) {
            throw new IllegalArgumentException("CRON schedule requires a cronExpr");
        }
        TaskEntity t = new TaskEntity();
        t.setNodeId(nodeId);
        t.setDeviceId(deviceId);
        t.setType(type);
        t.setDefinition(definition == null ? Map.of() : definition);
        t.setScheduleKind(scheduleKind);
        t.setIntervalMs("INTERVAL".equals(scheduleKind) ? intervalMs : null);
        t.setCronExpr("CRON".equals(scheduleKind) ? cronExpr : null);
        if (timeoutMs != null) {
            t.setTimeoutMs(timeoutMs);
        }
        TaskEntity saved = repo.save(t);
        scheduler.schedule(saved);
        return saved;
    }

    public List<TaskEntity> list() {
        return repo.findAll();
    }

    public TaskEntity get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("task not found: " + id));
    }

    @Transactional
    public TaskEntity setEnabled(UUID id, boolean enabled) {
        TaskEntity t = get(id);
        t.setEnabled(enabled);
        TaskEntity saved = repo.save(t);
        scheduler.schedule(saved); // schedule() removes the job when disabled
        return saved;
    }

    @Transactional
    public TaskEntity update(UUID id, String type, Map<String, Object> definition,
            String scheduleKind, Long intervalMs, String cronExpr, Integer timeoutMs) {
        TaskEntity t = get(id);
        if (type != null) {
            t.setType(type);
        }
        if (definition != null) {
            t.setDefinition(definition);
        }
        if (scheduleKind != null) {
            if ("INTERVAL".equals(scheduleKind) && (intervalMs == null || intervalMs <= 0)) {
                throw new IllegalArgumentException("INTERVAL schedule requires a positive intervalMs");
            }
            if ("CRON".equals(scheduleKind) && (cronExpr == null || cronExpr.isBlank())) {
                throw new IllegalArgumentException("CRON schedule requires a cronExpr");
            }
            t.setScheduleKind(scheduleKind);
            t.setIntervalMs("INTERVAL".equals(scheduleKind) ? intervalMs : null);
            t.setCronExpr("CRON".equals(scheduleKind) ? cronExpr : null);
        }
        if (timeoutMs != null) {
            t.setTimeoutMs(timeoutMs);
        }
        TaskEntity saved = repo.save(t);
        scheduler.schedule(saved); // re-register with the (possibly new) schedule
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        scheduler.unschedule(id.toString());
        mappings.deleteAll(mappings.findByTaskId(id)); // cascade: drop this task's mappings
        repo.deleteById(id);
    }
}
