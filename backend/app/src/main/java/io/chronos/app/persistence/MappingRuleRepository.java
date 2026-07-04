package io.chronos.app.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRuleRepository extends JpaRepository<MappingRuleEntity, UUID> {
    List<MappingRuleEntity> findByTaskId(UUID taskId);

    List<MappingRuleEntity> findByTagId(UUID tagId);
}
