package io.chronos.app.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal info endpoint so the frontend has something to call in Phase 1. */
@RestController
@RequestMapping("/api")
public class InfoController {

    private final String appName;

    public InfoController(@Value("${spring.application.name:chronos}") String appName) {
        this.appName = appName;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "name", appName,
                "version", "0.1.0-SNAPSHOT");
    }
}
