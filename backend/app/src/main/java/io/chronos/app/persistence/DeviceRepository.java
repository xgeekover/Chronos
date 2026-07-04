package io.chronos.app.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {
    boolean existsByName(String name);
}
