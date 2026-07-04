package io.chronos.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.chronos.app.persistence.FlowContextEntity;
import io.chronos.app.persistence.FlowContextRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holds the flow runtime's GLOBAL context — one map shared by every {@link FlowRuntime} and persisted
 * to the {@code flow_context} table so {@code global.put(...)} survives restarts (Node-RED persistent
 * context). Loaded on startup, flushed periodically (only when changed) and on shutdown.
 */
@Component
public class FlowContextStore {

    private static final String SCOPE = "global";

    private final FlowContextRepository repo;
    private final Map<String, Object> global = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private volatile String lastFlushed = "{}";

    public FlowContextStore(FlowContextRepository repo) {
        this.repo = repo;
    }

    /** The shared global-context map handed to every flow runtime. */
    public Map<String, Object> global() {
        return global;
    }

    @PostConstruct
    void load() {
        repo.findById(SCOPE).ifPresent(e -> global.putAll(e.getData()));
        lastFlushed = snapshot();
    }

    /** Persist the global context if it changed since the last flush. */
    @Scheduled(fixedDelay = 15_000)
    public void flush() {
        String snap = snapshot();
        if (snap.equals(lastFlushed)) {
            return;
        }
        repo.save(new FlowContextEntity(SCOPE, new LinkedHashMap<>(global)));
        lastFlushed = snap;
    }

    @PreDestroy
    void onShutdown() {
        flush();
    }

    private String snapshot() {
        try {
            return json.writeValueAsString(new TreeMap<>(global)); // stable ordering for change detection
        } catch (Exception e) {
            return "";
        }
    }
}
