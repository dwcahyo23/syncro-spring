package com.syncro.integration.infrastructure.db;

import com.syncro.integration.domain.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code webhook_delivery_logs} (blueprint I4, story 15-2; dispatch in 22-1). */
public interface WebhookDeliveryLogRepository
    extends JpaRepository<WebhookDeliveryLogEntity, UUID> {

  /**
   * Returns up to 10 deliveries ready for dispatch: {@code PENDING} (never attempted) or
   * {@code RETRYING} (failed with a retry scheduled or past its backoff window). Both share
   * the same {@code nextRetryAt} guard so one poll loop handles first sends and retries
   * identically (NotificationJobRepository.findPendingJobsDue precedent; the
   * idx_webhook_delivery_logs_status (status, next_retry_at) index already exists in V1).
   */
  @Query("""
      select d from WebhookDeliveryLogEntity d
      where d.status in :statuses
        and (d.nextRetryAt is null or d.nextRetryAt <= :now)
      order by d.createdAt asc
      limit 10
      """)
  List<WebhookDeliveryLogEntity> findDueDeliveries(
      @Param("statuses") Collection<WebhookDeliveryStatus> statuses,
      @Param("now") Instant now);

  Page<WebhookDeliveryLogEntity> findByWebhookConfigId(UUID webhookConfigId, Pageable pageable);

  Page<WebhookDeliveryLogEntity> findByStatus(WebhookDeliveryStatus status, Pageable pageable);

  /**
   * Duplicate-enqueue pre-check (story 22-1 review P1): the listener checks this before
   * inserting so the common duplicate path never touches the UNIQUE constraint (which
   * would mark its transaction rollback-only and poison sibling configs' rows).
   */
  boolean existsByIdempotencyKey(String idempotencyKey);
}
