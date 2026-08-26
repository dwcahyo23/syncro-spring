package com.syncro.maintenance.infrastructure.db;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repair session persistence (story 10-4). The overlap EXCLUDE constraint is the
 * authoritative backstop; the service pre-checks for friendly 409s.
 */
public interface RepairSessionRepository extends JpaRepository<RepairSessionEntity, UUID> {

  List<RepairSessionEntity> findByWorkOrderIdOrderByStartedAtAsc(String workOrderId);

  Optional<RepairSessionEntity> findFirstByWorkOrderIdAndEndedAtIsNull(String workOrderId);

  @Query("select coalesce(sum(r.durationMinutes), 0) from RepairSessionEntity r "
      + "where r.workOrderId = :workOrderId and r.endedAt is not null")
  Long sumCompletedDuration(@Param("workOrderId") String workOrderId);
}