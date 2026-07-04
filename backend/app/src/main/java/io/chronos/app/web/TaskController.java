package io.chronos.app.web;

import io.chronos.app.persistence.MappingRuleEntity;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.service.CollectionService;
import io.chronos.app.service.MappingService;
import io.chronos.app.service.TaskService;
import io.chronos.engine.pipeline.CollectionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Task CRUD + mappings + "run now" (manual/preview execution, §10). */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService tasks;
    private final MappingService mappings;
    private final CollectionService collection;

    public TaskController(TaskService tasks, MappingService mappings, CollectionService collection) {
        this.tasks = tasks;
        this.mappings = mappings;
        this.collection = collection;
    }

    public record CreateTask(
            UUID nodeId, UUID deviceId, @NotBlank String type, Map<String, Object> definition,
            @NotBlank String scheduleKind, Long intervalMs, String cronExpr, Integer timeoutMs) {}

    public record CreateMapping(UUID tagId, Map<String, Object> extractor, Map<String, Object> transform) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskEntity create(@Valid @RequestBody CreateTask req) {
        return tasks.create(req.nodeId(), req.deviceId(), req.type(), req.definition(),
                req.scheduleKind(), req.intervalMs(), req.cronExpr(), req.timeoutMs());
    }

    @GetMapping
    public List<TaskEntity> list() {
        return tasks.list();
    }

    @GetMapping("/{id}")
    public TaskEntity get(@PathVariable UUID id) {
        return tasks.get(id);
    }

    @PutMapping("/{id}")
    public TaskEntity update(@PathVariable UUID id, @RequestBody CreateTask req) {
        return tasks.update(id, req.type(), req.definition(),
                req.scheduleKind(), req.intervalMs(), req.cronExpr(), req.timeoutMs());
    }

    @PostMapping("/{id}/enable")
    public TaskEntity enable(@PathVariable UUID id, @RequestParam boolean value) {
        return tasks.setEnabled(id, value);
    }

    @PostMapping("/{id}/run")
    public CollectionResult run(@PathVariable UUID id) {
        return collection.runTaskNow(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tasks.delete(id);
    }

    @PostMapping("/{taskId}/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public MappingRuleEntity addMapping(@PathVariable UUID taskId, @RequestBody CreateMapping req) {
        return mappings.create(taskId, req.tagId(), req.extractor(), req.transform());
    }

    @GetMapping("/{taskId}/mappings")
    public List<MappingRuleEntity> listMappings(@PathVariable UUID taskId) {
        return mappings.listByTask(taskId);
    }
}
