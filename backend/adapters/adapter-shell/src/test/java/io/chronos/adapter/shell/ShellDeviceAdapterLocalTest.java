package io.chronos.adapter.shell;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShellDeviceAdapterLocalTest {

    private final ShellDeviceAdapter adapter = new ShellDeviceAdapter();

    private DeviceConfig localCfg(Object allowlist) {
        return new DeviceConfig("SHELL", Map.of("mode", "LOCAL", "allowlist", allowlist), null);
    }

    private CollectRequest req(String command, List<String> args, Duration timeout) {
        return new CollectRequest("t1", "n1", TaskType.SHELL,
                Map.of("command", command, "args", args), timeout);
    }

    @Test
    void runsAllowlistedCommandLocally() throws Exception {
        RawResult raw = adapter.collect(localCfg(List.of("echo")),
                req("echo", List.of("chronos-test"), Duration.ofSeconds(5)));
        assertTrue(raw.text().contains("chronos-test"));
    }

    @Test
    void rejectsCommandNotInAllowlist() {
        AdapterException ex = assertThrows(AdapterException.class, () ->
                adapter.collect(localCfg(List.of("echo")),
                        req("rm", List.of("-rf", "/"), Duration.ofSeconds(5))));
        assertTrue(ex.getMessage().contains("not allowlisted"));
    }

    @Test
    void enforcesTimeout() {
        AdapterException ex = assertThrows(AdapterException.class, () ->
                adapter.collect(localCfg(List.of("sleep")),
                        req("sleep", List.of("5"), Duration.ofMillis(300))));
        assertTrue(ex.getMessage().toLowerCase().contains("timed out"));
    }

    @Test
    void validateRequiresAllowlist() {
        assertThrows(AdapterException.class, () ->
                adapter.validate(new DeviceConfig("SHELL", Map.of("mode", "LOCAL"), null)));
    }
}
