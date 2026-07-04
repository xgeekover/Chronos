package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/** The historian-write ("tag") node — bridges a flow message into the historian tag store. */
class HistorianWriteTest {

    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);

    private FlowNode tagNode(Map<String, Object> config, BiFunction<String, Object, Boolean> write) {
        return FlowNodes.create(
                new FlowGraph.NodeDef("t", "tag", config),
                sched,
                r -> {},
                new AtomicLong(),
                (a, b) -> {},
                new HashMap<>(),
                new HashMap<>(),
                Map.of(),
                write,
                cfg -> null);
    }

    @Test
    void writesPayloadToTheConfiguredTagAndPassesThrough() {
        Map<String, Object> captured = new HashMap<>();
        FlowNode n = tagNode(Map.of("tag", "line1.temp"), (k, v) -> {
            captured.put(k, v);
            return true;
        });
        List<FlowMsg> sent = new ArrayList<>();
        n.onMessage(FlowMsg.of(42L), (port, m) -> sent.add(m));
        assertEquals(42L, captured.get("line1.temp"));
        assertEquals(1, sent.size(), "passes the message through on success");
    }

    @Test
    void unknownTagRaisesAnError() {
        AtomicReference<String> err = new AtomicReference<>();
        FlowNode n = tagNode(Map.of("tag", "nope.tag"), (k, v) -> false);
        n.onMessage(FlowMsg.of(1L), new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg m) {}

            @Override
            public void error(FlowMsg m, String message) {
                err.set(message);
            }
        });
        assertTrue(err.get().contains("no historian tag"), err.get());
    }
}
