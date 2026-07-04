package io.chronos.app.service;

import io.chronos.app.persistence.MappingRuleRepository;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagRepository;
import io.chronos.app.persistence.TagEntity;
import io.chronos.engine.cache.CurrentValueCache;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD for Tags. Derives the canonical key {@code <node>.<tag>} (ADR-012). */
@Service
public class TagService {

    private final TagRepository repo;
    private final NodeService nodes;
    private final MappingRuleRepository mappings;
    private final CurrentValueCache cache;

    public TagService(TagRepository repo, NodeService nodes,
            MappingRuleRepository mappings, CurrentValueCache cache) {
        this.repo = repo;
        this.nodes = nodes;
        this.mappings = mappings;
        this.cache = cache;
    }

    @Transactional
    public TagEntity create(UUID nodeId, String name, String dataType, String unit, String description) {
        if (name.contains(".")) {
            throw new IllegalArgumentException("tag name must not contain '.' (reserved for the key separator)");
        }
        NodeEntity node = nodes.get(nodeId);
        if (repo.existsByNodeIdAndName(nodeId, name)) {
            throw new IllegalArgumentException("tag name already exists in node: " + name);
        }
        TagEntity t = new TagEntity();
        t.setNodeId(nodeId);
        t.setName(name);
        t.setDataType(dataType);
        t.setUnit(unit);
        t.setDescription(description);
        t.setCanonicalKey(node.getName() + "." + name);
        return repo.save(t);
    }

    public List<TagEntity> listByNode(UUID nodeId) {
        return repo.findByNodeId(nodeId);
    }

    public TagEntity get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("tag not found: " + id));
    }

    @Transactional
    public TagEntity update(UUID id, String name, String dataType, String unit, String description) {
        TagEntity t = get(id);
        if (name != null && !name.equals(t.getName())) {
            if (name.contains(".")) {
                throw new IllegalArgumentException("tag name must not contain '.' (reserved for the key separator)");
            }
            if (repo.existsByNodeIdAndName(t.getNodeId(), name)) {
                throw new IllegalArgumentException("tag name already exists in node: " + name);
            }
            NodeEntity node = nodes.get(t.getNodeId());
            cache.evict(t.getCanonicalKey()); // drop the value under the old key so it isn't a phantom
            t.setName(name);
            t.setCanonicalKey(node.getName() + "." + name);
        }
        if (dataType != null) {
            t.setDataType(dataType);
        }
        if (unit != null) {
            t.setUnit(unit);
        }
        if (description != null) {
            t.setDescription(description);
        }
        return repo.save(t);
    }

    @Transactional
    public void delete(UUID id) {
        TagEntity t = get(id);
        // cascade: remove mappings that target this tag, to avoid orphans
        mappings.deleteAll(mappings.findByTagId(id));
        cache.evict(t.getCanonicalKey()); // drop any stale current value for this tag
        repo.deleteById(id);
    }
}
