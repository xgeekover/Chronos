package io.chronos.adapter.script;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import io.chronos.adapter.script.JavaScriptEngine.ScriptError;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pf4j.Extension;

/**
 * Runs a dynamic <b>pure-Java</b> script (§2 SCRIPT_JAVA): the task body returns a
 * {@code Map<String,Object>} of tag → value, compiled in-memory and executed under a blocklist +
 * timeout ({@link JavaScriptEngine}). Task definition: {@code script} (required);
 * {@code validateOnly:true} compiles without executing (used by the authoring UI).
 *
 * <p>⚠️ In-process Java is hardened but not a true sandbox; use process/container isolation for
 * untrusted input (see docs/security.md).
 */
@Extension
public class ScriptDeviceAdapter implements DeviceAdapter {

    private final JavaScriptEngine engine = new JavaScriptEngine();

    @Override
    public String type() {
        return "SCRIPT_JAVA";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.SCRIPT_JAVA);
    }

    @Override
    public void validate(DeviceConfig config) {
        // nothing to connect to; scripts are validated at compile time on each run
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        String script = str(request.definition().get("script"));
        boolean validateOnly = Boolean.parseBoolean(str(request.definition().getOrDefault("validateOnly", "false")));
        try {
            if (validateOnly) {
                engine.validate(script);
                return RawResult.ofText("valid", Instant.now());
            }
            Map<String, Object> produced = engine.run(script, request.timeout());
            return RawResult.ofRows(List.of(new LinkedHashMap<>(produced)), Instant.now());
        } catch (ScriptError e) {
            // encode the user line so the editor can place the marker
            String prefix = e.line != null ? "LINE:" + e.line + " " : "";
            throw new AdapterException(prefix + e.getMessage(), e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
