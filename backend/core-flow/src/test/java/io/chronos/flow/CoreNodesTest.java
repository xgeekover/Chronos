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
import org.junit.jupiter.api.Test;

/** Batch-2 core nodes: json / sort / batch / xml / yaml / html. */
class CoreNodesTest {

    private record Sent(int port, FlowMsg msg) {}

    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);

    private FlowNode node(String type, Map<String, Object> config) {
        return FlowNodes.create(
                new FlowGraph.NodeDef("n", type, config),
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

    private List<Sent> run(String type, Map<String, Object> config, Object payload) {
        List<Sent> sent = new ArrayList<>();
        FlowNode.Emit emit = new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg msg) {
                sent.add(new Sent(port, msg));
            }
        };
        FlowNode n = node(type, config);
        n.start(emit);
        n.onMessage(FlowMsg.of(payload), emit);
        return sent;
    }

    @Test
    void jsonParseAndStringify() {
        Object parsed = run("json", Map.of("action", "obj"), "{\"a\":1}").get(0).msg().get("payload");
        assertTrue(parsed instanceof Map);
        assertEquals(1, ((Map<?, ?>) parsed).get("a"));
        Object str = run("json", Map.of("action", "str"), Map.of("a", 1)).get(0).msg().get("payload");
        assertEquals("{\"a\":1}", str);
    }

    @Test
    void sortNumericDescending() {
        Object out = run("sort", Map.of("order", "desc", "numeric", "true"), List.of(3, 1, 2))
                .get(0).msg().get("payload");
        assertEquals(List.of(3, 1, 2).stream().sorted((a, b) -> b - a).toList(), out);
    }

    @Test
    void batchByCountEmitsArray() {
        FlowNode n = node("batch", Map.of("mode", "count", "count", 3));
        List<Sent> sent = new ArrayList<>();
        FlowNode.Emit emit = new FlowNode.Emit() {
            @Override
            public void send(int port, FlowMsg msg) {
                sent.add(new Sent(port, msg));
            }
        };
        n.start(emit);
        n.onMessage(FlowMsg.of("a"), emit);
        n.onMessage(FlowMsg.of("b"), emit);
        assertEquals(0, sent.size()); // not yet
        n.onMessage(FlowMsg.of("c"), emit);
        assertEquals(1, sent.size());
        assertEquals(List.of("a", "b", "c"), sent.get(0).msg().get("payload"));
    }

    @Test
    void xmlParsesRepeatedElements() {
        Object out = run("xml", Map.of("action", "obj"), "<r><a>1</a><a>2</a></r>").get(0).msg().get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) ((Map<String, Object>) out).get("r");
        assertEquals(List.of("1", "2"), r.get("a"));
    }

    @Test
    void yamlLoadsMapping() {
        Object out = run("yaml", Map.of("action", "obj"), "a: 1\nb: two").get(0).msg().get("payload");
        assertEquals(1, ((Map<?, ?>) out).get("a"));
        assertEquals("two", ((Map<?, ?>) out).get("b"));
    }

    @Test
    void htmlSelectsElements() {
        Object out = run("html", Map.of("selector", "li"), "<ul><li>x</li><li>y</li></ul>")
                .get(0).msg().get("payload");
        assertEquals(List.of("x", "y"), out);
    }
}
