package com.syncro.audit.infrastructure;

import com.syncro.audit.domain.AuditEntityType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
  @Query("""
      select entry from AuditLogEntity entry
      where (:entityType is null or entry.entityType = :entityType)
        and (:actor is null or lower(entry.actorName) like :actor)
        and (:plantId is null or entry.plantId = :plantId)
        and entry.createdAt >= :from
        and entry.createdAt <= :to
        and (:unrestricted = true or entry.plantId is null or entry.plantId in :plantIds)
      """)
  Page<AuditLogEntity> search(
      @Param("entityType") AuditEntityType entityType,
      @Param("actor") String actor,
      @Param("plantId") UUID plantId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") List<UUID> plantIds,
      Pageable pageable);
}
