package io.chronos.app.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagEntity, UUID> {
    List<TagEntity> findByNodeId(UUID nodeId);

    boolean existsByNodeIdAndName(UUID nodeId, String name);

    Optional<TagEntity> findByCanonicalKey(String canonicalKey);
}
