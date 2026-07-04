package io.chronos.adapter.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.TaskType;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the non-network paths (config validation, capabilities). Live pub/sub
 * requires an MQTT broker and is exercised via integration/manual testing.
 */
class MqttDeviceAdapterTest {

    private final MqttDeviceAdapter adapter = new MqttDeviceAdapter();

    @Test
    void typeAndCapabilities() {
        assertEquals("MQTT", adapter.type());
        assertTrue(adapter.capabilities().supportedTaskTypes().contains(TaskType.MQTT_SUBSCRIBE));
    }

    @Test
    void missingBrokerUrlFailsValidation() {
        var config = new DeviceConfig("MQTT", Map.of(), null);
        AdapterException e = assertThrows(AdapterException.class, () -> adapter.validate(config));
        assertTrue(e.getMessage().contains("brokerUrl"));
    }

    @Test
    void missingTopicFailsCollect() {
        var config = new DeviceConfig("MQTT", Map.of("brokerUrl", "tcp://localhost:1883"), null);
        var req = new CollectRequest("t", "n", TaskType.MQTT_SUBSCRIBE, Map.of(), Duration.ofSeconds(5));
        AdapterException e = assertThrows(AdapterException.class, () -> adapter.collect(config, req));
        assertTrue(e.getMessage().contains("topic"));
    }
}
