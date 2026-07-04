package io.chronos.app.service;

import io.chronos.api.history.HistoryStore;
import io.chronos.api.history.Snapshot;
import io.chronos.api.history.TagSample;
import io.chronos.app.persistence.TagEntity;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Time Machine read API over the per-node {@link HistoryStore} (§8): point-in-time snapshots
 * and time-range series for charting.
 */
@Service
public class HistoryService {

    private final HistoryStore history;
    private final NodeService nodes;
    private final TagService tags;

    public HistoryService(HistoryStore history, NodeService nodes, TagService tags) {
        this.history = history;
        this.nodes = nodes;
        this.tags = tags;
    }

    /** State of a node at instant {@code at}. If {@code tagKeys} is empty, all node tags are used. */
    public Snapshot snapshotAt(UUID nodeId, Instant at, Set<String> tagKeys) {
        nodes.get(nodeId); // validate existence
        Set<String> keys = (tagKeys == null || tagKeys.isEmpty())
                ? tags.listByNode(nodeId).stream().map(TagEntity::getCanonicalKey).collect(Collectors.toSet())
                : tagKeys;
        return history.snapshotAt(nodeId.toString(), at, keys);
    }

    /** Time-series samples for one tag within {@code [from, to]} (for the chart). */
    public List<TagSample> range(UUID nodeId, String tagKey, Instant from, Instant to) {
        nodes.get(nodeId);
        return history.range(nodeId.toString(), tagKey, from, to);
    }
}
