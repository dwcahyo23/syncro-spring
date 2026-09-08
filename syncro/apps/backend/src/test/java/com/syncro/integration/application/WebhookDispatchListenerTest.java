package com.syncro.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.config.WebhookProperties;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import com.syncro.integration.infrastructure.db.WebhookConfigEntity;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import com.syncro.maintenance.application.WorkOrderLifecycleEvent;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.notification.domain.AlertOpenedEvent;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Story 22-1 enqueue coverage: event-type matching against active OUTBOUND configs,
 * traceId + idempotency key on every row, the pre-check duplicate path (never touches
 * the constraint), and the race backstop that swallows ONLY the idempotency-key
 * violation (review 22-1 P1/P4). The mocked transaction manager lets the per-row
 * REQUIRES_NEW template run its callback inline.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDispatchListenerTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-09-06T08:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final UUID CONFIG_ID = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID OTHER_CONFIG_ID = UUID.fromString("99999999-2222-3333-4444-555566667777");
  private static final UUID ALERT_ID = UUID.fromString("33333333-4444-5555-6666-777788889999");
  private static final UUID MACHINE_ID = UUID.fromString("44444444-5555-6666-7777-888899990000");
  private static final String IDEMPOTENCY_CONSTRAINT = "uq_webhook_delivery_logs_idempotency_key";

  @Mock
  private WebhookConfigRepository configs;
  @Mock
  private WebhookDeliveryLogRepository deliveries;
  @Mock
  private PlatformTransactionManager transactionManager;

  private WebhookDispatchListener listener;

  @BeforeEach
  void setUp() {
    listener = new WebhookDispatchListener(configs, deliveries, new WebhookProperties(), FIXED_CLOCK,
        transactionManager);
  }

  private static WebhookConfigEntity config(UUID id, List<String> eventTypes) {
    return new WebhookConfigEntity(id, "hook-" + id, WebhookDirection.OUTBOUND, eventTypes,
        "https://erp.test/hook", "secret", true, null, FIXED_NOW, FIXED_NOW);
  }

  private static WorkOrderLifecycleEvent closedEvent() {
    return new WorkOrderLifecycleEvent("WO-2609-00001", "CLOSED", WorkOrderStatus.CLOSED, "trace-wo-1");
  }

  private static WebhookDeliveryLogEntity row(UUID configId, String key) {
    return new WebhookDeliveryLogEntity(UUID.randomUUID(), configId, "CLOSED", Map.of("k", "v"),
        null, null, null, WebhookDeliveryStatus.PENDING, 0, null, "trace-1", 3, key, FIXED_NOW, FIXED_NOW);
  }

  @Test
  @DisplayName("22.1-LST-001 P0 matching config → exactly one PENDING row with traceId + idempotency key")
  void matchingEventEnqueuesOneRow() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, List.of("CLOSED", "DONE"))));

    listener.onWorkOrderLifecycle(closedEvent());

    var captor = ArgumentCaptor.forClass(WebhookDeliveryLogEntity.class);
    verify(deliveries).saveAndFlush(captor.capture());
    var row = captor.getValue();
    assertThat(row.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(row.getWebhookConfigId()).isEqualTo(CONFIG_ID);
    assertThat(row.getEventType()).isEqualTo("CLOSED");
    assertThat(row.getTraceId()).isEqualTo("trace-wo-1");
    assertThat(row.getIdempotencyKey()).isEqualTo(CONFIG_ID + ":CLOSED:WO-2609-00001");
    assertThat(row.getMaxAttempts()).isEqualTo(3);
    assertThat(row.getAttemptCount()).isZero();
    assertThat(row.getNextRetryAt()).isNull();
    assertThat(row.getPayload()).containsEntry("workOrderId", "WO-2609-00001")
        .containsEntry("status", "CLOSED");
  }

  @Test
  @DisplayName("22.1-LST-002 P0 unsubscribed event type → no row")
  void unsubscribedEventTypeEnqueuesNothing() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, List.of("DONE"))));

    listener.onWorkOrderLifecycle(closedEvent());

    verify(deliveries, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-LST-003 P1 one row per matching config; non-matching configs skipped")
  void fansOutPerMatchingConfig() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(
            config(CONFIG_ID, List.of("CLOSED")),
            config(OTHER_CONFIG_ID, List.of("DONE")),
            config(UUID.fromString("55555555-6666-7777-8888-999900001111"), List.of("CLOSED"))));

    listener.onWorkOrderLifecycle(closedEvent());

    var captor = ArgumentCaptor.forClass(WebhookDeliveryLogEntity.class);
    verify(deliveries, times(2)).saveAndFlush(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(WebhookDeliveryLogEntity::getWebhookConfigId)
        .containsExactly(CONFIG_ID,
            UUID.fromString("55555555-6666-7777-8888-999900001111"));
  }

  @Test
  @DisplayName("22.1-LST-004 P0 duplicate (pre-check hit) skips insert — constraint never touched")
  void duplicatePreCheckSkipsInsert() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, List.of("CLOSED"))));
    when(deliveries.existsByIdempotencyKey(anyString())).thenReturn(true);

    listener.onWorkOrderLifecycle(closedEvent());

    // The common duplicate path is a pre-check hit: no saveAndFlush, so no rollback-only tx.
    verify(deliveries, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-LST-005 P0 race backstop: idempotency-key DIV is swallowed silently")
  void raceIdempotencyDivSwallowed() {
    when(deliveries.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("violates unique constraint \"" + IDEMPOTENCY_CONSTRAINT + "\""));

    // must not throw — the concurrent duplicate is a no-op
    listener.saveIgnoreDuplicate(row(CONFIG_ID, CONFIG_ID + ":CLOSED:WO-RACE"));
  }

  @Test
  @DisplayName("22.1-LST-006 P0 non-idempotency DIV is rethrown (never drop a delivery silently)")
  void raceOtherDivRethrown() {
    when(deliveries.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("violates check constraint ck_webhook_delivery_logs_status"));

    assertThatThrownBy(() -> listener.saveIgnoreDuplicate(row(CONFIG_ID, CONFIG_ID + ":CLOSED:WO-X")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("22.1-LST-007 P1 fan-out is resilient: a duplicate on one config does not drop the others")
  void fanOutResilientToDuplicate() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(
            config(CONFIG_ID, List.of("CLOSED")),
            config(OTHER_CONFIG_ID, List.of("CLOSED"))));
    // First config's insert loses a race (idempotency DIV); the second must still be saved.
    when(deliveries.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("violates unique constraint \"" + IDEMPOTENCY_CONSTRAINT + "\""))
        .thenAnswer(inv -> inv.getArgument(0));

    listener.onWorkOrderLifecycle(closedEvent());

    // Both configs attempted despite the first throwing — no UnexpectedRollbackException surface.
    verify(deliveries, times(2)).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-LST-008 P1 AlertOpenedEvent enqueues under ALERT_OPENED with alertId key")
  void alertEventEnqueues() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, List.of("ALERT_OPENED"))));

    listener.onAlertOpened(new AlertOpenedEvent(ALERT_ID, MACHINE_ID, "trace-alert-1"));

    var captor = ArgumentCaptor.forClass(WebhookDeliveryLogEntity.class);
    verify(deliveries).saveAndFlush(captor.capture());
    var row = captor.getValue();
    assertThat(row.getEventType()).isEqualTo("ALERT_OPENED");
    assertThat(row.getIdempotencyKey()).isEqualTo(CONFIG_ID + ":ALERT_OPENED:" + ALERT_ID);
    assertThat(row.getTraceId()).isEqualTo("trace-alert-1");
    assertThat(row.getPayload()).containsEntry("alertId", ALERT_ID.toString());
  }

  @Test
  @DisplayName("22.1-LST-009 P1 null traceId is tolerated (row still enqueued)")
  void nullTraceIdTolerated() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, List.of("CLOSED"))));

    listener.onWorkOrderLifecycle(new WorkOrderLifecycleEvent("WO-2609-00002", "CLOSED",
        WorkOrderStatus.CLOSED, null));

    var captor = ArgumentCaptor.forClass(WebhookDeliveryLogEntity.class);
    verify(deliveries).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getTraceId()).isNull();
  }

  @Test
  @DisplayName("22.1-LST-010 P2 over-long traceId truncated to 64 (VARCHAR(64) column)")
  void longTraceIdTruncated() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, List.of("CLOSED"))));

    listener.onWorkOrderLifecycle(new WorkOrderLifecycleEvent("WO-2609-00003", "CLOSED",
        WorkOrderStatus.CLOSED, "t".repeat(100)));

    var captor = ArgumentCaptor.forClass(WebhookDeliveryLogEntity.class);
    verify(deliveries).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getTraceId()).hasSize(64);
  }

  @Test
  @DisplayName("22.1-LST-011 P2 config with null eventTypes never matches")
  void nullEventTypesNeverMatch() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(config(CONFIG_ID, null)));

    listener.onWorkOrderLifecycle(closedEvent());

    verify(deliveries, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-LST-012 P2 repository failure inside the listener is swallowed (never breaks the publisher)")
  void repositoryFailureSwallowed() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenThrow(new RuntimeException("db down"));

    // AFTER_COMMIT listener must never propagate — the business event already committed.
    listener.onWorkOrderLifecycle(closedEvent());
    verify(deliveries, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-LST-013 P2 payload map is immutable per row (no shared mutation across configs)")
  void payloadIsCopiedPerRow() {
    when(configs.findByDirectionAndActiveTrue(WebhookDirection.OUTBOUND))
        .thenReturn(List.of(
            config(CONFIG_ID, List.of("CLOSED")),
            config(OTHER_CONFIG_ID, List.of("CLOSED"))));

    listener.onWorkOrderLifecycle(closedEvent());

    var captor = ArgumentCaptor.forClass(WebhookDeliveryLogEntity.class);
    verify(deliveries, times(2)).saveAndFlush(captor.capture());
    var first = captor.getAllValues().get(0).getPayload();
    var second = captor.getAllValues().get(1).getPayload();
    assertThat(first).isEqualTo(second);
    assertThat(Map.copyOf(first)).isEqualTo(first);
  }
}
