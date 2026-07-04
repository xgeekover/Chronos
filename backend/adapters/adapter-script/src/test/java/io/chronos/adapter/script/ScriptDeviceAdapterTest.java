package io.chronos.adapter.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawKind;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptDeviceAdapterTest {

    private final ScriptDeviceAdapter adapter = new ScriptDeviceAdapter();

    private CollectRequest req(String script, Duration timeout) {
        return new CollectRequest("t1", "n1", TaskType.SCRIPT_JAVA, Map.of("script", script), timeout);
    }

    private final DeviceConfig cfg = new DeviceConfig("SCRIPT_JAVA", Map.of(), null);

    @Test
    void runsPureJavaReturningMapAsRow() throws Exception {
        RawResult raw = adapter.collect(cfg,
                req("return java.util.Map.of(\"temp\", 20 + 1.5, \"ok\", true);", Duration.ofSeconds(10)));
        assertEquals(RawKind.ROWS, raw.kind());
        assertEquals(21.5, ((Number) raw.rows().get(0).get("temp")).doubleValue(), 1e-9);
        assertEquals(true, raw.rows().get(0).get("ok"));
    }

    @Test
    void blocksRuntimeExec() {
        assertThrows(AdapterException.class, () ->
                adapter.collect(cfg, req("Runtime.getRuntime().exec(\"id\");", Duration.ofSeconds(10))));
    }

    @Test
    void blocksSystemExit() {
        AdapterException ex = assertThrows(AdapterException.class, () ->
                adapter.collect(cfg, req("System.exit(1);", Duration.ofSeconds(10))));
        assertTrue(ex.getMessage().toLowerCase().contains("disallowed"));
    }

    @Test
    void reportsCompileErrorLine() {
        AdapterException ex = assertThrows(AdapterException.class, () ->
                adapter.collect(cfg, req("int x = ;\nreturn java.util.Map.of();", Duration.ofSeconds(10))));
        assertTrue(ex.getMessage().startsWith("LINE:1"), () -> "got: " + ex.getMessage());
    }
}
