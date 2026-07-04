package io.chronos.engine.plugin;

import io.chronos.api.adapter.DeviceAdapter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges several {@link PluginRuntime}s. Used to combine built-in adapters (discovered on the
 * classpath via {@link ServiceLoaderPluginRuntime}) with externally dropped PF4J plugin JARs
 * ({@link Pf4jPluginRuntime}). Earlier runtimes win on a type-key clash.
 */
public final class CompositePluginRuntime implements PluginRuntime {

    private final List<PluginRuntime> delegates;

    public CompositePluginRuntime(List<PluginRuntime> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Optional<DeviceAdapter> find(String adapterType) {
        for (PluginRuntime r : delegates) {
            Optional<DeviceAdapter> a = r.find(adapterType);
            if (a.isPresent()) {
                return a;
            }
        }
        return Optional.empty();
    }

    @Override
    public Collection<DeviceAdapter> all() {
        Map<String, DeviceAdapter> merged = new LinkedHashMap<>();
        for (PluginRuntime r : delegates) {
            for (DeviceAdapter a : r.all()) {
                merged.putIfAbsent(a.type(), a);
            }
        }
        return merged.values();
    }
}
