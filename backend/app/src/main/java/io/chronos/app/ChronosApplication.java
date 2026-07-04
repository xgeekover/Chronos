package io.chronos.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Chronos backend entry point (§3, §4). */
@SpringBootApplication
@EnableScheduling // retention cleanup job (§8, Phase 3)
public class ChronosApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChronosApplication.class, args);
    }
}
