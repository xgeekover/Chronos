package io.chronos.app.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectionLogRepository extends JpaRepository<CollectionLogEntity, Long> {

    List<CollectionLogEntity> findByOrderByStartedAtDesc(Pageable pageable);

    List<CollectionLogEntity> findByTaskIdOrderByStartedAtDesc(UUID taskId, Pageable pageable);

    long countByStatus(String status);
}
