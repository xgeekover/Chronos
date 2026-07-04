package io.chronos.api.adapter;

import java.util.Map;
import java.util.Set;

/**
 * Holds decrypted credential material for an adapter connection. Never logged in clear:
 * {@link #toString()} masks all values (불변 규칙 4 — secrets must not appear in logs).
 */
public final class Secrets {

    private final Map<String, String> values;

    public Secrets(Map<String, String> values) {
        this.values = Map.copyOf(values == null ? Map.of() : values);
    }

    public static Secrets empty() {
        return new Secrets(Map.of());
    }

    public String get(String key) {
        return values.get(key);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    /** @return key names only — values are masked so they never leak into logs. */
    @Override
    public String toString() {
        return "Secrets" + values.keySet().stream().map(k -> k + "=***").toList();
    }
}
