package io.chronos.app.service;

import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import io.chronos.engine.plugin.PluginRuntime;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Validates and previews pure-Java task scripts for the authoring UI by delegating to the real
 * SCRIPT_JAVA adapter (single source of truth for compilation/sandbox). Validation compiles only
 * (definition {@code validateOnly:true}); preview compiles and runs once.
 */
@Service
public class ScriptValidationService {

    // The adapter encodes the user line as a "LINE:<n> <message>" prefix on compile errors.
    private static final Pattern LINE_PREFIX = Pattern.compile("^LINE:(\\d+)\\s(.*)$", Pattern.DOTALL);

    private final PluginRuntime plugins;

    public ScriptValidationService(PluginRuntime plugins) {
        this.plugins = plugins;
    }

    public record ValidationResult(boolean valid, String error, Integer line) {
        static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }
    }

    /** Compile-only check (syntax + security blocklist). Never executes the script. */
    public ValidationResult validate(String script) {
        try {
            collect(Map.of("script", nullSafe(script), "validateOnly", true));
            return ValidationResult.ok();
        } catch (AdapterException e) {
            Matcher m = LINE_PREFIX.matcher(e.getMessage() == null ? "" : e.getMessage());
            if (m.matches()) {
                return new ValidationResult(false, m.group(2), Integer.valueOf(m.group(1)));
            }
            return new ValidationResult(false, e.getMessage(), null);
        }
    }

    private RawResult collect(Map<String, Object> definition) throws AdapterException {
        var adapter = plugins.find("SCRIPT_JAVA")
                .orElseThrow(() -> new IllegalStateException("SCRIPT_JAVA adapter not available"));
        var config = new DeviceConfig("SCRIPT_JAVA", Map.of(), null);
        var request = new CollectRequest("script-tool", "script-tool", TaskType.SCRIPT_JAVA,
                definition, Duration.ofSeconds(5));
        return adapter.collect(config, request);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
