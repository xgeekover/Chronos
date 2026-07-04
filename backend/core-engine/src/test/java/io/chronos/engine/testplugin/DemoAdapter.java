package io.chronos.engine.testplugin;

import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.time.Instant;

/** Minimal adapter packaged into a JAR by the PF4J drop-in test to prove runtime discovery. */
public class DemoAdapter implements DeviceAdapter {

    @Override
    public String type() {
        return "DEMO";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.API_CALL);
    }

    @Override
    public void validate(DeviceConfig config) {}

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) {
        return RawResult.ofText("demo", Instant.now());
    }
}
