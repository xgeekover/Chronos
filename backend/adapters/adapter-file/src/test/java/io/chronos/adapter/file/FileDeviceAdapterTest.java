package io.chronos.adapter.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDeviceAdapterTest {

    private final FileDeviceAdapter adapter = new FileDeviceAdapter();

    @Test
    void readsFileContents(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("data.json");
        Files.writeString(f, "{\"temp\":21.5}");
        DeviceConfig cfg = new DeviceConfig("FILE", Map.of("basePath", dir.toString()), null);
        CollectRequest req = new CollectRequest("t1", "n1", TaskType.FILE_READ,
                Map.of("path", "data.json"), Duration.ofSeconds(5));

        RawResult raw = adapter.collect(cfg, req);
        assertEquals("{\"temp\":21.5}", raw.text());
    }

    @Test
    void rejectsPathTraversalOutsideBase(@TempDir Path dir) {
        DeviceConfig cfg = new DeviceConfig("FILE", Map.of("basePath", dir.toString()), null);
        CollectRequest req = new CollectRequest("t1", "n1", TaskType.FILE_READ,
                Map.of("path", "../../etc/passwd"), Duration.ofSeconds(5));
        AdapterException ex = assertThrows(AdapterException.class, () -> adapter.collect(cfg, req));
        assertTrue(ex.getMessage().contains("escapes basePath"));
    }
}
