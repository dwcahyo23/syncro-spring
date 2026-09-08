package com.syncro.integration.application;

import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled outbound-webhook sweep (story 22-1, blueprint I4). Mirrors
 * {@code NotificationWorker}: a fixed-delay poll (own interval property) loads up to
 * 10 due PENDING/RETRYING deliveries and hands each to {@link WebhookDispatchService}.
 * The worker owns every HTTP call — dispatch never runs inline with the request or
 * ingest path — and one row's failure must not stop the rest of the batch.
 */
@Component
public class WebhookDeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

  private static final List<WebhookDeliveryStatus> DISPATCHABLE_STATUSES =
      List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRYING);

  private final WebhookDeliveryLogRepository deliveries;
  private final WebhookDispatchService dispatchService;
  private final Clock clock;

  public WebhookDeliveryWorker(WebhookDeliveryLogRepository deliveries,
      WebhookDispatchService dispatchService, Clock clock) {
    this.deliveries = deliveries;
    this.dispatchService = dispatchService;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${syncro.webhook.worker.poll-interval-ms:30000}")
  public void poll() {
    Instant now = Instant.now(clock);
    var due = deliveries.findDueDeliveries(DISPATCHABLE_STATUSES, now);
    if (due.isEmpty()) {
      return;
    }
    log.info("[WebhookDeliveryWorker] Processing {} due deliveries", due.size());
    int processed = 0;
    for (var delivery : due) {
      try {
        dispatchService.dispatch(delivery);
        processed++;
      } catch (Exception e) {
        log.error("[WebhookDeliveryWorker][traceId={}] Unexpected error dispatching delivery {}: {}",
            delivery.getTraceId(), delivery.getId(), e.getMessage(), e);
      }
    }
    log.info("[WebhookDeliveryWorker] Completed: {}/{} deliveries processed", processed, due.size());
  }
}
