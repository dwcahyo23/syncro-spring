package com.syncro.compliance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code non_conformances} (blueprint H1, story 15-2). */
public interface NonConformanceRepository extends JpaRepository<NonConformanceEntity, UUID> {

  Optional<NonConformanceEntity> findByNcNumber(String ncNumber);
}
