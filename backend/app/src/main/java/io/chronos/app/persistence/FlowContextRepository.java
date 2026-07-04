package io.chronos.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowContextRepository extends JpaRepository<FlowContextEntity, String> {}
