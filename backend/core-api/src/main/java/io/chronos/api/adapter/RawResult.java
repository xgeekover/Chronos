package io.chronos.api.adapter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Raw, un-normalized output of a single Task execution (§6). Exactly one payload field is
 * meaningful, selected by {@link #kind()}. Kept to JDK types so {@code core-api} stays
 * framework-free (ADR-001).
 *
 * @param kind        which payload field carries the data
 * @param rows        tabular rows when {@code kind == ROWS}; else empty
 * @param text        text/JSON payload when {@code kind == TEXT|JSON}; else null
 * @param bytes       binary payload when {@code kind == BYTES}; else null
 * @param collectedAt when the adapter produced this result
 */
public record RawResult(
        RawKind kind,
        List<Map<String, Object>> rows,
        String text,
        byte[] bytes,
        Instant collectedAt) {

    public RawResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public static RawResult ofRows(List<Map<String, Object>> rows, Instant at) {
        return new RawResult(RawKind.ROWS, rows, null, null, at);
    }

    public static RawResult ofText(String text, Instant at) {
        return new RawResult(RawKind.TEXT, List.of(), text, null, at);
    }

    public static RawResult ofJson(String json, Instant at) {
        return new RawResult(RawKind.JSON, List.of(), json, null, at);
    }

    public static RawResult ofBytes(byte[] bytes, Instant at) {
        return new RawResult(RawKind.BYTES, List.of(), null, bytes, at);
    }
}
