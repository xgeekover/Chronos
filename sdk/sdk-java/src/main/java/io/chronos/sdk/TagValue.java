package io.chronos.sdk;

/** A tag value as seen by SDK consumers (decoupled from the protobuf wire type). */
public record TagValue(String tag, String value, String quality, long tsEpochMillis) {

    /** Convenience: the value parsed as a double, or NaN if not numeric. */
    public double asDouble() {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
