package com.syncro.sparepart.request.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EscalationConfigRepository extends JpaRepository<EscalationConfigEntity, UUID> {

  /**
   * Ordered by min_cost ascending (nulls first) so approval-tier matching and the
   * fail-closed fallback follow cost order, not step-name alphabetical order.
   */
  @Query("""
      select e from EscalationConfigEntity e
      where e.scope = ?1
      order by e.minCost asc nulls first
      """)
  List<EscalationConfigEntity> findByScopeOrderByMinCostAscNullsFirst(String scope);
}