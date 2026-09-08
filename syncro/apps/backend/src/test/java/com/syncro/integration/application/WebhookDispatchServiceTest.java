package com.syncro.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.config.WebhookProperties;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import com.syncro.integration.infrastructure.WebhookHttpClient;
import com.syncro.integration.infrastructure.db.WebhookConfigEntity;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Story 22-1 dispatch coverage on a fixed clock: the full status machine
 * (2xx→DELIVERED, 4xx→FAILED terminal, 5xx/timeout→RETRYING with exponential
 * backoff, exhaustion→DLQ, circuit-open→no attempt consumed) plus per-config HMAC
 * signing. The mocked transaction manager lets TransactionTemplate run the state
 * writes inline (NotificationDispatchServiceTest precedent).
 */
@ExtendWith(MockitoExtension.class)
class WebhookDispatchServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-09-06T08:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final UUID CONFIG_ID = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID DELIVERY_ID = UUID.fromString("22222222-3333-4444-5555-666677778888");
  private static final String SECRET = "s3cr3t-signing-key";

  @Mock
  private WebhookDeliveryLogRepository deliveries;
  @Mock
  private WebhookConfigRepository configs;
  @Mock
  private WebhookHttpClient httpClient;
  @Mock
  private PlatformTransactionManager transactionManager;

  private WebhookDispatchService service;

  @BeforeEach
  void setUp() {
    service = new WebhookDispatchService(deliveries, configs, httpClient, new WebhookProperties(),
        FIXED_CLOCK, transactionManager);
  }

  private void stubConfig() {
    stubConfig(true);
  }

  private void stubConfig(boolean active) {
    when(configs.findById(CONFIG_ID)).thenReturn(Optional.of(new WebhookConfigEntity(
        CONFIG_ID, "erp-hook", WebhookDirection.OUTBOUND, List.of("CLOSED"),
        "https://erp.test/hook", SECRET, active, null, FIXED_NOW, FIXED_NOW)));
  }

  private static WebhookDeliveryLogEntity pending(int attemptCount) {
    return new WebhookDeliveryLogEntity(DELIVERY_ID, CONFIG_ID, "CLOSED",
        Map.of("workOrderId", "WO-2609-00001", "eventType", "CLOSED"),
        null, null, null, WebhookDeliveryStatus.PENDING, attemptCount, null,
        "trace-1", 3, CONFIG_ID + ":CLOSED:WO-2609-00001", FIXED_NOW, FIXED_NOW);
  }

  @Test
  @DisplayName("22.1-DSP-001 P0 2xx → DELIVERED with response code, latency, attempt count")
  void twoXxDelivers() {
    stubConfig();
    when(httpClient.post(eq("https://erp.test/hook"), any(), any(), eq("trace-1")))
        .thenReturn(new WebhookHttpClient.Result(true, 204, "no content"));

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    assertThat(delivery.getResponseCode()).isEqualTo(204);
    assertThat(delivery.getAttemptCount()).isEqualTo(1);
    assertThat(delivery.getNextRetryAt()).isNull();
    assertThat(delivery.getLatencyMs()).isNotNull();
    verify(deliveries).save(delivery);
  }

  @Test
  @DisplayName("22.1-DSP-002 P0 4xx → FAILED terminal, no retry scheduled")
  void fourXxFailsTerminally() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 400, "bad request"));

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    assertThat(delivery.getResponseCode()).isEqualTo(400);
    assertThat(delivery.getAttemptCount()).isEqualTo(1);
    assertThat(delivery.getNextRetryAt()).isNull();
  }

  @Test
  @DisplayName("22.1-DSP-003 P0 5xx → RETRYING with exponential backoff min(60*2^n, 3600)s")
  void fiveXxRetriesWithBackoff() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 503, "unavailable"));

    var first = pending(0);
    service.dispatch(first);
    assertThat(first.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(first.getAttemptCount()).isEqualTo(1);
    assertThat(first.getNextRetryAt()).isEqualTo(FIXED_NOW.plusSeconds(60));

    var second = pending(1);
    service.dispatch(second);
    assertThat(second.getNextRetryAt()).isEqualTo(FIXED_NOW.plusSeconds(120));

    // attemptCount=2 is the 3rd attempt of maxAttempts=3 → DLQ, not a 240 s backoff.
    var third = pending(2);
    service.dispatch(third);
    assertThat(third.getStatus()).isEqualTo(WebhookDeliveryStatus.DLQ);
    assertThat(third.getNextRetryAt()).isNull();
  }

  @Test
  @DisplayName("22.1-DSP-004 P0 timeout (httpStatus 0, non-circuit detail) → RETRYING")
  void timeoutRetries() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 0, "read timed out"));

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(delivery.getAttemptCount()).isEqualTo(1);
    assertThat(delivery.getNextRetryAt()).isEqualTo(FIXED_NOW.plusSeconds(60));
  }

  @Test
  @DisplayName("22.1-DSP-005 P0 attempt >= max_attempts → DLQ terminal, next_retry_at null")
  void exhaustionLandsInDlq() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 500, "boom"));

    // attemptCount=2, maxAttempts=3 → this is the 3rd attempt → DLQ
    var delivery = pending(2);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DLQ);
    assertThat(delivery.getAttemptCount()).isEqualTo(3);
    assertThat(delivery.getNextRetryAt()).isNull();
    assertThat(delivery.getResponseCode()).isEqualTo(500);
  }

  @Test
  @DisplayName("22.1-DSP-006 P0 circuit-open skip does NOT consume an attempt")
  void circuitOpenDoesNotConsumeAttempt() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 0, WebhookHttpClient.CIRCUIT_OPEN_DETAIL));

    var delivery = pending(2); // one attempt from exhaustion — CB must not finish it
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(delivery.getAttemptCount()).isEqualTo(2);
    assertThat(delivery.getNextRetryAt()).isEqualTo(FIXED_NOW.plusSeconds(60)); // waitDurationInOpenState
  }

  @Test
  @DisplayName("22.1-DSP-007 P0 payload signed per-config with hex HMAC-SHA256")
  void payloadSignedWithHexHmac() throws Exception {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(true, 200, "ok"));

    service.dispatch(pending(0));

    var bodyCaptor = ArgumentCaptor.forClass(String.class);
    var sigCaptor = ArgumentCaptor.forClass(String.class);
    verify(httpClient).post(eq("https://erp.test/hook"), bodyCaptor.capture(),
        sigCaptor.capture(), eq("trace-1"));

    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    var expected = HexFormat.of().formatHex(mac.doFinal(bodyCaptor.getValue().getBytes(StandardCharsets.UTF_8)));
    assertThat(sigCaptor.getValue()).isEqualTo(expected);
    assertThat(sigCaptor.getValue()).doesNotContain(SECRET);
    assertThat(bodyCaptor.getValue()).contains("WO-2609-00001");
  }

  @Test
  @DisplayName("22.1-DSP-008 P1 missing config (defensive) → DLQ without HTTP call")
  void missingConfigGoesToDlq() {
    when(configs.findById(CONFIG_ID)).thenReturn(Optional.empty());

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DLQ);
    verify(httpClient, never()).post(any(), any(), any(), any());
  }

  @Test
  @DisplayName("22.1-DSP-011 P0 deactivated config → DLQ 'config deactivated', no POST")
  void deactivatedConfigStopsDelivery() {
    stubConfig(false);

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DLQ);
    assertThat(delivery.getResponseBody()).isEqualTo("config deactivated");
    assertThat(delivery.getNextRetryAt()).isNull();
    verify(httpClient, never()).post(any(), any(), any(), any());
  }

  @Test
  @DisplayName("22.1-DSP-012 P1 3xx redirect → terminal FAILED, not retryable")
  void threeXxIsTerminalFailure() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 302, "moved"));

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    assertThat(delivery.getResponseCode()).isEqualTo(302);
    assertThat(delivery.getNextRetryAt()).isNull();
  }

  @Test
  @DisplayName("22.1-DSP-009 P1 response detail stored as-is (client already truncates to 512)")
  void responseBodyStoredTruncated() {
    stubConfig();
    when(httpClient.post(any(), any(), any(), any()))
        .thenReturn(new WebhookHttpClient.Result(false, 422, "x".repeat(512)));

    var delivery = pending(0);
    service.dispatch(delivery);

    assertThat(delivery.getResponseBody()).hasSize(512);
  }

  @Test
  @DisplayName("22.1-DSP-010 P2 signature helper is deterministic and secret-free")
  void signatureHelperBehavior() {
    var sig = WebhookDispatchService.signature("abc", "body");
    assertThat(sig).isEqualTo(WebhookDispatchService.signature("abc", "body"));
    assertThat(sig).hasSize(64).matches("[0-9a-f]+");
    assertThat(sig).doesNotContain("abc");
    // different secret → different signature
    assertThat(WebhookDispatchService.signature("xyz", "body")).isNotEqualTo(sig);
  }
}
