package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_executions} (blueprint F7, story 15-2). */
public interface PmExecutionRepository extends JpaRepository<PmExecutionEntity, UUID> {

  Optional<PmExecutionEntity> findByPmWoId(UUID pmWoId);
}
