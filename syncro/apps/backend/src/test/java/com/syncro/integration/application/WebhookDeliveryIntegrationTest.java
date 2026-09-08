package com.syncro.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.integration.api.IntegrationDtos.CreateWebhookConfigRequest;
import com.syncro.integration.api.IntegrationDtos.DeliveryListResponse;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import com.syncro.integration.infrastructure.db.WebhookConfigEntity;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogEntity;
import com.syncro.integration.infrastructure.db.WebhookDeliveryLogRepository;
import com.syncro.maintenance.application.WorkOrderLifecycleEvent;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.notification.domain.AlertOpenedEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 22-1 end-to-end proof against a real PostgreSQL (V19 applied by Flyway):
 * V19 columns round-trip and the idempotency UNIQUE is DB-enforced; a committed
 * event enqueues exactly one PENDING row per matching active OUTBOUND config
 * (traceId + idempotency key carried) and a duplicate event adds nothing; the
 * worker sweep dispatches against a real stub HTTP server — 2xx→DELIVERED,
 * 4xx→FAILED terminal, 5xx→RETRYING then DLQ on exhaustion — with the
 * X-Syncro-Signature header verified as a 64-char hex HMAC.
 *
 * <p>Rows the listener must see are committed through REQUIRES_NEW templates (the
 * listener's own REQUIRES_NEW transaction cannot read the test transaction's
 * uncommitted data). The scheduled poll is pushed to 1 hour so only explicit
 * {@code worker.poll()} calls dispatch — no background race.
 */
@TestPropertySource(properties = "syncro.webhook.worker.poll-interval-ms=3600000")
class WebhookDeliveryIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-06T08:00:00Z");
  private static final String SECRET = "integration-hmac-secret";

  @Autowired
  private WebhookConfigRepository configs;
  @Autowired
  private WebhookDeliveryLogRepository deliveries;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private ApplicationEventPublisher events;
  @Autowired
  private WebhookDispatchListener listener;
  @Autowired
  private WebhookDispatchService dispatchService;
  @Autowired
  private WebhookDeliveryWorker worker;
  @Autowired
  private WebhookConfigService configService;
  @Autowired
  private WebhookDeliveryQueryService deliveryQueryService;
  @Autowired
  private com.syncro.integration.infrastructure.WebhookHttpClient httpClient;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private PlatformTransactionManager transactionManager;

  // One stub server for the whole class (static state): per-test servers would leave
  // earlier tests' committed rows pointing at dead ports, and the resulting
  // connection-refused storm opens the shared singleton circuit breaker mid-sweep.
  // Tests run sequentially (no parallelism configured), so the statics are safe.
  private static HttpServer stubServer;
  private static int stubPort;
  private static final AtomicReference<Integer> stubResponseStatus = new AtomicReference<>(200);
  private static final List<String> receivedBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final List<String> receivedSignatures = new java.util.concurrent.CopyOnWriteArrayList<>();

  @org.junit.jupiter.api.BeforeAll
  static void startStubServer() throws IOException {
    stubServer = HttpServer.create(new InetSocketAddress(0), 0);
    stubServer.createContext("/hook", exchange -> {
      try {
        receivedSignatures.add(exchange.getRequestHeaders().getFirst("X-Syncro-Signature"));
        receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(stubResponseStatus.get(), -1);
      } finally {
        exchange.close();
      }
    });
    stubServer.start();
    stubPort = stubServer.getAddress().getPort();
  }

  @org.junit.jupiter.api.AfterAll
  static void stopStubServer() {
    if (stubServer != null) {
      stubServer.stop(0);
    }
  }

  @BeforeEach
  void resetStub() {
    stubResponseStatus.set(200);
    // The breaker is a singleton shared across the context; a prior test's 5xx
    // sequence must not fast-fail this test's dispatches.
    httpClient.getCircuitBreaker().transitionToClosedState();
  }

  private TransactionTemplate requiresNew() {
    var template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  private String stubUrl() {
    return "http://localhost:" + stubPort + "/hook";
  }

  /** Committed config (visible to the listener's REQUIRES_NEW transaction). */
  private WebhookConfigEntity committedConfig(List<String> eventTypes) {
    return requiresNew().execute(status -> configs.saveAndFlush(new WebhookConfigEntity(
        UUID.randomUUID(), "hook-" + UUID.randomUUID(), WebhookDirection.OUTBOUND, eventTypes,
        stubUrl(), SECRET, true, null, T0, T0)));
  }

  /** Row seeded inside the test transaction (dispatch reads join it). */
  private WebhookDeliveryLogEntity pendingRow(UUID configId, int attemptCount, String key) {
    return deliveries.saveAndFlush(new WebhookDeliveryLogEntity(
        UUID.randomUUID(), configId, "CLOSED",
        Map.of("eventType", "CLOSED", "workOrderId", "WO-IT-" + key),
        null, null, null, WebhookDeliveryStatus.PENDING, attemptCount, null,
        "trace-it-1", 3, key, T0, T0));
  }

  /** Row with an explicit createdAt + status for the read-path ordering/filter tests. */
  private WebhookDeliveryLogEntity seedRow(UUID configId, String eventType,
      WebhookDeliveryStatus status, Instant createdAt, String key) {
    return deliveries.saveAndFlush(new WebhookDeliveryLogEntity(
        UUID.randomUUID(), configId, eventType, Map.of("k", "v"),
        null, null, null, status, 0, null, "trace-read", 3, key, createdAt, createdAt));
  }

  private long rowsFor(UUID configId, String woId) {
    return deliveries.findAll().stream()
        .filter(d -> d.getWebhookConfigId().equals(configId))
        .filter(d -> woId.equals(d.getPayload().get("workOrderId")))
        .count();
  }

  // --- V19 schema evidence -----------------------------------------------------

  @Test
  @DisplayName("22.1-INT-001 P0 V19 columns round-trip: traceId, maxAttempts, idempotencyKey, version")
  void v19ColumnsRoundTrip() {
    var config = committedConfig(List.of("CLOSED"));
    var row = pendingRow(config.getId(), 0, config.getId() + ":CLOSED:WO-V19");

    var reloaded = deliveries.findById(row.getId()).orElseThrow();
    assertThat(reloaded.getTraceId()).isEqualTo("trace-it-1");
    assertThat(reloaded.getMaxAttempts()).isEqualTo(3);
    assertThat(reloaded.getIdempotencyKey()).endsWith(":CLOSED:WO-V19");
    assertThat(reloaded.getVersion()).isZero();

    var reloadedConfig = configs.findById(config.getId()).orElseThrow();
    assertThat(reloadedConfig.getVersion()).isZero();
  }

  @Test
  @DisplayName("22.1-INT-002 P0 duplicate idempotency key is rejected by the DB constraint")
  void idempotencyKeyUniqueEnforced() {
    var config = committedConfig(List.of("CLOSED"));
    var key = config.getId() + ":CLOSED:WO-DUP";
    requiresNew().executeWithoutResult(status -> deliveries.saveAndFlush(new WebhookDeliveryLogEntity(
        UUID.randomUUID(), config.getId(), "CLOSED", Map.of(),
        null, null, null, WebhookDeliveryStatus.PENDING, 0, null,
        "trace-dup", 3, key, T0, T0)));

    assertThatThrownBy(() -> requiresNew().executeWithoutResult(status ->
        deliveries.saveAndFlush(new WebhookDeliveryLogEntity(
            UUID.randomUUID(), config.getId(), "CLOSED", Map.of(),
            null, null, null, WebhookDeliveryStatus.PENDING, 0, null,
            "trace-dup", 3, key, T0, T0))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- enqueue on event after commit ----------------------------------------------

  @Test
  @DisplayName("22.1-INT-003 P0 committed event enqueues exactly one PENDING row with traceId + key")
  void enqueueOnEventAfterCommit() {
    var config = committedConfig(List.of("CLOSED"));
    var woId = "WO-IT-" + UUID.randomUUID();

    requiresNew().executeWithoutResult(status -> events.publishEvent(
        new WorkOrderLifecycleEvent(woId, "CLOSED", WorkOrderStatus.CLOSED, "trace-enqueue-1")));

    // AFTER_COMMIT listener ran in its own transaction — the row is committed.
    var rows = deliveries.findAll().stream()
        .filter(d -> d.getWebhookConfigId().equals(config.getId()))
        .filter(d -> woId.equals(d.getPayload().get("workOrderId")))
        .toList();
    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(row.getTraceId()).isEqualTo("trace-enqueue-1");
    assertThat(row.getIdempotencyKey()).isEqualTo(config.getId() + ":CLOSED:" + woId);
    assertThat(row.getMaxAttempts()).isEqualTo(3);
  }

  @Test
  @DisplayName("22.1-INT-004 P0 duplicate event through the bus adds nothing and never throws")
  void duplicateEventAddsNothing() {
    var config = committedConfig(List.of("CLOSED"));
    var woId = "WO-IT-" + UUID.randomUUID();
    var event = new WorkOrderLifecycleEvent(woId, "CLOSED", WorkOrderStatus.CLOSED, "trace-dup-2");

    // First publish through the event bus inside a committed transaction.
    requiresNew().executeWithoutResult(status -> events.publishEvent(event));
    assertThat(rowsFor(config.getId(), woId)).isEqualTo(1);

    // Review 22-1 P1: the second publish must complete WITHOUT exception (the pre-check
    // keeps the duplicate off the constraint, so no rollback-only transaction leaks an
    // UnexpectedRollbackException to the caller) and add no row.
    requiresNew().executeWithoutResult(status -> events.publishEvent(event));
    assertThat(rowsFor(config.getId(), woId)).isEqualTo(1);
  }

  @Test
  @DisplayName("22.1-INT-005 P1 unsubscribed event type enqueues nothing")
  void unsubscribedEventTypeNoRow() {
    var config = committedConfig(List.of("DONE"));
    var woId = "WO-IT-" + UUID.randomUUID();

    listener.onWorkOrderLifecycle(new WorkOrderLifecycleEvent(woId, "CLOSED",
        WorkOrderStatus.CLOSED, "trace-unsub"));

    assertThat(rowsFor(config.getId(), woId)).isZero();
  }

  // --- sweep dispatch vs stub server ----------------------------------------------

  @Test
  @DisplayName("22.1-INT-006 P0 worker sweep dispatches to the live stub → DELIVERED, signed")
  void sweepDispatchesDelivered() {
    var config = committedConfig(List.of("CLOSED"));
    var row = pendingRow(config.getId(), 0, config.getId() + ":CLOSED:WO-OK");
    stubResponseStatus.set(200);

    worker.poll();

    var reloaded = deliveries.findById(row.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    assertThat(reloaded.getResponseCode()).isEqualTo(200);
    assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    assertThat(reloaded.getLatencyMs()).isNotNull();
    assertThat(receivedBodies).anyMatch(b -> b.contains("WO-OK"));
    assertThat(receivedSignatures).isNotEmpty()
        .allMatch(sig -> sig != null && sig.matches("[0-9a-f]{64}"));
  }

  @Test
  @DisplayName("22.1-INT-007 P0 4xx → FAILED terminal, not re-dispatched")
  void fourXxTerminalFailure() {
    var config = committedConfig(List.of("CLOSED"));
    var row = pendingRow(config.getId(), 0, config.getId() + ":CLOSED:WO-4XX");
    stubResponseStatus.set(400);

    dispatchService.dispatch(row);

    var reloaded = deliveries.findById(row.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    assertThat(reloaded.getResponseCode()).isEqualTo(400);
    assertThat(reloaded.getNextRetryAt()).isNull();

    // The sweep query must never re-pick a terminal FAILED row (the worker's only
    // dispatchable statuses are PENDING/RETRYING).
    var due = deliveries.findDueDeliveries(
        List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRYING),
        Instant.now().plusSeconds(3600));
    assertThat(due).extracting(WebhookDeliveryLogEntity::getId).doesNotContain(row.getId());
  }

  @Test
  @DisplayName("22.1-INT-008 P0 5xx → RETRYING with backoff; exhausted attempt → DLQ")
  void fiveXxRetriesThenDlq() {
    var config = committedConfig(List.of("CLOSED"));
    stubResponseStatus.set(503);

    var row = pendingRow(config.getId(), 0, config.getId() + ":CLOSED:WO-5XX");
    dispatchService.dispatch(row);
    assertThat(row.getStatus()).isEqualTo(WebhookDeliveryStatus.RETRYING);
    assertThat(row.getNextRetryAt()).isAfter(Instant.now());

    // attemptCount 2 + this failure = 3rd attempt = maxAttempts → DLQ, next_retry_at null.
    var exhausted = pendingRow(config.getId(), 2, config.getId() + ":CLOSED:WO-5XX-EXH");
    dispatchService.dispatch(exhausted);
    assertThat(exhausted.getStatus()).isEqualTo(WebhookDeliveryStatus.DLQ);
    assertThat(exhausted.getNextRetryAt()).isNull();
    assertThat(exhausted.getAttemptCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("22.1-INT-009 P1 findDueDeliveries skips not-yet-due RETRYING rows")
  void dueSweepHonorsNextRetryAt() {
    var config = committedConfig(List.of("CLOSED"));
    var future = Instant.now().plusSeconds(3600);
    var notDue = deliveries.saveAndFlush(new WebhookDeliveryLogEntity(
        UUID.randomUUID(), config.getId(), "CLOSED", Map.of("k", "v"),
        null, null, null, WebhookDeliveryStatus.RETRYING, 1, future,
        "trace-due", 3, config.getId() + ":CLOSED:WO-DUE", T0, T0));

    var due = deliveries.findDueDeliveries(
        List.of(WebhookDeliveryStatus.PENDING, WebhookDeliveryStatus.RETRYING),
        Instant.now());

    assertThat(due).extracting(WebhookDeliveryLogEntity::getId).doesNotContain(notDue.getId());
  }

  @Test
  @DisplayName("22.1-INT-010 P2 config CRUD audit row persists with masked secret (WEBHOOK_CONFIG type)")
  void configMutationAuditedWithMaskedSecret() {
    var admin = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(),
        "wh-admin-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.SUPER_ADMIN, true, T0, T0));
    var actor = new AuthenticatedUser(admin.getId().toString(), admin.getLoginIdentifier(),
        ApplicationRole.SUPER_ADMIN);

    var view = configService.create(actor, new CreateWebhookConfigRequest(
        "audit-hook-" + UUID.randomUUID(), WebhookDirection.OUTBOUND, List.of("CLOSED"),
        stubUrl(), "raw-secret-value", true));

    assertThat(view.hmacSecretMasked()).isEqualTo("****alue");
    var audit = auditLogs.findAll().stream()
        .filter(a -> a.getEntityType() == AuditEntityType.WEBHOOK_CONFIG)
        .filter(a -> a.getEntityId().equals(view.id()))
        .findFirst().orElseThrow();
    assertThat(audit.getNewValue()).contains("****alue").doesNotContain("raw-secret-value");
  }

  // --- AlertOpenedEvent through the bus (patch 7) ---------------------------------

  @Test
  @DisplayName("22.1-INT-011 P0 committed AlertOpenedEvent enqueues one PENDING row via the bus")
  void alertEventThroughBusEnqueues() {
    var config = committedConfig(List.of("ALERT_OPENED"));
    var alertId = UUID.randomUUID();

    requiresNew().executeWithoutResult(status -> events.publishEvent(
        new AlertOpenedEvent(alertId, UUID.randomUUID(), "trace-alert-bus")));

    var rows = deliveries.findAll().stream()
        .filter(d -> d.getWebhookConfigId().equals(config.getId()))
        .filter(d -> "ALERT_OPENED".equals(d.getEventType()))
        .toList();
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
    assertThat(rows.getFirst().getTraceId()).isEqualTo("trace-alert-bus");
    assertThat(rows.getFirst().getIdempotencyKey())
        .isEqualTo(config.getId() + ":ALERT_OPENED:" + alertId);
  }

  // --- delivery reads against the real DB (patch 6) --------------------------------

  @Test
  @DisplayName("22.1-INT-012 P0 per-config deliveries newest-first + status filter branch")
  void deliveryReadsOrderAndFilter() {
    var config = committedConfig(List.of("CLOSED"));
    var oldest = seedRow(config.getId(), "CLOSED", WebhookDeliveryStatus.DELIVERED,
        T0.plusSeconds(100), config.getId() + ":CLOSED:WO-OLD");
    var middle = seedRow(config.getId(), "CLOSED", WebhookDeliveryStatus.DLQ,
        T0.plusSeconds(200), config.getId() + ":CLOSED:WO-MID");
    var newest = seedRow(config.getId(), "CLOSED", WebhookDeliveryStatus.FAILED,
        T0.plusSeconds(300), config.getId() + ":CLOSED:WO-NEW");

    // Newest-first ordering scoped to this config.
    var page = deliveryQueryService.listForConfig(adminActor(), config.getId(), 0, 10);
    assertThat(page.items()).extracting(d -> d.id())
        .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    assertThat(page.sort()).isEqualTo("createdAt,id:desc");

    // Status filter branch: every returned row is DLQ and my seeded DLQ row is present.
    var dlq = deliveryQueryService.list(adminActor(), WebhookDeliveryStatus.DLQ, 0, 100);
    assertThat(dlq.items()).allMatch(d -> d.status() == WebhookDeliveryStatus.DLQ);
    assertThat(dlq.items()).extracting(d -> d.id()).contains(middle.getId());
  }

  @Test
  @DisplayName("22.1-INT-013 P1 delivery list size clamps to 100 and defaults to 20")
  void deliveryReadsClampPageSize() {
    var config = committedConfig(List.of("CLOSED"));
    seedRow(config.getId(), "CLOSED", WebhookDeliveryStatus.PENDING,
        T0.plusSeconds(50), config.getId() + ":CLOSED:WO-CLAMP");

    // Oversized request clamps to MAX_PAGE_SIZE (100).
    var clamped = deliveryQueryService.list(adminActor(), null, 0, 500);
    assertThat(clamped.size()).isEqualTo(100);

    // Non-positive size falls back to DEFAULT_PAGE_SIZE (20).
    var defaulted = deliveryQueryService.list(adminActor(), null, 0, 0);
    assertThat(defaulted.size()).isEqualTo(20);

    // Negative page normalizes to 0 and echoes the normalized value (patch 15).
    var negativePage = deliveryQueryService.list(adminActor(), null, -1, 20);
    assertThat(negativePage.page()).isZero();
  }

  private static AuthenticatedUser adminActor() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
  }
}
