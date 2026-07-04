package io.chronos.adapter.tcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TcpDeviceAdapterTest {

    private final TcpDeviceAdapter adapter = new TcpDeviceAdapter();

    private static CollectRequest req(Map<String, Object> def) {
        return new CollectRequest("t", "n", TaskType.TCP_READ, def, Duration.ofSeconds(5));
    }

    @Test
    void readsOneLineFromSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    OutputStream out = s.getOutputStream();
                    out.write("temp=21.5C\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // test server best-effort
                }
            });
            t.setDaemon(true);
            t.start();

            var config = new DeviceConfig("TCP", Map.of("host", "127.0.0.1", "port", port), null);
            RawResult r = adapter.collect(config, req(Map.of()));
            assertEquals("temp=21.5C", r.text());
        }
    }

    @Test
    void echoesSendOnConnectThenReads() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    String line = new java.io.BufferedReader(
                            new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                            .readLine();
                    s.getOutputStream().write(("got:" + line + "\n").getBytes(StandardCharsets.UTF_8));
                    s.getOutputStream().flush();
                } catch (Exception ignored) {
                    // best-effort
                }
            });
            t.setDaemon(true);
            t.start();

            var config = new DeviceConfig("TCP", Map.of("host", "127.0.0.1", "port", port), null);
            RawResult r = adapter.collect(config, req(Map.of("sendOnConnect", "PING")));
            assertEquals("got:PING", r.text());
        }
    }

    @Test
    void missingHostFailsValidation() {
        var config = new DeviceConfig("TCP", Map.of("port", 5000), null);
        AdapterException e = assertThrows(AdapterException.class, () -> adapter.validate(config));
        assertTrue(e.getMessage().contains("host"));
    }

    @Test
    void invalidPortFailsValidation() {
        var config = new DeviceConfig("TCP", Map.of("host", "127.0.0.1"), null);
        assertThrows(AdapterException.class, () -> adapter.validate(config));
    }
}
