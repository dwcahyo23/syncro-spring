package com.syncro.maintenance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_order_quality_ratings} (blueprint C4, story 15-2). */
public interface WorkOrderQualityRatingRepository extends JpaRepository<WorkOrderQualityRatingEntity, UUID> {

  Optional<WorkOrderQualityRatingEntity> findByWorkOrderId(String workOrderId);
}
