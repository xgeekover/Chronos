package io.chronos.adapter.mqtt;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.pf4j.Extension;

/**
 * Subscribes to MQTT topics and caches the latest payload per topic (§2 MQTT device). MQTT is
 * push-based, so the adapter holds a long-lived subscription per broker and {@code collect()}
 * snapshots the most recent retained message — the scheduled poll just reads the cache.
 *
 * <p>Device config params: {@code brokerUrl} (e.g. {@code tcp://host:1883}, required),
 * {@code qos} (optional, default 1), {@code clientId} (optional). Secrets: {@code username},
 * {@code password}. Task definition: {@code topic} (required, concrete — no wildcards).
 * The payload (JSON/text) is then extracted with JSONPATH/REGEX by the core parser.
 */
@Extension
public class MqttDeviceAdapter implements DeviceAdapter {

    /** One live client per broker+clientId; each caches the latest payload for its topics. */
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();

    private static final class Sub {
        final IMqttClient client;
        final Map<String, String> latest = new ConcurrentHashMap<>();
        final Set<String> subscribed = ConcurrentHashMap.newKeySet();

        Sub(IMqttClient client) {
            this.client = client;
        }
    }

    @Override
    public String type() {
        return "MQTT";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.MQTT_SUBSCRIBE);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        String url = str(config.params().get("brokerUrl"));
        if (url == null || url.isBlank()) {
            throw new AdapterException("MQTT device requires 'brokerUrl' (e.g. tcp://host:1883)");
        }
        try {
            IMqttClient probe = new MqttClient(
                    url, "chronos-probe-" + Long.toHexString(System.nanoTime()), new MemoryPersistence());
            probe.connect(options(config));
            probe.disconnect();
            probe.close();
        } catch (MqttException e) {
            throw new AdapterException("MQTT connect to " + url + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String url = str(config.params().get("brokerUrl"));
        if (url == null || url.isBlank()) {
            throw new AdapterException("MQTT device is missing 'brokerUrl'");
        }
        String topic = str(request.definition().get("topic"));
        if (topic == null || topic.isBlank()) {
            throw new AdapterException("MQTT task is missing 'topic'");
        }
        int qos = intOr(config.params().get("qos"), 1);
        Sub sub = ensureSubscribed(url, config, qos, topic);
        String payload = sub.latest.get(topic);
        if (payload == null) {
            throw new AdapterException("no MQTT message received yet on topic " + topic);
        }
        return RawResult.ofText(payload, Instant.now());
    }

    private Sub ensureSubscribed(String url, DeviceConfig config, int qos, String topic)
            throws AdapterException {
        String clientId = str(config.params().get("clientId"));
        String key = url + "|" + (clientId == null ? "chronos" : clientId);
        Sub sub;
        try {
            sub = subs.computeIfAbsent(key, k -> {
                try {
                    return connect(url, clientId, config);
                } catch (MqttException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AdapterException("MQTT connect to " + url + " failed: " + cause.getMessage(), cause);
        }
        if (sub.subscribed.add(topic)) {
            try {
                sub.client.subscribe(topic, qos, (t, msg) ->
                        sub.latest.put(t, new String(msg.getPayload(), StandardCharsets.UTF_8)));
            } catch (MqttException e) {
                sub.subscribed.remove(topic);
                throw new AdapterException("MQTT subscribe to " + topic + " failed: " + e.getMessage(), e);
            }
        }
        return sub;
    }

    private Sub connect(String url, String clientId, DeviceConfig config) throws MqttException {
        IMqttClient client = new MqttClient(
                url, clientId != null ? clientId : "chronos-" + Long.toHexString(System.nanoTime()),
                new MemoryPersistence());
        client.connect(options(config));
        return new Sub(client);
    }

    private static MqttConnectOptions options(DeviceConfig config) {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(5);
        opts.setAutomaticReconnect(true);
        String user = config.secrets().get("username");
        String pass = config.secrets().get("password");
        if (user != null && !user.isBlank()) {
            opts.setUserName(user);
        }
        if (pass != null && !pass.isBlank()) {
            opts.setPassword(pass.toCharArray());
        }
        return opts;
    }

    @Override
    public void close() {
        for (Sub sub : subs.values()) {
            try {
                if (sub.client.isConnected()) {
                    sub.client.disconnect();
                }
                sub.client.close();
            } catch (MqttException ignored) {
                // best-effort cleanup on plugin stop
            }
        }
        subs.clear();
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
