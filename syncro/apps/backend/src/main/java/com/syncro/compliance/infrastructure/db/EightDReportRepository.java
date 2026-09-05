package com.syncro.compliance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code eight_d_reports} (blueprint H2, story 15-2). */
public interface EightDReportRepository extends JpaRepository<EightDReportEntity, UUID> {

  Optional<EightDReportEntity> findByNcId(UUID ncId);

  Optional<EightDReportEntity> findByReportNumber(String reportNumber);

  boolean existsByReportNumber(String reportNumber);
}
