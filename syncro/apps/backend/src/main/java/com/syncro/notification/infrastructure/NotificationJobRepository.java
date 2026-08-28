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

  long countByStatusIn(Collection<NotificationJobStatus> statuses);

  @Query("""
      select j from NotificationJobEntity j
      where j.alertId = :alertId
      order by case j.escalationLevel
        when 'TECHNICIAN' then 1
        when 'STAFF' then 2
        when 'LEADER' then 3
        when 'SPV' then 4
        when 'MANAGER' then 5
        else 99 end asc, j.createdAt asc
      """)
  List<NotificationJobEntity> findByAlertIdOrderByEscalationOrder(@Param("alertId") UUID alertId);

  /**
   * Returns up to 10 jobs that are ready for dispatch: either {@code PENDING} (never attempted
   * or failed with a retry scheduled) or {@code RATE_LIMITED} (suppressed within the deduplication
   * window but now past their {@code nextAttemptAt}).
   *
   * <p>Both statuses share the same {@code nextAttemptAt} guard so the single poll loop handles
   * normal retries and post-rate-limit retries identically.
   */
  @Query("""
      select j from NotificationJobEntity j
      where j.status in :statuses
        and (j.nextAttemptAt is null or j.nextAttemptAt <= :now)
      order by j.createdAt asc
      limit 10
      """)
  List<NotificationJobEntity> findPendingJobsDue(
      @Param("statuses") java.util.Collection<NotificationJobStatus> statuses,
      @Param("now") Instant now);

  @Query("""
      select j from NotificationJobEntity j
      join SparepartAlertEntity a on a.id = j.alertId
      where j.status = :status
        and j.sentAt <= :cutoff
        and a.status = com.syncro.alert.domain.SparepartAlertStatus.OPEN
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

  /**
   * Cancel all non-terminal notification jobs for a sparepart request in one statement
   * (story 12-3, FR-147 ack-stop). Matches the request's escalation jobs by the
   * idempotency-key prefix {@code SPAREPART_REQUEST:{requestId}:%} — the idempotency
   * key is the request handle (AD-9 polymorphic target columns are deliberately NOT
   * introduced here). Called from {@code SparepartRequestService.transition()} when a
   * request transitions to ACKED or CLOSED.
   *
   * <p>Same {@code @Version}-bypass tradeoff as {@link #cancelActiveForAlert}: the bulk
   * update must not race a dispatch into SENT; the idempotency-key unique constraint
   * prevents duplicate logical sends.
   *
   * @return the number of rows cancelled
   */
  @Modifying
  @Query("""
      update NotificationJobEntity j
      set j.status = :cancelled, j.nextAttemptAt = null, j.updatedAt = :now
      where j.idempotencyKey like :requestKeyPrefix
        and j.status in :activeStatuses
      """)
  int cancelActiveForRequest(
      @Param("requestKeyPrefix") String requestKeyPrefix,
      @Param("activeStatuses") Collection<NotificationJobStatus> activeStatuses,
      @Param("cancelled") NotificationJobStatus cancelled,
      @Param("now") Instant now);

  /**
   * Returns the most recently updated non-CANCELLED job for each alertId in the
   * given collection. Uses a subquery on MAX(updatedAt) per alertId to select
   * the single representative row without window functions.
   *
   * <p>Called from {@code SparepartAlertQueryService.list()} to batch-populate
   * notification summaries without issuing N+1 queries per alert row.
   */
  @Query("""
      select j from NotificationJobEntity j
      where j.alertId in :alertIds
        and j.status <> :cancelled
        and j.updatedAt = (
          select max(j2.updatedAt) from NotificationJobEntity j2
          where j2.alertId = j.alertId
            and j2.status <> :cancelled
        )
      """)
  List<NotificationJobEntity> findMostRecentNonCancelledJobsForAlerts(
      @Param("alertIds") Collection<UUID> alertIds,
      @Param("cancelled") NotificationJobStatus cancelled);

  /**
   * Fallback: returns the most recently updated job (any status) for each
   * alertId in the given collection. Used when an alert has only CANCELLED jobs.
   */
  @Query("""
      select j from NotificationJobEntity j
      where j.alertId in :alertIds
        and j.updatedAt = (
          select max(j2.updatedAt) from NotificationJobEntity j2
          where j2.alertId = j.alertId
        )
      """)
  List<NotificationJobEntity> findMostRecentJobsForAlerts(
      @Param("alertIds") Collection<UUID> alertIds);
}