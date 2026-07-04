package io.chronos.app.web;

import io.chronos.api.history.Snapshot;
import io.chronos.api.history.TagSample;
import io.chronos.app.service.HistoryService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Time Machine query endpoints feeding the UI time slider + range chart (§8, Phase 3). */
@RestController
@RequestMapping("/api/nodes/{nodeId}")
public class HistoryController {

    private final HistoryService history;

    public HistoryController(HistoryService history) {
        this.history = history;
    }

    /** Node state at instant {@code at} (ISO-8601). {@code tags} optional (defaults to all node tags). */
    @GetMapping("/snapshot")
    public Snapshot snapshot(
            @PathVariable UUID nodeId,
            @RequestParam String at,
            @RequestParam(required = false) Set<String> tags) {
        return history.snapshotAt(nodeId, Instant.parse(at), tags == null ? Set.of() : tags);
    }

    /** Time-series samples for one tag in {@code [from, to]} (ISO-8601) for charting. */
    @GetMapping("/range")
    public List<TagSample> range(
            @PathVariable UUID nodeId,
            @RequestParam String tag,
            @RequestParam String from,
            @RequestParam String to) {
        return history.range(nodeId, tag, Instant.parse(from), Instant.parse(to));
    }
}
