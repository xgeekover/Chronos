package io.chronos.engine.history;

import io.chronos.api.history.HistoryStore;
import io.chronos.api.history.Snapshot;
import io.chronos.api.history.TagSample;
import io.chronos.api.parse.Quality;
import io.chronos.api.parse.TagValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-node SQLite implementation of the Time Machine store (§8, ADR-004). Each node gets its
 * own {@code <dataDir>/<nodeId>.sqlite} file (WAL mode, single writer). Replaceable by
 * DuckDB/RocksDB behind the {@link HistoryStore} SPI without touching the core.
 */
public final class SqliteHistoryStore implements HistoryStore, AutoCloseable {

    private final Path dataDir;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public SqliteHistoryStore(Path dataDir) {
        this.dataDir = dataDir;
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create history data dir: " + dataDir, e);
        }
    }

    private Connection connection(String nodeId) {
        return connections.computeIfAbsent(nodeId, id -> {
            try {
                Path file = dataDir.resolve(id + ".sqlite");
                Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("PRAGMA busy_timeout=5000");
                    st.execute("CREATE TABLE IF NOT EXISTS samples ("
                            + "ts INTEGER NOT NULL, tag TEXT NOT NULL, value TEXT, quality INTEGER NOT NULL)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_samples_tag_ts ON samples(tag, ts)");
                }
                return c;
            } catch (SQLException e) {
                throw new IllegalStateException("cannot open history store for node " + id, e);
            }
        });
    }

    @Override
    public void append(String nodeId, List<TagSample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        Connection c = connection(nodeId);
        synchronized (c) { // SQLite is single-writer per node
            try {
                boolean prevAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO samples(ts, tag, value, quality) VALUES (?,?,?,?)")) {
                    for (TagSample s : samples) {
                        ps.setLong(1, s.tsMillis());
                        ps.setString(2, s.tag());
                        ps.setString(3, s.value() == null ? null : String.valueOf(s.value()));
                        ps.setInt(4, s.quality().ordinal());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
                c.setAutoCommit(prevAuto);
            } catch (SQLException e) {
                throw new IllegalStateException("history append failed for node " + nodeId, e);
            }
        }
    }

    @Override
    public Snapshot snapshotAt(String nodeId, Instant t, Set<String> tags) {
        Connection c = connection(nodeId);
        Map<String, TagValue> values = new LinkedHashMap<>();
        long ttl = t.toEpochMilli();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT value, quality, ts FROM samples WHERE tag = ? AND ts <= ? ORDER BY ts DESC LIMIT 1")) {
                for (String tag : tags) {
                    ps.setString(1, tag);
                    ps.setLong(2, ttl);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            values.put(tag, new TagValue(tag, rs.getString("value"),
                                    quality(rs.getInt("quality")), Instant.ofEpochMilli(rs.getLong("ts"))));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("snapshot failed for node " + nodeId, e);
            }
        }
        return new Snapshot(nodeId, t, values);
    }

    @Override
    public List<TagSample> range(String nodeId, String tag, Instant from, Instant to) {
        Connection c = connection(nodeId);
        List<TagSample> out = new ArrayList<>();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts, value, quality FROM samples WHERE tag = ? AND ts BETWEEN ? AND ? ORDER BY ts")) {
                ps.setString(1, tag);
                ps.setLong(2, from.toEpochMilli());
                ps.setLong(3, to.toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new TagSample(tag, rs.getString("value"),
                                quality(rs.getInt("quality")), rs.getLong("ts")));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("range query failed for node " + nodeId, e);
            }
        }
        return out;
    }

    @Override
    public void enforceRetention(String nodeId, Duration retention) {
        Connection c = connection(nodeId);
        long cutoff = Instant.now().minus(retention).toEpochMilli();
        synchronized (c) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM samples WHERE ts < ?")) {
                ps.setLong(1, cutoff);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("retention enforcement failed for node " + nodeId, e);
            }
        }
    }

    private static Quality quality(int ordinal) {
        Quality[] vals = Quality.values();
        return ordinal >= 0 && ordinal < vals.length ? vals[ordinal] : Quality.UNCERTAIN;
    }

    @Override
    public void close() {
        connections.values().forEach(c -> {
            try {
                c.close();
            } catch (SQLException ignored) {
                // best-effort on shutdown
            }
        });
        connections.clear();
    }
}
