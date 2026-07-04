package io.chronos.app.service;

import io.chronos.app.persistence.CollectionLogEntity;
import io.chronos.app.persistence.CollectionLogRepository;
import io.chronos.engine.pipeline.CollectionResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists and queries per-Task collection results (§10 수집 로그). */
@Service
public class CollectionLogService {

    private final CollectionLogRepository repo;

    public CollectionLogService(CollectionLogRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void record(UUID taskId, Instant startedAt, CollectionResult result, Instant nextFireAt) {
        CollectionLogEntity e = new CollectionLogEntity();
        e.setTaskId(taskId);
        e.setStartedAt(startedAt);
        e.setDurationMs((int) result.durationMs());
        e.setStatus(result.status().name());
        e.setError(result.error());
        e.setTagCount(result.tagCount());
        e.setNextFireAt(nextFireAt);
        repo.save(e);
    }

    public List<CollectionLogEntity> recent(UUID taskId, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(limit, 500));
        return taskId == null
                ? repo.findByOrderByStartedAtDesc(page)
                : repo.findByTaskIdOrderByStartedAtDesc(taskId, page);
    }
}
