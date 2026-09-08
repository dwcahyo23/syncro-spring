package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.domain.WhatsAppMessageDirection;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 22-4 unit coverage: template/target derivation per idempotency-key prefix,
 * recipient masking, SHA-256 text reference, and the upsert-vs-insert decision on the
 * notification_job_id anchor.
 */
@ExtendWith(MockitoExtension.class)
class WhatsAppMessageLogServiceTest {

  @Mock
  private WhatsAppMessageLogRepository messageLogs;

  private WhatsAppMessageLogService service;

  private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
  private static final String PHONE = "6281234567890";
  private static final String TEXT = "Peringatan sparepart pada mesin BF-08410";

  @BeforeEach
  void setUp() {
    service = new WhatsAppMessageLogService(messageLogs);
  }

  private static NotificationJobEntity alertJob(UUID alertId) {
    return new NotificationJobEntity(alertId, "TECHNICIAN", NotificationJobStatus.PENDING,
        UUID.randomUUID(), PHONE, alertId + "::TECHNICIAN", "trace-alert", null);
  }

  private static NotificationJobEntity lifecycleJob(String woId, String event, UUID userId) {
    return new NotificationJobEntity(null, "LEADER", NotificationJobStatus.PENDING,
        userId, PHONE, "WORKORDER:" + woId + ":" + event + ":" + userId, "trace-wo", null, "body");
  }

  // --- derivation: alert vs lifecycle vs ack vs sparepart -------------------------

  @Test
  @DisplayName("22.4-UNIT-001 P0 alert job derives ALERT target + alert_notification template")
  void alertJobDerivation() {
    var alertId = UUID.randomUUID();
    var job = alertJob(alertId);

    assertThat(WhatsAppMessageLogService.templateName(job)).isEqualTo("alert_notification");
    assertThat(WhatsAppMessageLogService.targetType(job)).isEqualTo("ALERT");
    assertThat(WhatsAppMessageLogService.targetId(job)).isEqualTo(alertId.toString());
    assertThat(WhatsAppMessageLogService.workOrderId(job)).isNull();
  }

  @Test
  @DisplayName("22.4-UNIT-002 P0 lifecycle jobs sharing woId+user derive distinct templates (DONE vs CLOSED)")
  void lifecycleDistinctTemplates() {
    var userId = UUID.randomUUID();
    var done = lifecycleJob("WO-2609-00001", "DONE", userId);
    var closed = lifecycleJob("WO-2609-00001", "CLOSED", userId);

    assertThat(WhatsAppMessageLogService.templateName(done)).isEqualTo("workorder_lifecycle:DONE");
    assertThat(WhatsAppMessageLogService.templateName(closed))
        .isEqualTo("workorder_lifecycle:CLOSED");
    assertThat(WhatsAppMessageLogService.templateName(done))
        .isNotEqualTo(WhatsAppMessageLogService.templateName(closed));
    assertThat(WhatsAppMessageLogService.targetType(done)).isEqualTo("WORK_ORDER");
    assertThat(WhatsAppMessageLogService.targetId(done)).isEqualTo("WO-2609-00001");
    assertThat(WhatsAppMessageLogService.workOrderId(done)).isEqualTo("WO-2609-00001");
  }

  @Test
  @DisplayName("22.4-UNIT-003 P1 ack + sparepart prefixes derive workorder_ack / sparepart_request:{step}")
  void ackAndSparepartDerivation() {
    var userId = UUID.randomUUID();
    var ack = new NotificationJobEntity(null, "ACK_WAITING", NotificationJobStatus.PENDING,
        userId, PHONE, "WORKORDER_ACK:WO-2609-00001:" + userId, "trace-ack", null, "ack body");
    var sp = new NotificationJobEntity(null, "ACK_WAITING", NotificationJobStatus.PENDING,
        userId, PHONE, "SPAREPART_REQUEST:req-1:ACK_WAITING:" + userId, "trace-sp", null, "sp body");

    assertThat(WhatsAppMessageLogService.templateName(ack)).isEqualTo("workorder_ack");
    assertThat(WhatsAppMessageLogService.targetType(ack)).isEqualTo("WORK_ORDER");
    assertThat(WhatsAppMessageLogService.targetId(ack)).isEqualTo("WO-2609-00001");

    assertThat(WhatsAppMessageLogService.templateName(sp)).isEqualTo("sparepart_request:ACK_WAITING");
    assertThat(WhatsAppMessageLogService.targetType(sp)).isEqualTo("SPAREPART_REQUEST");
    assertThat(WhatsAppMessageLogService.targetId(sp)).isEqualTo("req-1");
    assertThat(WhatsAppMessageLogService.workOrderId(sp)).isNull();
  }

  // --- masking + hashing -----------------------------------------------------------

  @Test
  @DisplayName("22.4-UNIT-004 P0 recipient stored masked only (first3+last3), never raw")
  void recipientMasked() {
    var alertId = UUID.randomUUID();
    when(messageLogs.findByNotificationJobId(any())).thenReturn(Optional.empty());
    when(messageLogs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service.upsertForDispatch(alertJob(alertId), WhatsAppMessageLogStatus.SENT, TEXT, NOW);

    var captor = ArgumentCaptor.forClass(WhatsAppMessageLogEntity.class);
    verify(messageLogs).saveAndFlush(captor.capture());
    var row = captor.getValue();
    assertThat(row.getRecipientMasked()).isEqualTo("628***890");
    assertThat(row.getRecipientMasked()).doesNotContain(PHONE);
  }

  @Test
  @DisplayName("22.4-UNIT-004b P0 short phone (maskPhone returns it unmasked) collapses to ***")
  void shortPhoneNeverStoredRaw() {
    // NotificationHistoryDtos.maskPhone documents <=6 chars returned as-is — a stored
    // evidence column must never carry that (review 22-4 P2).
    assertThat(WhatsAppMessageLogService.maskRecipient("628123")).isEqualTo("***");
    assertThat(WhatsAppMessageLogService.maskRecipient("12345")).isEqualTo("***");
    assertThat(WhatsAppMessageLogService.maskRecipient("  628123  ")).isEqualTo("***");
    assertThat(WhatsAppMessageLogService.maskRecipient(null)).isNull();
    assertThat(WhatsAppMessageLogService.maskRecipient(PHONE)).isEqualTo("628***890");
  }

  @Test
  @DisplayName("22.4-UNIT-005 P0 rendered text stored as SHA-256 hex, never raw; render-fail → null")
  void textSha256() throws Exception {
    var alertId = UUID.randomUUID();
    when(messageLogs.findByNotificationJobId(any())).thenReturn(Optional.empty());
    when(messageLogs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service.upsertForDispatch(alertJob(alertId), WhatsAppMessageLogStatus.SENT, TEXT, NOW);

    var captor = ArgumentCaptor.forClass(WhatsAppMessageLogEntity.class);
    verify(messageLogs).saveAndFlush(captor.capture());
    var expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(TEXT.getBytes(StandardCharsets.UTF_8)));
    assertThat(captor.getValue().getTextSha256()).isEqualTo(expected).hasSize(64);
    assertThat(captor.getValue().getText()).isNull(); // raw text never stored

    var renderFail = alertJob(UUID.randomUUID());
    service.upsertForDispatch(renderFail, WhatsAppMessageLogStatus.FAILED, null, NOW);
    var failCaptor = ArgumentCaptor.forClass(WhatsAppMessageLogEntity.class);
    verify(messageLogs, org.mockito.Mockito.times(2)).saveAndFlush(failCaptor.capture());
    assertThat(failCaptor.getAllValues().get(1).getTextSha256()).isNull();
  }

  // --- upsert vs insert --------------------------------------------------------------

  @Test
  @DisplayName("22.4-UNIT-006 P0 first dispatch inserts one OUTBOUND row anchored to the job")
  void firstDispatchInserts() {
    var alertId = UUID.randomUUID();
    var job = alertJob(alertId);
    when(messageLogs.findByNotificationJobId(job.getId())).thenReturn(Optional.empty());
    when(messageLogs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service.upsertForDispatch(job, WhatsAppMessageLogStatus.SENT, TEXT, NOW);

    var captor = ArgumentCaptor.forClass(WhatsAppMessageLogEntity.class);
    verify(messageLogs).saveAndFlush(captor.capture());
    var row = captor.getValue();
    assertThat(row.getDirection()).isEqualTo(WhatsAppMessageDirection.OUTBOUND);
    assertThat(row.getNotificationJobId()).isEqualTo(job.getId());
    assertThat(row.getIdempotencyKey()).isEqualTo(job.getIdempotencyKey());
    assertThat(row.getStatus()).isEqualTo(WhatsAppMessageLogStatus.SENT);
    assertThat(row.getAttemptCount()).isEqualTo(1);
    assertThat(row.getSentAt()).isEqualTo(NOW);
    assertThat(row.getTraceId()).isEqualTo("trace-alert");
    assertThat(row.getUserId()).isEqualTo(job.getRecipientUserId());
    assertThat(row.getWahaMessageId()).isNull(); // deliberately not parsed
  }

  @Test
  @DisplayName("22.4-UNIT-007 P0 retry updates the SAME row: FAILED→SENT, attempt_count 2, latest sent_at")
  void retryUpdatesSameRow() {
    var alertId = UUID.randomUUID();
    var job = alertJob(alertId);
    var existing = WhatsAppMessageLogEntity.forOutbound(UUID.randomUUID(), job.getId(),
        job.getIdempotencyKey(), "ALERT", alertId.toString(), "alert_notification",
        "628***890", null, job.getRecipientUserId(), "trace-alert", NOW.minusSeconds(60));
    existing.bumpAttempt();
    existing.markFailed("hash");
    when(messageLogs.findByNotificationJobId(job.getId())).thenReturn(Optional.of(existing));
    when(messageLogs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    Instant later = NOW.plusSeconds(120);
    service.upsertForDispatch(job, WhatsAppMessageLogStatus.SENT, TEXT, later);

    assertThat(existing.getStatus()).isEqualTo(WhatsAppMessageLogStatus.SENT);
    assertThat(existing.getAttemptCount()).isEqualTo(2);
    assertThat(existing.getSentAt()).isEqualTo(later);
    assertThat(existing.getTextSha256()).isEqualTo(WhatsAppMessageLogService.sha256Hex(TEXT));
    verify(messageLogs).saveAndFlush(existing);
  }

  @Test
  @DisplayName("22.4-UNIT-008 P1 sha256Hex is stable and null-safe")
  void sha256Stable() {
    assertThat(WhatsAppMessageLogService.sha256Hex(null)).isNull();
    assertThat(WhatsAppMessageLogService.sha256Hex("a"))
        .isEqualTo(WhatsAppMessageLogService.sha256Hex("a"))
        .isNotEqualTo(WhatsAppMessageLogService.sha256Hex("b"));
  }

  @Test
  @DisplayName("22.4-UNIT-009 P2 unknown prefix derives a non-null fallback template")
  void unknownPrefixFallback() {
    var job = new NotificationJobEntity(null, "X", NotificationJobStatus.PENDING,
        UUID.randomUUID(), PHONE, "SOMETHING_ELSE:1", "trace", null, "body");
    assertThat(WhatsAppMessageLogService.templateName(job)).isEqualTo("unknown");
    assertThat(WhatsAppMessageLogService.targetType(job)).isNull();
    assertThat(WhatsAppMessageLogService.targetId(job)).isNull();
  }

  @Test
  @DisplayName("22.4-UNIT-010 P2 insert race surfaces as DIV (worker retry takes the update path)")
  void insertRacePropagates() {
    var alertId = UUID.randomUUID();
    var job = alertJob(alertId);
    when(messageLogs.findByNotificationJobId(job.getId())).thenReturn(Optional.empty());
    when(messageLogs.saveAndFlush(any())).thenThrow(
        new org.springframework.dao.DataIntegrityViolationException("uq anchor"));

    assertThatThrownBy(() ->
        service.upsertForDispatch(job, WhatsAppMessageLogStatus.SENT, TEXT, NOW))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    verify(messageLogs, never()).delete(any());
  }
}
