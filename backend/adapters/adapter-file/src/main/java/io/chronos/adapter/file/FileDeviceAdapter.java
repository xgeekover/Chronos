package io.chronos.adapter.file;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import org.pf4j.Extension;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Reads a local file's contents (§2 FILE device). The mapped values are then extracted with
 * JSONPATH/REGEX by the core parser.
 *
 * <p>Device config: optional {@code params.basePath} confines reads to a directory (path-traversal
 * guard). Task definition: {@code path} (required), {@code charset} (optional, default UTF-8).
 */
@Extension
public class FileDeviceAdapter implements DeviceAdapter {

    @Override
    public String type() {
        return "FILE";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.FILE_READ);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        String basePath = str(config.params().get("basePath"));
        if (basePath != null && !Files.isDirectory(Path.of(basePath))) {
            throw new AdapterException("basePath is not a directory: " + basePath);
        }
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String pathStr = str(request.definition().get("path"));
        if (pathStr == null || pathStr.isBlank()) {
            throw new AdapterException("FILE task definition is missing 'path'");
        }
        Charset charset = Charset.forName(
                request.definition().getOrDefault("charset", "UTF-8").toString());
        Path file = resolve(str(config.params().get("basePath")), pathStr);
        try {
            String content = Files.readString(file, charset);
            return RawResult.ofText(content, Instant.now());
        } catch (Exception e) {
            throw new AdapterException("failed to read file " + file + ": " + e.getMessage(), e);
        }
    }

    /** Resolve {@code path} against {@code basePath} (if set) and forbid escaping the base dir. */
    private static Path resolve(String basePath, String path) throws AdapterException {
        if (basePath == null) {
            return Path.of(path);
        }
        Path base = Path.of(basePath).toAbsolutePath().normalize();
        Path resolved = base.resolve(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new AdapterException("path escapes basePath: " + path);
        }
        return resolved;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
