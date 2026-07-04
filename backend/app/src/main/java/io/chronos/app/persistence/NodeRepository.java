package io.chronos.app.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRepository extends JpaRepository<NodeEntity, UUID> {
    boolean existsByName(String name);
}
