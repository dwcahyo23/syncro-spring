package com.syncro.notification.infrastructure;

import com.syncro.notification.domain.NotificationJobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationJobRepository extends JpaRepository<NotificationJobEntity, UUID> {

  List<NotificationJobEntity> findByAlertIdOrderByCreatedAtAsc(UUID alertId);

  @Query("""
      select j from NotificationJobEntity j
      where j.alertId = :alertId
      order by case j.escalationLevel
        when 'TECHNICIAN' then 1
        when 'STAFF' then 2
        when 'LEADER' then 3
        when 'SPV' then 4
        when 'MANAGER' then 5
        else 99 end asc
      """)
  List<NotificationJobEntity> findByAlertIdOrderByEscalationOrder(@Param("alertId") UUID alertId);

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
      where j.status = :status
        and j.sentAt <= :cutoff
      order by j.sentAt asc
      limit 10
      """)
  List<NotificationJobEntity> findSentJobsDueForEscalation(
      @Param("status") NotificationJobStatus status,
      @Param("cutoff") Instant cutoff);

  /**
   * Cancel all non-terminal notification jobs for an alert in one statement.
   *
   * <p>Called from {@code SparepartAlertCommandService.acknowledge()} so that
   * acknowledgement stops escalation for that alert in the same transaction —
   * {@code PENDING} jobs can no longer be dispatched to WAHA and {@code SENT}
   * jobs can no longer be escalated by {@code EscalationWorker}.
   *
   * <p>Terminal statuses ({@code ESCALATED}, {@code EXHAUSTED},
   * {@code ROUTING_FAILED}, {@code CANCELLED}) are intentionally not matched.
   *
   * <p>The bulk update bypasses the entity {@code @Version} optimistic lock.
   * This is acceptable here: a job being cancelled must not race into SENT;
   * the DB unique constraint on {@code (alert_id, escalation_level)} and the
   * workers' fresh re-reads prevent duplicate logical sends. Documented
   * tradeoff — do not "fix" this by loading and saving each row.
   *
   * @return the number of rows cancelled
   */
  @Modifying
  @Query("""
      update NotificationJobEntity j
      set j.status = :cancelled, j.nextAttemptAt = null, j.updatedAt = :now
      where j.alertId = :alertId
        and j.status in :activeStatuses
      """)
  int cancelActiveForAlert(
      @Param("alertId") UUID alertId,
      @Param("activeStatuses") Collection<NotificationJobStatus> activeStatuses,
      @Param("cancelled") NotificationJobStatus cancelled,
      @Param("now") Instant now);
}