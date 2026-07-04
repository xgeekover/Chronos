package io.chronos.engine.plugin;

import io.chronos.api.adapter.DeviceAdapter;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.pf4j.JarPluginManager;
import org.pf4j.PluginManager;

/**
 * {@link PluginRuntime} that loads adapter plugins as JARs from a directory at runtime via PF4J
 * (ADR-001, §6): drop a plugin JAR into the plugins directory and its {@code @Extension}
 * {@code DeviceAdapter} is discovered — the core is never modified to add a protocol (불변 규칙 2).
 */
public final class Pf4jPluginRuntime implements PluginRuntime, AutoCloseable {

    private final PluginManager manager;
    private final Map<String, DeviceAdapter> byType = new LinkedHashMap<>();

    public Pf4jPluginRuntime(Path pluginsDir) {
        this.manager = new JarPluginManager(pluginsDir);
        manager.loadPlugins();
        manager.startPlugins();
        for (DeviceAdapter adapter : manager.getExtensions(DeviceAdapter.class)) {
            byType.putIfAbsent(adapter.type(), adapter);
        }
    }

    @Override
    public Optional<DeviceAdapter> find(String adapterType) {
        return Optional.ofNullable(byType.get(adapterType));
    }

    @Override
    public Collection<DeviceAdapter> all() {
        return byType.values();
    }

    @Override
    public void close() {
        // Best-effort teardown: PF4J's stop/unload can throw during shutdown; never let it
        // propagate out of close().
        try {
            manager.stopPlugins();
        } catch (RuntimeException ignored) {
            // ignore
        }
        try {
            manager.unloadPlugins();
        } catch (RuntimeException ignored) {
            // ignore
        }
    }
}
