package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronos.api.history.HistoryStore;
import io.chronos.api.history.Snapshot;
import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.service.CollectionService;
import io.chronos.app.service.DeviceService;
import io.chronos.app.service.MappingService;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.TagService;
import io.chronos.app.service.TaskService;
import io.chronos.engine.pipeline.CollectionResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared collection E2E flow exercised against each supported JDBC source (MariaDB / Oracle /
 * MS-SQL). Subclasses provide the container and the dialect-specific DDL; the metadata DB is a
 * shared Testcontainers PostgreSQL ({@link TestcontainersConfiguration}). Names are prefixed
 * per source so the shared (cached) Spring context's unique constraints don't collide.
 */
abstract class AbstractJdbcCollectionE2E {

    @Autowired DeviceService devices;
    @Autowired NodeService nodes;
    @Autowired TagService tags;
    @Autowired TaskService taskService;
    @Autowired MappingService mappings;
    @Autowired CollectionService collection;
    @Autowired HistoryStore historyStore;

    /**
     * Seed the source, configure Device/Node/Tag/Task/Mapping, run the pipeline once, and assert
     * the values land in the current-value cache and the node-local SQLite history.
     */
    protected void collectAndAssert(String prefix, String jdbcUrl, String user, String pass,
            String createSql, String insertSql, String selectSql) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass);
                Statement st = c.createStatement()) {
            st.execute(createSql);
            st.execute(insertSql);
        }

        DeviceEntity device = devices.create(prefix + "-db", "DATABASE", "JDBC",
                Map.of("jdbcUrl", jdbcUrl), Map.of("username", user, "password", pass));
        NodeEntity node = nodes.create(prefix + "-node", prefix, 24);
        TagEntity temp = tags.create(node.getId(), "temperature", "NUMBER", "C", null);
        TagEntity hum = tags.create(node.getId(), "humidity", "NUMBER", "%", null);
        TaskEntity task = taskService.create(node.getId(), device.getId(), "QUERY",
                Map.of("sql", selectSql), "INTERVAL", 3_600_000L, null, 20_000);
        mappings.create(task.getId(), temp.getId(), Map.of("kind", "COLUMN", "expr", "temp_c"), Map.of());
        mappings.create(task.getId(), hum.getId(), Map.of("kind", "COLUMN", "expr", "humidity"), Map.of());

        CollectionResult result = collection.runTaskNow(task.getId());
        assertThat(result.ok()).as("collection: %s / %s", result.status(), result.error()).isTrue();
        assertThat(result.tagCount()).isEqualTo(2);

        String tempKey = prefix + "-node.temperature";
        String humKey = prefix + "-node.humidity";
        assertThat(((Number) collection.currentValue(tempKey).orElseThrow().value()).doubleValue())
                .isEqualTo(21.5);
        assertThat(((Number) collection.currentValue(humKey).orElseThrow().value()).doubleValue())
                .isEqualTo(47.0);

        Snapshot snap = historyStore.snapshotAt(node.getId().toString(), Instant.now(),
                Set.of(tempKey, humKey));
        assertThat(snap.values()).containsKeys(tempKey, humKey);
    }
}
