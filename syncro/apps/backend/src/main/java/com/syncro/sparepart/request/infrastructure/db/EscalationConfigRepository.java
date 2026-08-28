package com.syncro.sparepart.request.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscalationConfigRepository extends JpaRepository<EscalationConfigEntity, UUID> {

  /** Ordered by step so approval-tier matching is deterministic regardless of insertion order. */
  List<EscalationConfigEntity> findByScopeOrderByStepAsc(String scope);
}