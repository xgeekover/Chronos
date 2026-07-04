package io.chronos.app.web;

import io.chronos.app.persistence.MappingRuleEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.service.MappingService;
import io.chronos.app.service.TagService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Update + delete endpoints for resources addressed by their own id (tags, mapping rules). */
@RestController
@RequestMapping("/api")
public class ResourceDeleteController {

    private final TagService tags;
    private final MappingService mappings;

    public ResourceDeleteController(TagService tags, MappingService mappings) {
        this.tags = tags;
        this.mappings = mappings;
    }

    public record UpdateTag(String name, String dataType, String unit, String description) {}

    public record UpdateMapping(Map<String, Object> extractor, Map<String, Object> transform) {}

    @PutMapping("/tags/{id}")
    public TagEntity updateTag(@PathVariable UUID id, @RequestBody UpdateTag req) {
        return tags.update(id, req.name(), req.dataType(), req.unit(), req.description());
    }

    @DeleteMapping("/tags/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTag(@PathVariable UUID id) {
        tags.delete(id);
    }

    @PutMapping("/mappings/{id}")
    public MappingRuleEntity updateMapping(@PathVariable UUID id, @RequestBody UpdateMapping req) {
        return mappings.update(id, req.extractor(), req.transform());
    }

    @DeleteMapping("/mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMapping(@PathVariable UUID id) {
        mappings.delete(id);
    }
}
