package io.chronos.adapter.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiDeviceAdapterTest {

    private final ApiDeviceAdapter adapter = new ApiDeviceAdapter();
    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/sensor", exchange -> {
            byte[] body = "{\"temp\":21.5}".getBytes(StandardCharsets.UTF_8);
            assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void callsHttpEndpointWithBearerAndReturnsBody() throws Exception {
        DeviceConfig cfg = new DeviceConfig("API",
                Map.of("baseUrl", "http://localhost:" + port),
                new io.chronos.api.adapter.Secrets(Map.of("bearerToken", "secret-token")));
        CollectRequest req = new CollectRequest("t1", "n1", TaskType.API_CALL,
                Map.of("method", "GET", "url", "/sensor"), Duration.ofSeconds(5));

        RawResult raw = adapter.collect(cfg, req);
        assertEquals("{\"temp\":21.5}", raw.text());
    }
}
