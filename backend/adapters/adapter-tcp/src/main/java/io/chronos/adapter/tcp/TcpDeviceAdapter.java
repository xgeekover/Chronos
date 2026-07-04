package io.chronos.adapter.tcp;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.pf4j.Extension;

/**
 * Reads one line of text from a raw TCP socket (§2 TCP device). The line is then extracted with
 * REGEX/JSONPATH by the core parser.
 *
 * <p>Device config params: {@code host} (required), {@code port} (required),
 * {@code readTimeoutMs} (optional, default 3000). Task definition: optional
 * {@code sendOnConnect} string written (with a trailing newline) before reading.
 */
@Extension
public class TcpDeviceAdapter implements DeviceAdapter {

    @Override
    public String type() {
        return "TCP";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.TCP_READ);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        if (str(config.params().get("host")) == null) {
            throw new AdapterException("TCP device requires 'host'");
        }
        port(config); // throws if missing/out of range
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String host = str(config.params().get("host"));
        if (host == null) {
            throw new AdapterException("TCP device is missing 'host'");
        }
        int port = port(config);
        int readTimeout = intOr(config.params().get("readTimeoutMs"), 3000);
        int connectTimeout = (int) Math.min(request.timeout().toMillis(), 10_000);
        String send = str(request.definition().get("sendOnConnect"));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            socket.setSoTimeout(readTimeout);
            if (send != null && !send.isBlank()) {
                OutputStream out = socket.getOutputStream();
                out.write((send + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            var in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                throw new AdapterException("TCP peer closed without sending data");
            }
            return RawResult.ofText(line, Instant.now());
        } catch (AdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new AdapterException(
                    "TCP read from " + host + ":" + port + " failed: " + e.getMessage(), e);
        }
    }

    private static int port(DeviceConfig config) throws AdapterException {
        int p = intOr(config.params().get("port"), -1);
        if (p < 1 || p > 65535) {
            throw new AdapterException("TCP device requires a valid 'port' (1-65535)");
        }
        return p;
    }

    private static int intOr(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return o instanceof Number n ? n.intValue() : Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
