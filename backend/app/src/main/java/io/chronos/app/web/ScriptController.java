package io.chronos.app.web;

import io.chronos.app.service.ScriptValidationService;
import io.chronos.app.service.ScriptValidationService.ValidationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Script authoring support: live syntax/security validation for the Flows Function node (Java). */
@RestController
@RequestMapping("/api/scripts")
public class ScriptController {

    private final ScriptValidationService scripts;

    public ScriptController(ScriptValidationService scripts) {
        this.scripts = scripts;
    }

    public record ScriptBody(@NotNull String script) {}

    @PostMapping("/validate")
    public ValidationResult validate(@Valid @RequestBody ScriptBody body) {
        return scripts.validate(body.script());
    }
}
