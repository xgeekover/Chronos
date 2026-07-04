package io.chronos.api.adapter;

import java.util.List;
import java.util.Map;
import org.pf4j.ExtensionPoint;

/**
 * The core extension point every protocol plugin implements (§6, 불변 규칙 2).
 *
 * <p>Adapters are discovered at runtime by PF4J (ADR-001): drop a plugin JAR into the
 * plugins directory and its {@code @Extension}-annotated {@code DeviceAdapter} is picked up
 * without touching the core. Adapters depend only on {@code core-api}.
 */
public interface DeviceAdapter extends ExtensionPoint {

    /** @return the concrete adapter key, e.g. {@code "JDBC_MARIADB"}; matched against Device.adapterType. */
    String type();

    /** @return what this adapter supports, used to validate Task definitions up front. */
    Capabilities capabilities();

    /**
     * Validate connection config / perform a connection test (§10 "접속테스트").
     *
     * @throws AdapterException if the config is invalid or the source is unreachable
     */
    void validate(DeviceConfig config) throws AdapterException;

    /**
     * Execute one Task and return its raw result (§7 step 2). Implementations should honor
     * {@code request.timeout()}; the engine additionally enforces it externally.
     *
     * @throws AdapterException on any collection failure
     */
    RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException;

    /** Release pooled connections/sessions. Called on plugin stop. */
    default void close() {
        // no-op by default
    }

    /**
     * Live connection-pool stats, one entry per pooled connection (empty for adapters that don't
     * pool). Surfaced by the Devices management view so operators can see and bound DB sessions.
     */
    default List<Map<String, Object>> poolStats() {
        return List.of();
    }
}
