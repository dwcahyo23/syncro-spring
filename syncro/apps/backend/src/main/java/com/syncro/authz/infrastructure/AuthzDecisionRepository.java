package com.syncro.authz.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthzDecisionRepository extends JpaRepository<AuthzDecisionEntity, UUID> {

  /** Newest-first page for the decision-log read view. */
  Page<AuthzDecisionEntity> findAllByOrderByDecidedAtDesc(Pageable pageable);

  /** Bulk-deletes rows older than the retention cutoff (purge job); returns the deleted count. */
  @Modifying
  @Query("delete from AuthzDecisionEntity d where d.decidedAt < :cutoff")
  long deleteByDecidedAtBefore(@Param("cutoff") Instant cutoff);
}