package io.chronos.app.service;

import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.TaskType;
import io.chronos.api.parse.MappingRule;
import io.chronos.api.parse.TagValue;
import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.persistence.MappingRuleEntity;
import io.chronos.app.persistence.MappingRuleRepository;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.engine.cache.CurrentValueCache;
import io.chronos.engine.pipeline.CollectionPipeline;
import io.chronos.engine.pipeline.CollectionResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bridges metadata (Tasks/Devices/Mappings) and the Spring-free pipeline (§7). Assembles a
 * {@link DeviceConfig} (decrypting secrets), a {@link CollectRequest} and the mapping rules,
 * then runs the pipeline. Used by the Quartz job, the REST "run now" / preview, and the E2E.
 */
@Service
public class CollectionService {

    private final TaskService tasks;
    private final DeviceService devices;
    private final TagService tags;
    private final MappingRuleRepository mappingRepo;
    private final CollectionPipeline pipeline;
    private final CurrentValueCache cache;
    private final CollectionLogService logs;
    private final MeterRegistry meters;

    private final io.chronos.app.persistence.TagRepository tagRepo;

    public CollectionService(TaskService tasks, DeviceService devices, TagService tags,
            MappingRuleRepository mappingRepo, CollectionPipeline pipeline, CurrentValueCache cache,
            CollectionLogService logs, MeterRegistry meters,
            io.chronos.app.persistence.TagRepository tagRepo) {
        this.tasks = tasks;
        this.devices = devices;
        this.tags = tags;
        this.mappingRepo = mappingRepo;
        this.pipeline = pipeline;
        this.cache = cache;
        this.logs = logs;
        this.meters = meters;
        this.tagRepo = tagRepo;
    }

    /**
     * Run a pull adapter once from inline config (flow "device read" node) and return a payload:
     * ROWS → List of row maps, TEXT/JSON → the string, BYTES → a UTF-8 string. Reuses the pipeline's
     * bounded executor + timeout. `cfg` is the node config: adapterType, taskType, params, secrets,
     * definition, timeoutMs. Throws on unknown adapter / timeout / adapter error.
     */
    @SuppressWarnings("unchecked")
    public Object readAdapter(Map<String, Object> cfg) {
        String adapterType = String.valueOf(cfg.getOrDefault("adapterType", ""));
        var params = cfg.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        var secretsRaw = cfg.get("secrets") instanceof Map<?, ?> m ? (Map<?, ?>) m : Map.of();
        var definition = cfg.get("definition") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
        Map<String, String> secrets = new java.util.HashMap<>();
        secretsRaw.forEach((k, v) -> secrets.put(String.valueOf(k), String.valueOf(v)));
        io.chronos.api.adapter.TaskType type = io.chronos.api.adapter.TaskType.valueOf(
                String.valueOf(cfg.getOrDefault("taskType", "QUERY")));
        long timeoutMs = cfg.get("timeoutMs") instanceof Number n
                ? n.longValue()
                : Long.parseLong(String.valueOf(cfg.getOrDefault("timeoutMs", "10000")));

        // A device-read node may reference a globally-registered Device instead of carrying inline
        // credentials. Reusing the device means all such nodes share one connection pool (keyed by
        // url+user in the adapter), so DB sessions stay centralized and bounded — not one per node.
        String deviceId = String.valueOf(cfg.getOrDefault("deviceId", "")).trim();
        io.chronos.api.adapter.DeviceConfig dc = deviceId.isEmpty()
                ? new io.chronos.api.adapter.DeviceConfig(
                        adapterType, params, new io.chronos.api.adapter.Secrets(secrets))
                : devices.toDeviceConfig(devices.get(java.util.UUID.fromString(deviceId)));
        var req = new io.chronos.api.adapter.CollectRequest(
                "flow", "flow", type, definition, java.time.Duration.ofMillis(timeoutMs));
        var raw = pipeline.readOnce(dc, req);
        return switch (raw.kind()) {
            case ROWS -> raw.rows();
            case TEXT, JSON -> raw.text();
            case BYTES -> raw.bytes() == null ? null : new String(raw.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    /**
     * Record one value for an existing tag (by canonicalKey) through the same fan-out a scheduled
     * collection uses — current-value cache + node-local history + gateway broadcast + alarm evaluation.
     * Used by the flow runtime's historian-write ("tag") node. Returns false if no such tag exists.
     */
    public boolean ingestValue(String canonicalKey, Object value) {
        var tag = tagRepo.findByCanonicalKey(canonicalKey).orElse(null);
        if (tag == null) {
            return false;
        }
        TagValue v = TagValue.good(canonicalKey, value, Instant.now());
        pipeline.ingest(tag.getNodeId().toString(), List.of(v));
        return true;
    }

    public CollectionResult runTaskNow(UUID taskId) {
        TaskEntity task = tasks.get(taskId);
        DeviceEntity device = devices.get(task.getDeviceId());
        DeviceConfig config = devices.toDeviceConfig(device);

        List<MappingRule> mappings = mappingRepo.findByTaskId(taskId).stream()
                .map(this::toMappingRule)
                .toList();

        CollectRequest request = new CollectRequest(
                task.getId().toString(),
                task.getNodeId().toString(),
                TaskType.valueOf(task.getType()),
                task.getDefinition(),
                Duration.ofMillis(task.getTimeoutMs()));

        Instant startedAt = Instant.now();
        CollectionResult result = pipeline.run(config, request, mappings);

        // §10: persist the run to the collection log.
        Instant nextFireAt = "INTERVAL".equals(task.getScheduleKind()) && task.getIntervalMs() != null
                ? startedAt.plusMillis(task.getIntervalMs())
                : null;
        logs.record(taskId, startedAt, result, nextFireAt);

        // §11 metrics: count runs by status and record duration.
        meters.counter("chronos.collections", "status", result.status().name()).increment();
        meters.timer("chronos.collection.duration").record(result.durationMs(), TimeUnit.MILLISECONDS);

        return result;
    }

    private MappingRule toMappingRule(MappingRuleEntity m) {
        TagEntity tag = tags.get(m.getTagId());
        return new MappingRule(m.getTagId().toString(), tag.getCanonicalKey(), m.getExtractor(), m.getTransform());
    }

    public Collection<TagValue> currentValues() {
        return cache.all();
    }

    public Optional<TagValue> currentValue(String tagKey) {
        return cache.get(tagKey);
    }
}
