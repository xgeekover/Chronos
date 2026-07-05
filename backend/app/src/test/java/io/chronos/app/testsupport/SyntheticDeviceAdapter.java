package io.chronos.app.testsupport;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Test-only adapter (type {@code "SYNTHETIC"}) that emits the task's {@code definition} map verbatim as
 * a single row — a deterministic, no-I/O in-process value source. Discovered on the test classpath via
 * {@code ServiceLoader} (see {@code META-INF/services/io.chronos.api.adapter.DeviceAdapter}). Replaces
 * the removed SCRIPT_JAVA adapter as the collection fixture for the app integration tests.
 */
public class SyntheticDeviceAdapter implements DeviceAdapter {

    @Override
    public String type() {
        return "SYNTHETIC";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.values());
    }

    @Override
    public void validate(DeviceConfig config) {
        // nothing to connect to
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        return RawResult.ofRows(List.of(new LinkedHashMap<>(request.definition())), Instant.now());
    }
}
