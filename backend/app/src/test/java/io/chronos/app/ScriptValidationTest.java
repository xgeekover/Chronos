package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronos.app.service.ScriptValidationService;
import io.chronos.app.service.ScriptValidationService.ValidationResult;
import io.chronos.engine.plugin.ServiceLoaderPluginRuntime;
import org.junit.jupiter.api.Test;

/** Pure-Java script validation: good compiles, bad syntax reports a line, blocked API is rejected. */
class ScriptValidationTest {

    // ServiceLoader finds the SCRIPT_JAVA adapter on the test classpath (runtimeOnly dep).
    private final ScriptValidationService svc =
            new ScriptValidationService(ServiceLoaderPluginRuntime.fromContextClasspath());

    @Test
    void validScriptCompiles() {
        assertThat(svc.validate("return java.util.Map.of(\"temp\", 21.5, \"humidity\", 47.0);").valid()).isTrue();
    }

    @Test
    void syntaxErrorReportsLine() {
        ValidationResult r = svc.validate("int x = ;\nreturn java.util.Map.of();");
        assertThat(r.valid()).isFalse();
        assertThat(r.error()).isNotBlank();
        assertThat(r.line()).isEqualTo(1);
    }

    @Test
    void blockedApiIsRejected() {
        assertThat(svc.validate("System.exit(1);").valid()).isFalse();
    }
}
