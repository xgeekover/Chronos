package io.chronos.engine.plugin;

import io.chronos.api.adapter.DeviceAdapter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * {@link PluginRuntime} backed by {@link ServiceLoader}: adapters on the runtime classpath
 * declare themselves in {@code META-INF/services/io.chronos.api.adapter.DeviceAdapter}.
 * (Phase 4 replaces this with a PF4J-backed implementation.)
 */
public final class ServiceLoaderPluginRuntime implements PluginRuntime {

    private final Map<String, DeviceAdapter> byType = new LinkedHashMap<>();

    private ServiceLoaderPluginRuntime() {}

    /** Discover adapters on the given classloader's classpath. */
    public static ServiceLoaderPluginRuntime fromClasspath(ClassLoader classLoader) {
        ServiceLoaderPluginRuntime r = new ServiceLoaderPluginRuntime();
        for (DeviceAdapter adapter : ServiceLoader.load(DeviceAdapter.class, classLoader)) {
            r.byType.putIfAbsent(adapter.type(), adapter);
        }
        return r;
    }

    /** Discover adapters on the thread context classloader. */
    public static ServiceLoaderPluginRuntime fromContextClasspath() {
        return fromClasspath(Thread.currentThread().getContextClassLoader());
    }

    /** Build a runtime from an explicit set of adapters (used in tests and manual wiring). */
    public static ServiceLoaderPluginRuntime of(DeviceAdapter... adapters) {
        ServiceLoaderPluginRuntime r = new ServiceLoaderPluginRuntime();
        for (DeviceAdapter a : adapters) {
            r.byType.putIfAbsent(a.type(), a);
        }
        return r;
    }

    @Override
    public Optional<DeviceAdapter> find(String adapterType) {
        return Optional.ofNullable(byType.get(adapterType));
    }

    @Override
    public Collection<DeviceAdapter> all() {
        return byType.values();
    }
}
