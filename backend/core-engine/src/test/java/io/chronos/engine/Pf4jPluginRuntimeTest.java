package io.chronos.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.chronos.engine.plugin.Pf4jPluginRuntime;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the PF4J drop-in model (불변 규칙 2): a plugin JAR placed in the plugins directory is
 * discovered at runtime and its {@link io.chronos.api.adapter.DeviceAdapter} becomes available —
 * with no change to the core. The JAR is assembled here (manifest + extensions.idx + the adapter
 * class) so the test is self-contained.
 */
class Pf4jPluginRuntimeTest {

    private static final String CLASS_RES = "io/chronos/engine/testplugin/DemoAdapter.class";

    @Test
    void discoversAdapterFromDroppedJar(@TempDir Path dir) throws Exception {
        Path pluginsDir = Files.createDirectories(dir.resolve("plugins"));
        buildPluginJar(pluginsDir.resolve("demo-plugin.jar"));

        try (Pf4jPluginRuntime runtime = new Pf4jPluginRuntime(pluginsDir)) {
            assertTrue(runtime.find("DEMO").isPresent(), "DEMO adapter should be discovered from the JAR");
            assertEquals("DEMO", runtime.find("DEMO").orElseThrow().type());
        }
    }

    /** Assemble a valid PF4J plugin JAR: PluginId/Version manifest + extensions.idx + the class. */
    private void buildPluginJar(Path jarPath) throws Exception {
        Manifest mf = new Manifest();
        Attributes a = mf.getMainAttributes();
        a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        a.putValue("Plugin-Id", "demo-adapter");
        a.putValue("Plugin-Version", "1.0.0");

        byte[] classBytes;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASS_RES)) {
            classBytes = in.readAllBytes();
        }

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), mf)) {
            jos.putNextEntry(new JarEntry(CLASS_RES));
            jos.write(classBytes);
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("META-INF/extensions.idx"));
            jos.write("io.chronos.engine.testplugin.DemoAdapter\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }
}
