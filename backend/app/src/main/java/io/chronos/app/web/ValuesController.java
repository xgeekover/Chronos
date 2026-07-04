package io.chronos.app.web;

import io.chronos.api.parse.TagValue;
import io.chronos.app.service.CollectionService;
import java.util.Collection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Current (latest) tag values from the in-memory cache — feeds the real-time dashboard (§10). */
@RestController
@RequestMapping("/api/values")
public class ValuesController {

    private final CollectionService collection;

    public ValuesController(CollectionService collection) {
        this.collection = collection;
    }

    @GetMapping
    public Collection<TagValue> all() {
        return collection.currentValues();
    }

    @GetMapping("/{tagKey}")
    public TagValue byKey(@PathVariable String tagKey) {
        return collection.currentValue(tagKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no value for tag " + tagKey));
    }
}
