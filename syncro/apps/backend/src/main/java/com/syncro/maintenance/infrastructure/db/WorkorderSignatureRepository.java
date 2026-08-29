package com.syncro.maintenance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder-signature persistence (story 14-3, FR-175). One signature per workorder
 * enforced by {@code uq_workorder_signatures_work_order} — a duplicate approve hits the
 * unique constraint and surfaces as a 409 conflict.
 */
public interface WorkorderSignatureRepository extends JpaRepository<WorkorderSignatureEntity, UUID> {

  Optional<WorkorderSignatureEntity> findByWorkOrderId(String workOrderId);

  boolean existsByWorkOrderId(String workOrderId);
}
