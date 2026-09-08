package com.syncro.notification.infrastructure;

import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code whatsapp_message_logs} (blueprint I5, story 15-2;
 * outbound evidence reads in story 22-4).
 */
public interface WhatsAppMessageLogRepository extends JpaRepository<WhatsAppMessageLogEntity, UUID> {

  Optional<WhatsAppMessageLogEntity> findByWahaMessageId(String wahaMessageId);

  /**
   * Story 22-4 dedupe anchor: the one OUTBOUND row per logical notification job
   * (uq_whatsapp_message_logs_notification_job_id). The dispatch upsert reads this
   * before inserting so retries update the same row instead of duplicating it.
   */
  Optional<WhatsAppMessageLogEntity> findByNotificationJobId(UUID notificationJobId);

  /**
   * SUPER_ADMIN evidence read (story 22-4): optional status/target/workorder/trace
   * filters, scoped to OUTBOUND rows only (the table is shared with future inbound
   * ingest — the evidence view must not mix them). Ordering lives here rather than in
   * the caller's Sort because Postgres puts NULLs FIRST on DESC: FAILED rows carry a
   * null sent_at and must sort AFTER dated rows, with the always-set logged_at as the
   * tiebreaker (review 22-4 P3). The {@code (:x is null or ...)} pattern mirrors
   * SparepartAlertRepository.
   */
  @Query("""
      select l from WhatsAppMessageLogEntity l
      where l.direction = com.syncro.notification.domain.WhatsAppMessageDirection.OUTBOUND
        and (:status is null or l.status = :status)
        and (:targetType is null or l.targetType = :targetType)
        and (:workOrderId is null or l.workOrderId = :workOrderId)
        and (:traceId is null or l.traceId = :traceId)
      order by l.sentAt desc nulls last, l.loggedAt desc, l.id desc
      """)
  Page<WhatsAppMessageLogEntity> findLogs(
      @Param("status") WhatsAppMessageLogStatus status,
      @Param("targetType") String targetType,
      @Param("workOrderId") String workOrderId,
      @Param("traceId") String traceId,
      Pageable pageable);
}
