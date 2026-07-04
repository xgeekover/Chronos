package io.chronos.adapter.modbus;

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.util.BitVector;
import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pf4j.Extension;

/**
 * Reads Modbus TCP registers/coils (§2 MODBUS device). Returns one row per register/coil as
 * {@code {address, value}}; the core parser picks values with COLUMN ("value") on the first row
 * or INDEX (row N) for a specific register.
 *
 * <p>Device config params: {@code host} (required), {@code port} (optional, 502),
 * {@code unitId} (optional, 1). Task definition: {@code registerType}
 * (HOLDING|INPUT|COIL|DISCRETE, default HOLDING), {@code address} (int, default 0),
 * {@code count} (int, default 1).
 */
@Extension
public class ModbusDeviceAdapter implements DeviceAdapter {

    @Override
    public String type() {
        return "MODBUS";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.MODBUS_READ);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        if (str(config.params().get("host")) == null) {
            throw new AdapterException("Modbus device requires 'host'");
        }
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String host = str(config.params().get("host"));
        if (host == null) {
            throw new AdapterException("Modbus device is missing 'host'");
        }
        int port = intOr(config.params().get("port"), 502);
        int unitId = intOr(config.params().get("unitId"), 1);
        String regType = String.valueOf(
                request.definition().getOrDefault("registerType", "HOLDING")).toUpperCase();
        int address = intOr(request.definition().get("address"), 0);
        int count = Math.max(1, intOr(request.definition().get("count"), 1));

        ModbusTCPMaster master = new ModbusTCPMaster(host, port);
        try {
            master.connect();
            List<Map<String, Object>> rows = new ArrayList<>(count);
            switch (regType) {
                case "HOLDING" -> {
                    Register[] regs = master.readMultipleRegisters(unitId, address, count);
                    for (int i = 0; i < regs.length; i++) {
                        rows.add(row(address + i, regs[i].getValue()));
                    }
                }
                case "INPUT" -> {
                    InputRegister[] regs = master.readInputRegisters(unitId, address, count);
                    for (int i = 0; i < regs.length; i++) {
                        rows.add(row(address + i, regs[i].getValue()));
                    }
                }
                case "COIL" -> {
                    BitVector bits = master.readCoils(unitId, address, count);
                    for (int i = 0; i < count; i++) {
                        rows.add(row(address + i, bits.getBit(i)));
                    }
                }
                case "DISCRETE" -> {
                    BitVector bits = master.readInputDiscretes(unitId, address, count);
                    for (int i = 0; i < count; i++) {
                        rows.add(row(address + i, bits.getBit(i)));
                    }
                }
                default -> throw new AdapterException("unknown Modbus registerType '" + regType + "'");
            }
            return RawResult.ofRows(rows, Instant.now());
        } catch (AdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new AdapterException(
                    "Modbus read from " + host + ":" + port + " failed: " + e.getMessage(), e);
        } finally {
            master.disconnect();
        }
    }

    private static Map<String, Object> row(int address, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("address", address);
        m.put("value", value);
        return m;
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
