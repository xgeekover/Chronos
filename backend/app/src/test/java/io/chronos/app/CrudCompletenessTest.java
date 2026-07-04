package io.chronos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronos.app.persistence.DeviceEntity;
import io.chronos.app.persistence.NodeEntity;
import io.chronos.app.persistence.TagEntity;
import io.chronos.app.persistence.TaskEntity;
import io.chronos.app.service.DeviceService;
import io.chronos.app.service.MappingService;
import io.chronos.app.service.NodeService;
import io.chronos.app.service.TagService;
import io.chronos.app.service.TaskService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Completeness pass: cascade-safe deletes and entity updates. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrudCompletenessTest {

    @Autowired DeviceService devices;
    @Autowired NodeService nodes;
    @Autowired TagService tags;
    @Autowired TaskService taskService;
    @Autowired MappingService mappings;

    @Test
    void deletingTagCascadesMappings() {
        DeviceEntity device = devices.create("casc-dev", "HOST", "SCRIPT_JAVA", Map.of(), Map.of());
        NodeEntity node = nodes.create("casc-node", "cascade", 24);
        TagEntity tag = tags.create(node.getId(), "t1", "NUMBER", null, null);
        TaskEntity task = taskService.create(node.getId(), device.getId(), "SCRIPT_JAVA",
                Map.of("script", "return java.util.Map.of(\"t1\", 1.0);"), "INTERVAL", 3_600_000L, null, 5_000);
        mappings.create(task.getId(), tag.getId(), Map.of("kind", "COLUMN", "expr", "t1"), Map.of());

        tags.delete(tag.getId());

        assertThat(mappings.listByTask(task.getId())).isEmpty();
    }

    @Test
    void deletingDeviceCascadesTasksAndMappings() {
        DeviceEntity device = devices.create("cascdev-dev", "HOST", "SCRIPT_JAVA", Map.of(), Map.of());
        NodeEntity node = nodes.create("cascdev-node", "cascade dev", 24);
        TagEntity tag = tags.create(node.getId(), "t2", "NUMBER", null, null);
        TaskEntity task = taskService.create(node.getId(), device.getId(), "SCRIPT_JAVA",
                Map.of("script", "return java.util.Map.of(\"t2\", 1.0);"), "INTERVAL", 3_600_000L, null, 5_000);
        mappings.create(task.getId(), tag.getId(), Map.of("kind", "COLUMN", "expr", "t2"), Map.of());

        devices.delete(device.getId());

        assertThat(taskService.list()).noneMatch(t -> t.getId().equals(task.getId()));
        assertThat(mappings.listByTask(task.getId())).isEmpty();
    }

    @Test
    void updateTagRenamesCanonicalKey() {
        NodeEntity node = nodes.create("upd-node", "update", 24);
        TagEntity tag = tags.create(node.getId(), "old", "NUMBER", null, null);

        TagEntity updated = tags.update(tag.getId(), "renamed", "STRING", null, null);

        assertThat(updated.getName()).isEqualTo("renamed");
        assertThat(updated.getCanonicalKey()).isEqualTo(node.getName() + ".renamed");
        assertThat(updated.getDataType()).isEqualTo("STRING");
    }
}
