package io.chronos.app.service;

import io.chronos.api.history.HistoryStore;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.NodeRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodically trims each node's local history to its retention window (§8). Runs on a fixed
 * delay; the work is also exposed as {@link #enforceAllRetention()} for direct invocation/tests.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final NodeRepository nodes;
    private final HistoryStore history;

    public RetentionService(NodeRepository nodes, HistoryStore history) {
        this.nodes = nodes;
        this.history = history;
    }

    @Scheduled(
            fixedDelayString = "${chronos.retention.interval-ms:3600000}",
            initialDelayString = "${chronos.retention.initial-delay-ms:60000}")
    public void scheduledCleanup() {
        int n = enforceAllRetention();
        log.info("Retention cleanup ran for {} node(s)", n);
    }

    /** Enforce retention for every node; returns the number of nodes processed. */
    public int enforceAllRetention() {
        int count = 0;
        for (NodeEntity node : nodes.findAll()) {
            try {
                history.enforceRetention(node.getId().toString(), Duration.ofHours(node.getRetentionHours()));
                count++;
            } catch (RuntimeException e) {
                log.warn("Retention cleanup failed for node {}: {}", node.getId(), e.getMessage());
            }
        }
        return count;
    }
}
