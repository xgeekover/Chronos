package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Phase 7 load smoke test: many concurrent collections all succeed without errors (§11 성능). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LoadSmokeTest {

    @Autowired DeviceService devices;
    @Autowired NodeService nodes;
    @Autowired TagService tags;
    @Autowired TaskService taskService;
    @Autowired MappingService mappings;
    @Autowired CollectionService collection;

    @Test
    void manyConcurrentCollectionsAllSucceed() throws Exception {
        DeviceEntity device = devices.create("load-script", "HOST", "SCRIPT_JAVA", Map.of(), Map.of());
        NodeEntity node = nodes.create("loadnode", "load smoke", 24);
        TagEntity tag = tags.create(node.getId(), "v", "NUMBER", null, null);
        TaskEntity task = taskService.create(node.getId(), device.getId(), "SCRIPT_JAVA",
                Map.of("script", "return java.util.Map.of(\"v\", 7.0);"), "INTERVAL", 3_600_000L, null, 5_000);
        mappings.create(task.getId(), tag.getId(), Map.of("kind", "COLUMN", "expr", "v"), Map.of());

        int runs = 50;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<CollectionResult>> jobs = java.util.Collections.nCopies(
                    runs, () -> collection.runTaskNow(task.getId()));
            List<Future<CollectionResult>> futures = pool.invokeAll(jobs);
            int ok = 0;
            for (Future<CollectionResult> f : futures) {
                if (f.get().ok()) {
                    ok++;
                }
            }
            assertThat(ok).as("all %d concurrent collections should succeed", runs).isEqualTo(runs);
        } finally {
            pool.shutdownNow();
        }

        assertThat(((Number) collection.currentValue("loadnode.v").orElseThrow().value()).doubleValue())
                .isEqualTo(7.0);
    }
}
