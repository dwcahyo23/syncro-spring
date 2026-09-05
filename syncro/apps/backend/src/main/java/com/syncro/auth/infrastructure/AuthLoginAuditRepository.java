package com.syncro.auth.infrastructure;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code auth_login_audits} (blueprint I2, story 15-2; read surface story 22-2). */
public interface AuthLoginAuditRepository extends JpaRepository<AuthLoginAuditEntity, UUID> {

  /**
   * Filtered paged read (story 22-2 AC2): case-insensitive identifier substring, optional
   * success flag, inclusive occurred-at range. Null filters mean "no constraint"; the caller
   * supplies unbounded sentinels for open-ended ranges (AuditLogRepository.search precedent).
   * Ordering (newest first) comes from the {@link Pageable}.
   */
  @Query("""
      select a from AuthLoginAuditEntity a
      where (:identifier is null or lower(a.identifier) like :identifier escape '\\')
        and (:success is null or a.wasSuccess = :success)
        and a.occurredAt >= :from
        and a.occurredAt <= :to
      """)
  Page<AuthLoginAuditEntity> search(
      @Param("identifier") String identifier,
      @Param("success") Boolean success,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);
}
