package com.syncro.sparepart.request.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Per-request timeline persistence (FR-141, story 12-2). Rows are written on every
 * status transition or MRE code recording for operational evidence.
 */
public interface SparepartRequestTimelineRepository extends JpaRepository<SparepartRequestTimelineEntity, UUID> {

  List<SparepartRequestTimelineEntity> findByRequestIdOrderByCreatedAtAsc(UUID requestId);
}