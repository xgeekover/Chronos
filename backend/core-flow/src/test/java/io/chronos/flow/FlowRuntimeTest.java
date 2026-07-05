package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.flow.FlowGraph.NodeDef;
import io.chronos.flow.FlowGraph.WireDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowRuntimeTest {

    private static List<DebugRecord> await(FlowRuntime rt, int minCount, long timeoutMs)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            List<DebugRecord> d = rt.debugSince(0);
            if (d.size() >= minCount) {
                return d;
            }
            Thread.sleep(20);
        }
        return rt.debugSince(0);
    }

    @Test
    void injectSwitchChangeReachDebug() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 5, "topic", "t")),
                        new NodeDef("sw", "switch",
                                Map.of("property", "payload",
                                        "rules", List.of(Map.of("op", "gt", "value", 3)))),
                        new NodeDef("ch", "change",
                                Map.of("property", "payload", "value", "HIGH", "valueType", "str")),
                        new NodeDef("dbg", "debug", Map.of("name", "d"))),
                List.of(new WireDef("i", 0, "sw"), new WireDef("sw", 0, "ch"),
                        new WireDef("ch", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty(), "expected a debug message to flow through");
            assertEquals("HIGH", d.get(0).payload());
            assertEquals("t", d.get(0).topic());
        } finally {
            rt.stop();
        }
    }

    @Test
    void switchRoutesToElseOutput() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 1)),
                        new NodeDef("sw", "switch",
                                Map.of("property", "payload",
                                        "rules", List.of(
                                                Map.of("op", "gt", "value", 3),
                                                Map.of("op", "else")))),
                        new NodeDef("hi", "debug", Map.of("name", "hi")),
                        new NodeDef("lo", "debug", Map.of("name", "lo"))),
                List.of(new WireDef("i", 0, "sw"), new WireDef("sw", 0, "hi"),
                        new WireDef("sw", 1, "lo")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            assertEquals("lo", d.get(0).name(), "1 is not > 3, so the else output should fire");
        } finally {
            rt.stop();
        }
    }

    @Test
    void rangeScalesNumericPayload() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 50)),
                        new NodeDef("r", "range",
                                Map.of("inMin", 0, "inMax", 100, "outMin", 0, "outMax", 10)),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "r"), new WireDef("r", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            assertEquals(5.0, (double) d.get(0).payload(), 1e-9); // 50 of 0..100 → 5 of 0..10
        } finally {
            rt.stop();
        }
    }

    @Test
    void functionTransformsPayload() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 5)),
                        new NodeDef("f", "function",
                                Map.of("code", "return msg.payload * 2;")),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "f"), new WireDef("f", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 3000);
            assertFalse(d.isEmpty());
            assertEquals(10, ((Number) d.get(0).payload()).intValue());
        } finally {
            rt.stop();
        }
    }

    @Test
    void splitThenJoinRebuildsArray() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", List.of(1, 2, 3))),
                        new NodeDef("sp", "split", Map.of()),
                        new NodeDef("jn", "join", Map.of("count", 3)),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "sp"), new WireDef("sp", 0, "jn"),
                        new WireDef("jn", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 3000);
            assertFalse(d.isEmpty());
            assertTrue(d.get(0).payload() instanceof List, "join should emit an array");
            assertEquals(3, ((List<?>) d.get(0).payload()).size());
        } finally {
            rt.stop();
        }
    }

    @Test
    void injectNowFiresManuallyAndCounts() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("once", false, "payload", "manual")),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            Thread.sleep(150); // manual-only inject should NOT auto-fire
            assertTrue(rt.debugSince(0).isEmpty(), "manual inject must not auto-fire");
            assertTrue(rt.injectNow("i"), "injectNow should succeed");
            List<DebugRecord> d = await(rt, 1, 2000);
            assertEquals("manual", d.get(0).payload());
            assertEquals(1L, rt.counts().getOrDefault("dbg", 0L)); // debug received exactly one
        } finally {
            rt.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void functionUsesFlowContext() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("once", false, "payload", 1)),
                        new NodeDef("f", "function", Map.of("code",
                                "var n = (flow.get(\"count\") || 0) + 1;"
                                + " flow.set(\"count\", n); return n;")),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "f"), new WireDef("f", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            rt.injectNow("i");
            rt.injectNow("i");
            rt.injectNow("i");
            await(rt, 3, 2000);
            assertEquals(3, ((Number) rt.debugSince(0).get(0).payload()).intValue());
            Map<String, Object> flow = (Map<String, Object>) rt.context().get("flow");
            assertEquals(3, ((Number) flow.get("count")).intValue());
        } finally {
            rt.stop();
        }
    }

    @Test
    void rbeBlocksConsecutiveEqualPayloads() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("once", false, "payload", "x")),
                        new NodeDef("f", "rbe", Map.of()),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "f"), new WireDef("f", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            rt.injectNow("i"); // "x" → passes (first)
            rt.injectNow("i"); // "x" again → blocked
            rt.injectNow("i"); // "x" again → blocked
            await(rt, 1, 1500);
            Thread.sleep(150); // give any erroneous extras a chance to arrive
            assertEquals(1, rt.debugSince(0).size(), "rbe must block consecutive equal payloads");
        } finally {
            rt.stop();
        }
    }

    @Test
    void csvSplitsDelimitedPayloadIntoArray() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", "a,b,c")),
                        new NodeDef("c", "csv", Map.of("delimiter", ",")),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "c"), new WireDef("c", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            assertTrue(d.get(0).payload() instanceof List, "csv should emit an array");
            assertEquals(List.of("a", "b", "c"), d.get(0).payload());
        } finally {
            rt.stop();
        }
    }

    @Test
    void triggerEmitsInputThenDelayedThen() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("once", true, "payload", "go")),
                        new NodeDef("t", "trigger", Map.of("delayMs", 100, "thenPayload", "reset")),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "t"), new WireDef("t", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 2, 2000);
            assertEquals(2, d.size(), "trigger emits the input then a delayed 'then'");
            assertEquals("reset", d.get(0).payload()); // newest first
            assertEquals("go", d.get(1).payload());
        } finally {
            rt.stop();
        }
    }

    @Test
    void tcpInEmitsReceivedLines() throws Exception {
        try (var server = new java.net.ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread t = new Thread(() -> {
                try (var s = server.accept()) {
                    s.getOutputStream().write("alpha\nbeta\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    s.getOutputStream().flush();
                    Thread.sleep(300); // keep the socket open so the reader sees both lines
                } catch (Exception ignored) {
                    // best-effort test server
                }
            });
            t.setDaemon(true);
            t.start();

            var graph = new FlowGraph(
                    List.of(
                            new NodeDef("in", "tcpin", Map.of("host", "127.0.0.1", "port", port)),
                            new NodeDef("dbg", "debug", Map.of())),
                    List.of(new WireDef("in", 0, "dbg")));
            var rt = new FlowRuntime();
            rt.deploy(graph);
            rt.start();
            try {
                List<DebugRecord> d = await(rt, 2, 2000);
                assertEquals(2, d.size());
                assertEquals("beta", d.get(0).payload()); // newest first
                assertEquals("alpha", d.get(1).payload());
            } finally {
                rt.stop();
            }
        }
    }

    @Test
    void linkOutRoutesToLinkInAcrossVirtualWire() throws Exception {
        // inject → linkout (no wire to linkin); linkout.links → linkin → debug
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", "via-link")),
                        new NodeDef("lo", "linkout", Map.of("links", List.of("li"))),
                        new NodeDef("li", "linkin", Map.of()),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "lo"), new WireDef("li", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty(), "message should cross the virtual link to link-in");
            assertEquals("via-link", d.get(0).payload());
        } finally {
            rt.stop();
        }
    }

    @Test
    void linkCallInvokesSubroutineAndReturns() throws Exception {
        // inject → link-call(→sub) → debug ; sub: link-in → change(×→"done") → link-out(return)
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", "req")),
                        new NodeDef("call", "linkcall", Map.of("links", List.of("in"))),
                        new NodeDef("dbg", "debug", Map.of()),
                        new NodeDef("in", "linkin", Map.of()),
                        new NodeDef("ch", "change", Map.of("rules", List.of(
                                Map.of("t", "set", "p", "payload", "to", "done", "tot", "str")))),
                        new NodeDef("out", "linkout", Map.of("mode", "return"))),
                List.of(
                        new WireDef("i", 0, "call"),
                        new WireDef("call", 0, "dbg"),
                        new WireDef("in", 0, "ch"),
                        new WireDef("ch", 0, "out")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 3000);
            assertFalse(d.isEmpty(), "the returned message should reach the debug after the link-call");
            assertEquals("done", d.get(0).payload());
        } finally {
            rt.stop();
        }
    }

    @Test
    void changeSetWithExpressionValue() throws Exception {
        // C→F conversion via an expression: payload * 1.8 + 32
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 100)),
                        new NodeDef("ch", "change", Map.of("rules", List.of(
                                Map.of("t", "set", "p", "payload", "to", "payload * 1.8 + 32",
                                        "tot", "expr")))),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "ch"), new WireDef("ch", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            assertEquals(212.0, (double) d.get(0).payload(), 1e-9);
        } finally {
            rt.stop();
        }
    }

    @Test
    void switchExpressionOperatorRoutes() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 50)),
                        new NodeDef("sw", "switch", Map.of("property", "payload", "rules", List.of(
                                Map.of("op", "expr", "value", "payload > 10 && payload < 100"),
                                Map.of("op", "else")))),
                        new NodeDef("hi", "debug", Map.of("name", "hi")),
                        new NodeDef("lo", "debug", Map.of("name", "lo"))),
                List.of(new WireDef("i", 0, "sw"), new WireDef("sw", 0, "hi"),
                        new WireDef("sw", 1, "lo")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            assertEquals("hi", d.get(0).name(), "50 is between 10 and 100 → expr routes to port 0");
        } finally {
            rt.stop();
        }
    }

    @Test
    void templateEvaluatesExpressions() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 21)),
                        new NodeDef("t", "template",
                                Map.of("property", "payload", "template", "temp={{payload * 2}}")),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "t"), new WireDef("t", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            assertEquals("temp=42.0", d.get(0).payload());
        } finally {
            rt.stop();
        }
    }

    @Test
    void functionMultiOutputRoutesArrayElementsToPorts() throws Exception {
        // node.send([null, {payload}]) → nothing on port 0, msg on port 1
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 5)),
                        new NodeDef("f", "function", Map.of("code",
                                "node.send([null, { payload: msg.payload * 10 }]);")),
                        new NodeDef("p0", "debug", Map.of("name", "p0")),
                        new NodeDef("p1", "debug", Map.of("name", "p1"))),
                List.of(new WireDef("i", 0, "f"), new WireDef("f", 0, "p0"),
                        new WireDef("f", 1, "p1")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 3000);
            Thread.sleep(150);
            assertEquals(1, d.size(), "only port 1 should emit (port 0 element was null)");
            assertEquals("p1", d.get(0).name());
            assertEquals(50, ((Number) d.get(0).payload()).intValue());
        } finally {
            rt.stop();
        }
    }

    @Test
    void changeRulesDeleteAndMoveAndSet() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 1, "topic", "t")),
                        new NodeDef("ch", "change", Map.of("rules", List.of(
                                Map.of("t", "set", "p", "payload", "to", "hi", "tot", "str"),
                                Map.of("t", "move", "p", "topic", "to", "channel")))),
                        new NodeDef("dbg", "debug", Map.of("property", "$msg"))),
                List.of(new WireDef("i", 0, "ch"), new WireDef("ch", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            assertFalse(d.isEmpty());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) d.get(0).payload();
            assertEquals("hi", m.get("payload")); // set
            assertEquals("t", m.get("channel")); // moved topic → channel
            assertFalse(m.containsKey("topic")); // original removed by move
        } finally {
            rt.stop();
        }
    }

    @Test
    void switchRegexAndEmptyOperators() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", "hello-123")),
                        new NodeDef("sw", "switch", Map.of("property", "payload", "rules", List.of(
                                Map.of("op", "regex", "value", "\\d+"),
                                Map.of("op", "empty")))),
                        new NodeDef("hit", "debug", Map.of("name", "hit")),
                        new NodeDef("mt", "debug", Map.of("name", "mt"))),
                List.of(new WireDef("i", 0, "sw"), new WireDef("sw", 0, "hit"),
                        new WireDef("sw", 1, "mt")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 2000);
            Thread.sleep(150);
            assertEquals(1, d.size(), "regex matches, empty does not");
            assertEquals("hit", d.get(0).name());
        } finally {
            rt.stop();
        }
    }

    @Test
    void catchNodeReceivesErrorsFromAWatchedNode() throws Exception {
        // a function that throws → the catch node (scope = all) should receive the error msg
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 1)),
                        new NodeDef("f", "function",
                                Map.of("code", "throw new Error(\"boom\");")),
                        new NodeDef("c", "catch", Map.of()), // empty scope = catch all
                        new NodeDef("dbg", "debug", Map.of("name", "err", "property", "$msg"))),
                List.of(new WireDef("i", 0, "f"), new WireDef("c", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 3000);
            assertFalse(d.isEmpty(), "catch should have forwarded the error message");
            assertTrue(d.get(0).payload() instanceof Map, "debug of $msg emits the whole message");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) d.get(0).payload();
            assertTrue(m.containsKey("error"), "the caught message carries msg.error");
        } finally {
            rt.stop();
        }
    }

    @Test
    void completeNodeFiresWhenAWatchedNodeFinishes() throws Exception {
        // complete scoped to the change node fires after it processes a message
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", 1)),
                        new NodeDef("ch", "change",
                                Map.of("property", "payload", "value", "done", "valueType", "str")),
                        new NodeDef("cp", "complete", Map.of("scope", List.of("ch"))),
                        new NodeDef("dbg", "debug", Map.of())),
                List.of(new WireDef("i", 0, "ch"), new WireDef("cp", 0, "dbg")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 1, 3000);
            // complete fires with the message the watched node received (msgs are immutable, so it's
            // the pre-transform payload). The point is that it fired at all.
            assertFalse(d.isEmpty(), "complete should fire after the change node finishes");
            assertEquals(1, d.get(0).payload());
        } finally {
            rt.stop();
        }
    }

    @Test
    void tcpInReconnectsAfterTheConnectionDrops() throws Exception {
        try (var server = new java.net.ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread t = new Thread(() -> {
                try {
                    // first connection: send a line then drop
                    try (var s1 = server.accept()) {
                        s1.getOutputStream().write(
                                "first\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        s1.getOutputStream().flush();
                    }
                    // second connection (after the node reconnects): send another line
                    try (var s2 = server.accept()) {
                        s2.getOutputStream().write(
                                "second\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        s2.getOutputStream().flush();
                        Thread.sleep(300);
                    }
                } catch (Exception ignored) {
                    // best-effort test server
                }
            });
            t.setDaemon(true);
            t.start();

            var graph = new FlowGraph(
                    List.of(
                            new NodeDef("in", "tcpin", Map.of("host", "127.0.0.1", "port", port)),
                            new NodeDef("dbg", "debug", Map.of())),
                    List.of(new WireDef("in", 0, "dbg")));
            var rt = new FlowRuntime();
            rt.deploy(graph);
            rt.start();
            try {
                // both lines arrive only if the node reconnected after the first drop (~500ms backoff)
                List<DebugRecord> d = await(rt, 2, 5000);
                assertEquals(2, d.size(), "expected a line from before AND after the reconnect");
                assertEquals("second", d.get(0).payload()); // newest first
                assertEquals("first", d.get(1).payload());
            } finally {
                rt.stop();
            }
        }
    }

    @Test
    void httpRequestFetchesResponseBody() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", ex -> {
            byte[] b = "pong".getBytes();
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            var graph = new FlowGraph(
                    List.of(
                            new NodeDef("i", "inject", Map.of("payload", "go")),
                            new NodeDef("h", "httprequest",
                                    Map.of("method", "GET", "url", "http://127.0.0.1:" + port + "/ping")),
                            new NodeDef("dbg", "debug", Map.of())),
                    List.of(new WireDef("i", 0, "h"), new WireDef("h", 0, "dbg")));
            var rt = new FlowRuntime();
            rt.deploy(graph);
            rt.start();
            try {
                List<DebugRecord> d = await(rt, 1, 3000);
                assertFalse(d.isEmpty());
                assertEquals("pong", d.get(0).payload());
            } finally {
                rt.stop();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void statusNodeReceivesEventsAndJunctionForwards() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("i", "inject", Map.of("payload", "go")),
                        new NodeDef("fn", "function",
                                Map.of("lang", "js", "code", "node.status('online'); return msg;")),
                        new NodeDef("j", "junction", Map.of()),
                        new NodeDef("d1", "debug", Map.of("name", "d1")),
                        new NodeDef("st", "status", Map.of()),
                        new NodeDef("d2", "debug", Map.of("name", "d2"))),
                List.of(
                        new WireDef("i", 0, "fn"),
                        new WireDef("fn", 0, "j"),
                        new WireDef("j", 0, "d1"),
                        new WireDef("st", 0, "d2")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            List<DebugRecord> d = await(rt, 2, 3000);
            List<Object> payloads = d.stream().map(DebugRecord::payload).toList();
            assertTrue(payloads.contains("go"), "junction forwarded the message to d1");
            assertTrue(payloads.contains("online"), "status node received the status event");
        } finally {
            rt.stop();
        }
    }
}
