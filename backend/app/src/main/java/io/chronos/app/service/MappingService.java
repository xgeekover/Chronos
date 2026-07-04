package io.chronos.app.service;

import io.chronos.app.persistence.MappingRuleEntity;
import io.chronos.app.persistence.MappingRuleRepository;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TaskEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for mapping rules. Enforces that a Tag and its Task belong to the same Node (§D.2). */
@Service
public class MappingService {

    private final MappingRuleRepository repo;
    private final TaskService tasks;
    private final TagService tags;

    public MappingService(MappingRuleRepository repo, TaskService tasks, TagService tags) {
        this.repo = repo;
        this.tasks = tasks;
        this.tags = tags;
    }

    @Transactional
    public MappingRuleEntity create(UUID taskId, UUID tagId,
            Map<String, Object> extractor, Map<String, Object> transform) {
        TaskEntity task = tasks.get(taskId);
        TagEntity tag = tags.get(tagId);
        if (!task.getNodeId().equals(tag.getNodeId())) {
            throw new IllegalArgumentException("tag and task must belong to the same node");
        }
        MappingRuleEntity m = new MappingRuleEntity();
        m.setTaskId(taskId);
        m.setTagId(tagId);
        m.setExtractor(extractor == null ? Map.of() : extractor);
        m.setTransform(transform == null ? Map.of() : transform);
        return repo.save(m);
    }

    public List<MappingRuleEntity> listByTask(UUID taskId) {
        return repo.findByTaskId(taskId);
    }

    @Transactional
    public MappingRuleEntity update(UUID id, Map<String, Object> extractor, Map<String, Object> transform) {
        MappingRuleEntity m = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("mapping not found: " + id));
        if (extractor != null) {
            m.setExtractor(extractor);
        }
        if (transform != null) {
            m.setTransform(transform);
        }
        return repo.save(m);
    }

    @Transactional
    public void delete(UUID id) {
        repo.deleteById(id);
    }
}
