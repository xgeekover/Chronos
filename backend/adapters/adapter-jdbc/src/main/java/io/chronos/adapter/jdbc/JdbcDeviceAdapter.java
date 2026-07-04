package io.chronos.adapter.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.chronos.api.adapter.AdapterException;
import io.chronos.api.adapter.Capabilities;
import io.chronos.api.adapter.CollectRequest;
import io.chronos.api.adapter.DeviceAdapter;
import io.chronos.api.adapter.DeviceConfig;
import io.chronos.api.adapter.RawResult;
import io.chronos.api.adapter.TaskType;
import org.pf4j.Extension;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC adapter (Oracle / MS-SQL / MariaDB via HikariCP), §4/§7. Maintains one connection pool
 * per distinct (jdbcUrl, username) and executes a Task's SQL into tabular rows.
 *
 * <p>DeviceConfig contract: {@code params.jdbcUrl} (required), {@code params.maxPoolSize}
 * (optional, default 4); credentials in {@code secrets.username}/{@code secrets.password}.
 * Task definition: {@code definition.sql} (required).
 */
@Extension
public class JdbcDeviceAdapter implements DeviceAdapter {

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "JDBC";
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(TaskType.QUERY);
    }

    @Override
    public void validate(DeviceConfig config) throws AdapterException {
        try (Connection c = pool(config).getConnection()) {
            if (!c.isValid(5)) {
                throw new AdapterException("connection validation failed (isValid=false)");
            }
        } catch (SQLException e) {
            throw new AdapterException("JDBC connection test failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        Object sql = request.definition().get("sql");
        if (sql == null || sql.toString().isBlank()) {
            throw new AdapterException("JDBC task definition is missing 'sql'");
        }
        int timeoutSec = Math.max(1, (int) Math.ceil(request.timeout().toMillis() / 1000.0));
        try (Connection c = pool(config).getConnection();
                Statement st = c.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            try (ResultSet rs = st.executeQuery(sql.toString())) {
                return RawResult.ofRows(toRows(rs), Instant.now());
            }
        } catch (SQLException e) {
            throw new AdapterException("JDBC query failed: " + e.getMessage(), e);
        }
    }

    private static List<Map<String, Object>> toRows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(cols);
            for (int i = 1; i <= cols; i++) {
                row.put(md.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private HikariDataSource pool(DeviceConfig config) throws AdapterException {
        String jdbcUrl = str(config.params().get("jdbcUrl"));
        if (jdbcUrl == null) {
            throw new AdapterException("JDBC device config is missing 'jdbcUrl'");
        }
        String username = config.secrets().get("username");
        String password = config.secrets().get("password");
        // include a password hash so a credential rotation gets a fresh pool (not the stale one)
        String key = jdbcUrl + "|" + username + "|" + Objects.hashCode(password);
        return pools.computeIfAbsent(key, k -> {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(jdbcUrl);
            hc.setUsername(username);
            hc.setPassword(password);
            hc.setMaximumPoolSize(intOr(config.params().get("maxPoolSize"), 4));
            hc.setPoolName("chronos-jdbc-" + Integer.toHexString(key.hashCode()));
            hc.setConnectionTimeout(10_000);
            return new HikariDataSource(hc);
        });
    }

    @Override
    public List<Map<String, Object>> poolStats() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HikariDataSource ds : pools.values()) {
            var mx = ds.getHikariPoolMXBean();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("adapter", "JDBC");
            m.put("url", ds.getJdbcUrl());
            m.put("user", ds.getUsername());
            m.put("max", ds.getMaximumPoolSize());
            m.put("active", mx == null ? 0 : mx.getActiveConnections());
            m.put("idle", mx == null ? 0 : mx.getIdleConnections());
            m.put("total", mx == null ? 0 : mx.getTotalConnections());
            m.put("awaiting", mx == null ? 0 : mx.getThreadsAwaitingConnection());
            out.add(m);
        }
        return out;
    }

    @Override
    public void close() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int intOr(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
