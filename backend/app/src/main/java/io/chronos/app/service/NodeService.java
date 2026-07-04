package io.chronos.app.service;

import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.NodeRepository;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TagRepository;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.persistence.TaskRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for Nodes (logical collection units owning retention + tags/tasks). */
@Service
public class NodeService {

    private final NodeRepository repo;
    private final TaskRepository taskRepo;
    private final TagRepository tagRepo;
    private final TaskService taskService;
    private final TagService tagService;

    // @Lazy breaks the construction cycle (TaskService/TagService depend on NodeService).
    public NodeService(NodeRepository repo, TaskRepository taskRepo, TagRepository tagRepo,
            @Lazy TaskService taskService, @Lazy TagService tagService) {
        this.repo = repo;
        this.taskRepo = taskRepo;
        this.tagRepo = tagRepo;
        this.taskService = taskService;
        this.tagService = tagService;
    }

    @Transactional
    public NodeEntity create(String name, String description, int retentionHours) {
        if (retentionHours <= 0) {
            throw new IllegalArgumentException("retentionHours must be > 0");
        }
        if (repo.existsByName(name)) {
            throw new IllegalArgumentException("node name already exists: " + name);
        }
        NodeEntity n = new NodeEntity();
        n.setName(name);
        n.setDescription(description);
        n.setRetentionHours(retentionHours);
        return repo.save(n);
    }

    public List<NodeEntity> list() {
        return repo.findAll();
    }

    public NodeEntity get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("node not found: " + id));
    }

    @Transactional
    public NodeEntity update(UUID id, String name, String description, Integer retentionHours) {
        NodeEntity n = get(id);
        if (name != null && !name.equals(n.getName())) {
            if (repo.existsByName(name)) {
                throw new IllegalArgumentException("node name already exists: " + name);
            }
            n.setName(name);
        }
        if (description != null) {
            n.setDescription(description);
        }
        if (retentionHours != null) {
            if (retentionHours <= 0) {
                throw new IllegalArgumentException("retentionHours must be > 0");
            }
            n.setRetentionHours(retentionHours);
        }
        return repo.save(n);
    }

    @Transactional
    public void delete(UUID id) {
        // cascade through the service layer so each child's own cleanup runs
        // (tasks → mappings + unschedule; tags → mappings)
        for (TaskEntity t : taskRepo.findByNodeId(id)) {
            taskService.delete(t.getId());
        }
        for (TagEntity tag : tagRepo.findByNodeId(id)) {
            tagService.delete(tag.getId());
        }
        repo.deleteById(id);
    }
}
