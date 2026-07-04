package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The exec node: benign spawn works (off-worker, 3 outputs); the shell-injection path is refused. */
class ExecNodeTest {

    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);

    private FlowNode node(Map<String, Object> c) {
        return FlowNodes.create(
                new FlowGraph.NodeDef("n", "exec", c),
                sched,
                r -> {},
                new AtomicLong(),
                (a, b) -> {},
                new HashMap<>(),
                new HashMap<>(),
                Map.of(),
                (k, v) -> false,
                cfg -> null);
    }

    @Test
    void echoProducesStdoutStderrAndExitCode() throws Exception {
        Map<Integer, Object> sent = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(1);
        FlowNode.Emit emit = new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg m) {
                sent.put(port, m.get("payload"));
                if (port == 2) {
                    done.countDown();
                }
            }
        };
        FlowNode n = node(Map.of("command", "echo", "args", "hello"));
        n.start(emit);
        n.onMessage(FlowMsg.of("x"), emit);
        assertTrue(done.await(5, TimeUnit.SECONDS), "exec should finish");
        assertEquals("hello\n", sent.get(0)); // stdout → port 0
        assertEquals(0L, sent.get(2)); // return code → port 2
    }

    @Test
    void shellModeWithEmptyCommandIsRefused() {
        // regression guard: msg.payload must never be run via sh -c (command injection from data plane)
        AtomicReference<String> err = new AtomicReference<>();
        FlowNode.Emit emit = new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg m) {}

            @Override
            public void error(FlowMsg m, String message) {
                err.set(message);
            }
        };
        FlowNode n = node(Map.of("useShell", "true", "command", ""));
        n.start(emit);
        n.onMessage(FlowMsg.of("id; echo pwned"), emit); // rejected synchronously, before any process runs
        assertNotNull(err.get());
        assertTrue(err.get().contains("shell mode requires"), err.get());
    }
}
