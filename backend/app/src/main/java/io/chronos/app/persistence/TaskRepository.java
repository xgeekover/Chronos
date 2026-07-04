package io.chronos.app.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
    List<TaskEntity> findByEnabledTrue();

    List<TaskEntity> findByNodeId(UUID nodeId);

    List<TaskEntity> findByDeviceId(UUID deviceId);
}
