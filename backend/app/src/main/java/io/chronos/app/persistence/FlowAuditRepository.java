package io.chronos.app.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowAuditRepository extends JpaRepository<FlowAuditEntity, Long> {

    List<FlowAuditEntity> findTop50ByOrderByAtDesc();
}
