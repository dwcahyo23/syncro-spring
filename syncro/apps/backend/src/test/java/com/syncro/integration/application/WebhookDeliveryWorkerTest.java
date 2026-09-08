package com.syncro.integration.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 22-1 worker coverage (mirrors NotificationWorkerTest): the sweep loads due rows
 * and dispatches each, and one row's failure must not stop the rest of the batch — the
 * per-row isolation claim in the worker javadoc (review 22-1 P8).
 */
@ExtendWith(MockitoExtension.class)
class WebhookDeliveryWorkerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-09-06T08:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  @Mock
  private WebhookDeliveryLogRepository deliveries;
  @Mock
  private WebhookDispatchService dispatchService;

  private WebhookDeliveryWorker worker;

  @BeforeEach
  void setUp() {
    worker = new WebhookDeliveryWorker(deliveries, dispatchService, FIXED_CLOCK);
  }

  private static WebhookDeliveryLogEntity row() {
    return new WebhookDeliveryLogEntity(UUID.randomUUID(), UUID.randomUUID(), "CLOSED",
        Map.of("k", "v"), null, null, null, WebhookDeliveryStatus.PENDING, 0, null,
        "trace-1", 3, "key-" + UUID.randomUUID(), FIXED_NOW, FIXED_NOW);
  }

  @Test
  @DisplayName("22.1-WKR-001 P0 a dispatch failure on one row does not stop the batch")
  void perRowIsolationOnDispatchFailure() {
    var first = row();
    var second = row();
    when(deliveries.findDueDeliveries(any(), any())).thenReturn(List.of(first, second));
    doThrow(new RuntimeException("boom")).when(dispatchService).dispatch(first);

    worker.poll();

    // Both rows dispatched despite the first throwing — the worker catches per row.
    verify(dispatchService).dispatch(first);
    verify(dispatchService, times(1)).dispatch(second);
  }

  @Test
  @DisplayName("22.1-WKR-002 P1 empty sweep dispatches nothing")
  void emptySweepDispatchesNothing() {
    when(deliveries.findDueDeliveries(any(), any())).thenReturn(List.of());

    worker.poll();

    verify(dispatchService, times(0)).dispatch(any());
  }
}
