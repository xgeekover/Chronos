package io.chronos.engine.plugin;

import io.chronos.api.adapter.DeviceAdapter;
import java.util.Collection;
import java.util.Optional;

/**
 * Resolves a {@link DeviceAdapter} by its {@code type()} key (§7 step 2). Keeps the core
 * decoupled from concrete adapters (불변 규칙 2): the engine asks for an adapter by type and
 * never imports a specific implementation.
 *
 * <p>Phase 2 discovers adapters via {@link java.util.ServiceLoader}; Phase 4 swaps in PF4J
 * runtime plugin loading behind this same interface.
 */
public interface PluginRuntime {

    /** @return the adapter whose {@code type()} equals {@code adapterType}, if present. */
    Optional<DeviceAdapter> find(String adapterType);

    /** @return all discovered adapters. */
    Collection<DeviceAdapter> all();
}
