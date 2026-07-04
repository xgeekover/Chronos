package io.chronos.adapter.modbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawKind;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModbusDeviceAdapterTest {

    private final ModbusDeviceAdapter adapter = new ModbusDeviceAdapter();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static int asInt(Object o) {
        return ((Number) o).intValue();
    }

    @Test
    void readsHoldingRegistersFromInProcessSlave() throws Exception {
        int unitId = 1;
        int port = freePort();
        ModbusSlave slave = ModbusSlaveFactory.createTCPSlave(port, 5);
        SimpleProcessImage image = new SimpleProcessImage(unitId);
        image.addRegister(new SimpleRegister(111));
        image.addRegister(new SimpleRegister(222));
        image.addRegister(new SimpleRegister(333));
        slave.addProcessImage(unitId, image);
        slave.open();
        try {
            var config = new DeviceConfig(
                    "MODBUS", Map.of("host", "127.0.0.1", "port", port, "unitId", unitId), null);
            var req = new CollectRequest("t", "n", TaskType.MODBUS_READ,
                    Map.of("registerType", "HOLDING", "address", 0, "count", 3),
                    Duration.ofSeconds(5));
            RawResult r = adapter.collect(config, req);
            assertEquals(RawKind.ROWS, r.kind());
            assertEquals(3, r.rows().size());
            assertEquals(111, asInt(r.rows().get(0).get("value")));
            assertEquals(333, asInt(r.rows().get(2).get("value")));
        } finally {
            ModbusSlaveFactory.close();
        }
    }

    @Test
    void missingHostFailsValidation() {
        var config = new DeviceConfig("MODBUS", Map.of(), null);
        assertThrows(AdapterException.class, () -> adapter.validate(config));
    }
}
