package io.chronos.app.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowDefRepository extends JpaRepository<FlowDefEntity, String> {

    List<FlowDefEntity> findByOwnerOrderByUpdatedAtDesc(String owner);

    Optional<FlowDefEntity> findByIdAndOwner(String id, String owner);
}
