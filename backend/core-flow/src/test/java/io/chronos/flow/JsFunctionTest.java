package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Node-RED-style JavaScript function node: transform / multi-output / context / sandbox. */
class JsFunctionTest {

    private record Sent(int port, Map<String, Object> msg) {}

    private static List<Sent> run(JsFunction js, Map<String, Object> msg, Map<String, Object> flow) {
        List<Sent> out = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        js.run(msg, flow, new HashMap<>(), Map.of("SITE", "plant-A"), new JsFunction.Sink() {
            @Override
            public void send(int port, Map<String, Object> m) {
                out.add(new Sent(port, m));
            }

            @Override
            public void error(String message, Map<String, Object> m) {}

            @Override
            public void status(String text) {}

            @Override
            public void log(String text) {
                logs.add(text);
            }
        });
        return out;
    }

    @Test
    void returnTransformsPayload() {
        JsFunction js = new JsFunction("msg.payload = msg.payload * 2; return msg;");
        List<Sent> out = run(js, new LinkedHashMap<>(Map.of("payload", 5L)), new HashMap<>());
        assertEquals(1, out.size());
        assertEquals(10L, out.get(0).msg().get("payload"));
    }

    @Test
    void nodeSendMultipleOutputs() {
        JsFunction js = new JsFunction(
                "node.send([{payload:'a'}, {payload:'b'}]); return null;");
        List<Sent> out = run(js, new LinkedHashMap<>(Map.of("payload", "x")), new HashMap<>());
        assertEquals(2, out.size());
        assertEquals(0, out.get(0).port());
        assertEquals("a", out.get(0).msg().get("payload"));
        assertEquals(1, out.get(1).port());
        assertEquals("b", out.get(1).msg().get("payload"));
    }

    @Test
    void flowContextGetSetAndEnv() {
        Map<String, Object> flow = new HashMap<>();
        JsFunction js = new JsFunction(
                "var n = (flow.get('count') || 0) + 1; flow.set('count', n);"
                        + " msg.payload = n; msg.site = env.get('SITE'); return msg;");
        run(js, new LinkedHashMap<>(), flow);
        List<Sent> out = run(js, new LinkedHashMap<>(), flow); // second call sees persisted state
        assertEquals(2L, out.get(0).msg().get("payload"));
        assertEquals("plant-A", out.get(0).msg().get("site"));
        assertEquals(2L, flow.get("count"));
    }

    @Test
    void nestedObjectsRoundTrip() {
        JsFunction js = new JsFunction(
                "msg.payload = { items: [1,2,3], meta: { ok: true } }; return msg;");
        List<Sent> out = run(js, new LinkedHashMap<>(), new HashMap<>());
        Object payload = out.get(0).msg().get("payload");
        assertTrue(payload instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) payload;
        assertEquals(List.of(1L, 2L, 3L), p.get("items"));
        assertEquals(true, ((Map<?, ?>) p.get("meta")).get("ok"));
    }

    @Test
    void compileErrorIsReported() {
        JsFunction js = new JsFunction("this is not valid javascript !!!");
        assertNotNull(js.compileError());
    }

    @Test
    void sandboxBlocksHostAccess() {
        // Java.type / accessing the JVM must not be possible under HostAccess.NONE
        JsFunction js = new JsFunction(
                "try { var F = Java.type('java.lang.System'); msg.payload = 'LEAK'; }"
                        + " catch (e) { msg.payload = 'blocked'; } return msg;");
        List<Sent> out = run(js, new LinkedHashMap<>(), new HashMap<>());
        assertEquals("blocked", out.get(0).msg().get("payload"));
    }
}
