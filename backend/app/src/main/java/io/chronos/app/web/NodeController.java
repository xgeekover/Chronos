package io.chronos.app.web;

import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.TagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Node CRUD + nested Tag CRUD (§10 관리 UI). */
@RestController
@RequestMapping("/api/nodes")
public class NodeController {

    private final NodeService nodes;
    private final TagService tags;

    public NodeController(NodeService nodes, TagService tags) {
        this.nodes = nodes;
        this.tags = tags;
    }

    public record CreateNode(@NotBlank String name, String description, @Positive int retentionHours) {}

    public record CreateTag(@NotBlank String name, @NotBlank String dataType, String unit, String description) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NodeEntity create(@Valid @RequestBody CreateNode req) {
        return nodes.create(req.name(), req.description(), req.retentionHours());
    }

    @GetMapping
    public List<NodeEntity> list() {
        return nodes.list();
    }

    @GetMapping("/{id}")
    public NodeEntity get(@PathVariable UUID id) {
        return nodes.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        nodes.delete(id);
    }

    @PostMapping("/{nodeId}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagEntity createTag(@PathVariable UUID nodeId, @Valid @RequestBody CreateTag req) {
        return tags.create(nodeId, req.name(), req.dataType(), req.unit(), req.description());
    }

    @GetMapping("/{nodeId}/tags")
    public List<TagEntity> listTags(@PathVariable UUID nodeId) {
        return tags.listByNode(nodeId);
    }
}
