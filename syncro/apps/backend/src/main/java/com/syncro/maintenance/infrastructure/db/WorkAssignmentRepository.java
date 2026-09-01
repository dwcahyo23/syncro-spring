package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_assignments} (blueprint B3, AD-17, story 15-2). */
public interface WorkAssignmentRepository extends JpaRepository<WorkAssignmentEntity, UUID> {

  List<WorkAssignmentEntity> findByWorkOrderIdOrderByAssignedAtAsc(String workOrderId);
}
