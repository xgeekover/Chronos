package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronos.app.persistence.CollectionLogEntity;
import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.service.CollectionLogService;
import io.chronos.app.service.CollectionService;
import io.chronos.app.service.DeviceService;
import io.chronos.app.service.MappingService;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.TagService;
import io.chronos.app.service.TaskService;
import io.chronos.app.web.DashboardController;
import io.chronos.engine.pipeline.CollectionResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Phase 6 E2E: a synthetic-adapter task run (returns 35.0, no external source) flows through the pipeline,
 * the run is logged, and the dashboard reflects the collection activity (§10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class Phase6IntegrationTest {

    @Autowired DeviceService devices;
    @Autowired NodeService nodes;
    @Autowired TagService tags;
    @Autowired TaskService taskService;
    @Autowired MappingService mappings;
    @Autowired CollectionService collection;
    @Autowired CollectionLogService logs;
    @Autowired DashboardController dashboard;

    @Test
    void collectionRunLogsAndDashboard() {
        DeviceEntity device = devices.create("synthetic-e2e", "HOST", "SYNTHETIC", Map.of(), Map.of());
        NodeEntity node = nodes.create("scriptnode", "collection e2e", 24);
        TagEntity temp = tags.create(node.getId(), "temp", "NUMBER", "C", null);

        TaskEntity task = taskService.create(node.getId(), device.getId(), "QUERY",
                Map.of("temp", 35.0), "INTERVAL", 3_600_000L, null, 5_000);
        mappings.create(task.getId(), temp.getId(), Map.of("kind", "COLUMN", "expr", "temp"), Map.of());

        CollectionResult result = collection.runTaskNow(task.getId());
        assertThat(result.ok()).isTrue();
        assertThat(result.tagCount()).isEqualTo(1);

        // collection log written
        List<CollectionLogEntity> recent = logs.recent(task.getId(), 10);
        assertThat(recent).isNotEmpty();
        assertThat(recent.get(0).getStatus()).isEqualTo("OK");

        // dashboard reflects the activity (the test Postgres is shared, so counts are cumulative)
        Map<String, Object> summary = dashboard.summary();
        assertThat((Long) ((Map<?, ?>) summary.get("collections")).get("total")).isGreaterThanOrEqualTo(1L);
    }
}
