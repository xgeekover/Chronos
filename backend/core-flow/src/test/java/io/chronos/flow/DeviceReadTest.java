package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The "device read" node — runs a pull adapter via the app callback and emits its result (off-worker). */
class DeviceReadTest {

    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);

    private FlowNode node(Function<Map<String, Object>, Object> read) {
        return FlowNodes.create(
                new FlowGraph.NodeDef("dr", "deviceread", Map.of("adapterType", "JDBC")),
                sched,
                r -> {},
                new AtomicLong(),
                (a, b) -> {},
                new HashMap<>(),
                new HashMap<>(),
                Map.of(),
                (k, v) -> false,
                read);
    }

    @Test
    void emitsTheAdapterResultAsPayload() throws Exception {
        FlowNode n = node(cfg -> List.of(Map.of("v", 7)));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> got = new AtomicReference<>();
        n.onMessage(FlowMsg.of("go"), (port, m) -> {
            got.set(m.get("payload"));
            done.countDown();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(Map.of("v", 7)), got.get());
    }

    @Test
    void adapterErrorRoutesToErrorChannel() throws Exception {
        FlowNode n = node(cfg -> {
            throw new IllegalStateException("boom");
        });
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> err = new AtomicReference<>();
        n.onMessage(FlowMsg.of("go"), new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg m) {}

            @Override
            public void error(FlowMsg m, String message) {
                err.set(message);
                done.countDown();
            }
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(err.get().contains("boom"), err.get());
    }
}
