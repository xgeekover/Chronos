package io.chronos.api.adapter;

/** Shape of the payload an adapter returns in a {@link RawResult}. */
public enum RawKind {
    /** Tabular rows (e.g. JDBC result set) → {@code RawResult.rows()}. */
    ROWS,
    /** Plain text (e.g. file content, shell stdout) → {@code RawResult.text()}. */
    TEXT,
    /** JSON document serialized as text → {@code RawResult.text()}. */
    JSON,
    /** Binary payload → {@code RawResult.bytes()}. */
    BYTES
}
