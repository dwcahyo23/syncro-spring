package com.syncro.notification.infrastructure;

import com.syncro.notification.domain.NotificationJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationJobRepository extends JpaRepository<NotificationJobEntity, UUID> {

  @Query("""
      select j from NotificationJobEntity j
      where j.status = :status
        and (j.nextAttemptAt is null or j.nextAttemptAt <= :now)
      order by j.createdAt asc
      limit 10
      """)
  List<NotificationJobEntity> findPendingJobsDue(
      @Param("status") NotificationJobStatus status,
      @Param("now") Instant now);

  @Query("""
      select j from NotificationJobEntity j
      where j.status = 'SENT'
        and j.sentAt <= :cutoff
      order by j.sentAt asc
      limit 10
      """)
  List<NotificationJobEntity> findSentJobsDueForEscalation(
      @Param("cutoff") Instant cutoff);
}