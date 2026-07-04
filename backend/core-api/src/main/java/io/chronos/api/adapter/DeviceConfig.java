package io.chronos.api.adapter;

import java.util.Map;
import java.util.Objects;

/**
 * Connection configuration handed to an adapter. Non-sensitive parameters live in
 * {@link #params()}; decrypted credentials live in {@link #secrets()} (§2, ADR-008).
 *
 * @param adapterType concrete adapter key, e.g. {@code "JDBC_MARIADB"}
 * @param params      non-sensitive connection params (host, port, database, …)
 * @param secrets     decrypted credentials (masked in logs)
 */
public record DeviceConfig(String adapterType, Map<String, Object> params, Secrets secrets) {

    public DeviceConfig {
        Objects.requireNonNull(adapterType, "adapterType");
        params = params == null ? Map.of() : Map.copyOf(params);
        secrets = secrets == null ? Secrets.empty() : secrets;
    }
}
