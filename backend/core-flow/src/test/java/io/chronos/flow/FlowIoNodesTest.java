package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.chronos.flow.FlowGraph.NodeDef;
import io.chronos.flow.FlowGraph.WireDef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** http-in → http-response round trip. */
class FlowIoNodesTest {

    @Test
    void httpInResponseReturnsBodyAndStatusToTheCaller() throws Exception {
        var graph = new FlowGraph(
                List.of(
                        new NodeDef("hin", "httpin", Map.of("path", "echo")),
                        new NodeDef("ch", "change",
                                Map.of("rules", List.of(
                                        Map.of("t", "set", "p", "statusCode", "to", "201", "tot", "num"),
                                        Map.of("t", "set", "p", "payload", "to", "created", "tot", "str")))),
                        new NodeDef("resp", "httpresponse", Map.of())),
                List.of(new WireDef("hin", 0, "ch"), new WireDef("ch", 0, "resp")));
        var rt = new FlowRuntime();
        rt.deploy(graph);
        rt.start();
        try {
            Object out = rt.injectHttp("POST", "echo", "hi").get(3, TimeUnit.SECONDS);
            HttpReply reply = assertInstanceOf(HttpReply.class, out);
            assertEquals(201, reply.status());
            assertEquals("created", reply.body());
        } finally {
            rt.stop();
        }
    }
}
