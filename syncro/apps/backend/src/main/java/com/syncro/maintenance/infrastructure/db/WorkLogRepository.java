package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_logs} (blueprint B4, AD-18, story 15-2). */
public interface WorkLogRepository extends JpaRepository<WorkLogEntity, UUID> {

  List<WorkLogEntity> findByWorkOrderIdOrderByStartTimeAsc(String workOrderId);

  long countByWorkOrderIdAndEndTimeIsNotNull(String workOrderId);

  List<WorkLogEntity> findByWorkOrderIdAndEndTimeIsNotNull(String workOrderId);
}
