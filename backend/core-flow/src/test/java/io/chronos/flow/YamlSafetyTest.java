package io.chronos.flow;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Empirically verify whether SnakeYAML's default new Yaml() instantiates arbitrary global-tagged types. */
class YamlSafetyTest {

    @Test
    void defaultYamlRejectsGlobalTags() {
        org.yaml.snakeyaml.Yaml y = new org.yaml.snakeyaml.Yaml();
        // If the default constructor is unsafe this instantiates a java.io.File; if safe it throws.
        assertThrows(Exception.class, () -> y.load("!!java.io.File [/tmp/x]"));
    }
}
