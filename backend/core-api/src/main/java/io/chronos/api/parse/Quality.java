package io.chronos.api.parse;

/**
 * Quality flag for a collected value (OPC-UA inspired, ADR-013).
 *
 * <ul>
 *   <li>{@link #GOOD} – value collected and mapped successfully.</li>
 *   <li>{@link #BAD} – collection or mapping failed; value is not trustworthy.</li>
 *   <li>{@link #STALE} – value is older than expected (source did not refresh).</li>
 *   <li>{@link #UNCERTAIN} – value present but a transform/cast partially failed.</li>
 * </ul>
 */
public enum Quality {
    GOOD,
    BAD,
    STALE,
    UNCERTAIN
}
