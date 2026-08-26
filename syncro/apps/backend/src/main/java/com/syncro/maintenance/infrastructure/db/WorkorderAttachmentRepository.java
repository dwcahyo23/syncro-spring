package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Workorder attachment persistence (story 10-5). Lookups are always scoped by
 * work order id so an attachment can never be addressed through the wrong workorder.
 */
public interface WorkorderAttachmentRepository extends JpaRepository<WorkorderAttachmentEntity, UUID> {

  List<WorkorderAttachmentEntity> findByWorkOrderIdOrderByCreatedAtAsc(String workOrderId);

  Optional<WorkorderAttachmentEntity> findByIdAndWorkOrderId(UUID id, String workOrderId);
}
