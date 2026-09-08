package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.notification.api.WhatsAppMessageLogDtos.MessageLogView;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.domain.WhatsAppMessageDirection;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.notification.infrastructure.WahaClient;
import com.syncro.notification.infrastructure.WhatsAppMessageLogEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 22-4 end-to-end proof against a real PostgreSQL (V20 applied by Flyway):
 * a dispatched WAHA send writes exactly one OUTBOUND row carrying masked recipient,
 * derived template, status, attempt count, traceId and a text SHA-256 reference;
 * a retry updates the SAME row (never duplicates); DONE+CLOSED for one woId+user
 * produce two distinct rows; the unique job anchor is DB-enforced; the SUPER_ADMIN
 * read returns masked, newest-first projections with working (non-vacuous) filters,
 * FAILED rows sorting AFTER dated rows, and inbound rows excluded.
 *
 * <p>WahaClient and WahaRateLimiter are mocked — this test proves the DB evidence
 * behavior (upsert, constraint, queries), not the HTTP client (covered by
 * WahaClientCircuitBreakerTest) or Redis (covered by the dispatch unit tests).
 * Workers are pushed to a 1-hour poll so only explicit dispatch calls run.
 */
@TestPropertySource(properties = {
    "syncro.notification.worker.poll-interval-ms=3600000",
    "syncro.notification.escalation.poll-interval-ms=3600000"})
class WhatsAppMessageLogIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final String PHONE = "6281234567890";
  private static final String BODY = "Workorder lifecycle notification body";
  private static final Instant T0 = Instant.parse("2026-09-08T08:00:00Z");

  @Autowired
  private NotificationDispatchService dispatchService;
  @Autowired
  private NotificationJobRepository jobs;
  @Autowired
  private WhatsAppMessageLogRepository messageLogs;
  @Autowired
  private WhatsAppMessageLogQueryService queryService;
  @Autowired
  private PlatformTransactionManager transactionManager;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private WorkOrderRepository workOrders;

  @MockitoBean
  private WahaClient wahaClient;
  @MockitoBean
  private WahaRateLimiter rateLimiter;

  private UUID userId;
  private String woId;
  // Unique per run — the reused Testcontainers container keeps rows from earlier runs,
  // so constant trace_ids would break the containsExactly trace-filter assertion.
  private final String traceSuffix = UUID.randomUUID().toString().substring(0, 8);

  @BeforeEach
  void seedCorrelationRows() {
    // whatsapp_message_logs.user_id / work_order_id are real FKs (V1) — the outbound
    // rows must reference persisted auth_users / work_orders rows. Seeded through
    // REQUIRES_NEW (22-1 precedent) so the committed dispatch in INT-005's own
    // transaction can see them too.
    var seeded = requiresNew().execute(status -> {
      var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(),
          "wa-log-" + UUID.randomUUID() + "@syncro.test", "hash",
          ApplicationRole.STOREKEEPER, true, T0, T0));
      var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(),
          "WALOG" + UUID.randomUUID().toString().substring(0, 6), "Plant", T0, T0));
      var group = machineGroups.saveAndFlush(new MachineGroupEntity(
          UUID.randomUUID(), plant, "Group", T0, T0));
      var machine = machines.saveAndFlush(new MachineEntity(
          UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
          "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
      var id = "WO-224-" + UUID.randomUUID().toString().substring(0, 8);
      workOrders.saveAndFlush(new WorkOrderEntity(id, "INTERNAL", null, WorkOrderStatus.OPEN,
          null, machine.getId(), "22-4 log test", 0L, null, null, null, T0, T0));
      return new Object[] {user.getId(), id};
    });
    userId = (UUID) seeded[0];
    woId = (String) seeded[1];
  }

  private TransactionTemplate requiresNew() {
    var template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  /** Lifecycle-shaped job (pre-composed body → no renderer/DB chain needed). */
  private NotificationJobEntity lifecycleJob(String event, String traceId) {
    var job = new NotificationJobEntity(null, "LEADER", NotificationJobStatus.PENDING,
        userId, PHONE, "WORKORDER:" + woId + ":" + event + ":" + userId, traceId, null, BODY);
    return jobs.saveAndFlush(job);
  }

  /** Sparepart-request-shaped job — WORK_ORDER filters must exclude it. */
  private NotificationJobEntity sparepartJob(String traceId) {
    var job = new NotificationJobEntity(null, "ACK_WAITING", NotificationJobStatus.PENDING,
        userId, PHONE, "SPAREPART_REQUEST:" + UUID.randomUUID() + ":ACK_WAITING:" + userId,
        traceId, null, BODY);
    return jobs.saveAndFlush(job);
  }

  private long rowsForJob(UUID jobId) {
    return messageLogs.findAll().stream()
        .filter(l -> jobId.equals(l.getNotificationJobId()))
        .count();
  }

  // --- V20 schema evidence ---------------------------------------------------------

  @Test
  @DisplayName("22.4-INT-001 P0 V20 outbound columns round-trip; waha_message_id/received_at nullable; logged_at set")
  void v20ColumnsRoundTrip() {
    var row = WhatsAppMessageLogEntity.forOutbound(UUID.randomUUID(), null, "IT-KEY-1",
        "WORK_ORDER", woId, "workorder_lifecycle:DONE", "628***890", woId, userId,
        "trace-roundtrip", Instant.parse("2026-09-08T08:30:00Z"));
    row.bumpAttempt();
    row.markSent(Instant.parse("2026-09-08T09:00:00Z"), "abc123");
    var saved = messageLogs.saveAndFlush(row);

    var reloaded = messageLogs.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getDirection()).isEqualTo(WhatsAppMessageDirection.OUTBOUND);
    assertThat(reloaded.getWahaMessageId()).isNull(); // widened to nullable
    assertThat(reloaded.getReceivedAt()).isNull();
    assertThat(reloaded.getStatus()).isEqualTo(WhatsAppMessageLogStatus.SENT);
    assertThat(reloaded.getAttemptCount()).isEqualTo(1);
    assertThat(reloaded.getTextSha256()).isEqualTo("abc123");
    assertThat(reloaded.getLoggedAt()).isEqualTo(Instant.parse("2026-09-08T08:30:00Z"));
    assertThat(reloaded.getVersion()).isZero();
  }

  // --- dispatch → row ----------------------------------------------------------------

  @Test
  @DisplayName("22.4-INT-002 P0 successful dispatch writes one OUTBOUND SENT row, masked, with hash")
  void dispatchWritesSentRow() {
    when(rateLimiter.isRateLimited(any(String.class), any())).thenReturn(false);
    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(true, 200, "{\"sent\":true}"));

    var job = lifecycleJob("DONE", "trace-ok");
    dispatchService.dispatch(job);

    var rows = messageLogs.findAll().stream()
        .filter(l -> job.getId().equals(l.getNotificationJobId())).toList();
    assertThat(rows).hasSize(1);
    var row = rows.getFirst();
    assertThat(row.getDirection()).isEqualTo(WhatsAppMessageDirection.OUTBOUND);
    assertThat(row.getStatus()).isEqualTo(WhatsAppMessageLogStatus.SENT);
    assertThat(row.getRecipientMasked()).isEqualTo("628***890");
    assertThat(row.getRecipientMasked()).doesNotContain(PHONE);
    assertThat(row.getTemplateName()).isEqualTo("workorder_lifecycle:DONE");
    assertThat(row.getTargetType()).isEqualTo("WORK_ORDER");
    assertThat(row.getTargetId()).isEqualTo(woId);
    assertThat(row.getWorkOrderId()).isEqualTo(woId);
    assertThat(row.getTraceId()).isEqualTo("trace-ok");
    assertThat(row.getAttemptCount()).isEqualTo(1);
    assertThat(row.getTextSha256()).hasSize(64);
    assertThat(row.getText()).isNull(); // raw text never stored
    assertThat(row.getLoggedAt()).isNotNull(); // never-sent rows still carry a timestamp
    assertThat(row.getIdempotencyKey()).isEqualTo(job.getIdempotencyKey());
  }

  @Test
  @DisplayName("22.4-INT-003 P0 retry 5xx→2xx updates the SAME row: FAILED→SENT, attempt_count 2")
  void retryUpdatesSameRow() {
    when(rateLimiter.isRateLimited(any(String.class), any())).thenReturn(false);
    var job = lifecycleJob("CLOSED", "trace-retry");

    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(false, 503, "Service Unavailable"));
    dispatchService.dispatch(job);

    var afterFirst = messageLogs.findByNotificationJobId(job.getId()).orElseThrow();
    assertThat(afterFirst.getStatus()).isEqualTo(WhatsAppMessageLogStatus.FAILED);
    assertThat(afterFirst.getAttemptCount()).isEqualTo(1);
    assertThat(afterFirst.getSentAt()).isNull();
    assertThat(afterFirst.getLoggedAt()).isNotNull(); // dated even before any send

    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));
    dispatchService.dispatch(job);

    assertThat(rowsForJob(job.getId())).isEqualTo(1); // updated, never duplicated
    var afterSecond = messageLogs.findByNotificationJobId(job.getId()).orElseThrow();
    assertThat(afterSecond.getStatus()).isEqualTo(WhatsAppMessageLogStatus.SENT);
    assertThat(afterSecond.getAttemptCount()).isEqualTo(2);
    assertThat(afterSecond.getSentAt()).isNotNull();
  }

  @Test
  @DisplayName("22.4-INT-004 P0 DONE then CLOSED for one woId+user → two rows, distinct templates")
  void distinctEventsNoCollision() {
    when(rateLimiter.isRateLimited(any(String.class), any())).thenReturn(false);
    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));

    var done = lifecycleJob("DONE", "trace-two");
    var closed = lifecycleJob("CLOSED", "trace-two");
    dispatchService.dispatch(done);
    dispatchService.dispatch(closed);

    var rows = messageLogs.findAll().stream()
        .filter(l -> userId.equals(l.getUserId()))
        .filter(l -> woId.equals(l.getTargetId()))
        .toList();
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(WhatsAppMessageLogEntity::getTemplateName)
        .containsExactlyInAnyOrder("workorder_lifecycle:DONE", "workorder_lifecycle:CLOSED");
  }

  @Test
  @DisplayName("22.4-INT-005 P0 unique job anchor: a second row for the same job is rejected")
  void uniqueJobAnchorEnforced() {
    when(rateLimiter.isRateLimited(any(String.class), any())).thenReturn(false);
    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));
    // Commit the first row through REQUIRES_NEW (22-1 INT-002 precedent) so the
    // violation below aborts only its own transaction, not the test's.
    var jobId = requiresNew().execute(status -> {
      var job = lifecycleJob("DONE", "trace-anchor");
      dispatchService.dispatch(job);
      return job.getId();
    });

    assertThatThrownBy(() -> requiresNew().executeWithoutResult(status -> messageLogs.saveAndFlush(
        WhatsAppMessageLogEntity.forOutbound(UUID.randomUUID(), jobId, "DUP-KEY",
            "WORK_ORDER", woId, "workorder_lifecycle:DONE", "628***890", woId, userId,
            "trace-anchor", T0))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- masked read path ---------------------------------------------------------------

  @Test
  @DisplayName("22.4-INT-006 P0 SUPER_ADMIN read: newest-first (FAILED null-sent_at last), non-vacuous filters, masked, inbound excluded")
  void superAdminReadsMaskedNewestFirst() {
    when(rateLimiter.isRateLimited(any(String.class), any())).thenReturn(false);
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);

    // Two SENT rows + one FAILED row (503) for the same workorder.
    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));
    var older = lifecycleJob("DONE", "trace-read-1-" + traceSuffix);
    dispatchService.dispatch(older);
    var newer = lifecycleJob("CLOSED", "trace-read-2-" + traceSuffix);
    dispatchService.dispatch(newer);
    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(false, 503, "Service Unavailable"));
    var failed = lifecycleJob("PART_READY", "trace-read-3-" + traceSuffix);
    dispatchService.dispatch(failed);

    // A SPAREPART_REQUEST-shaped row rides the same recipient — WORK_ORDER filters exclude it.
    when(wahaClient.send(eq(PHONE), eq(BODY), any()))
        .thenReturn(new WahaClient.Result(true, 200, "OK"));
    var sparepart = sparepartJob("trace-read-sp-" + traceSuffix);
    dispatchService.dispatch(sparepart);

    // An INBOUND row (V1 shape) sharing the workorder correlation — the evidence view
    // must never mix directions (review 22-4 P6).
    messageLogs.saveAndFlush(new WhatsAppMessageLogEntity(UUID.randomUUID(),
        "waha-in-" + UUID.randomUUID(), "syncro-main", "6281234567890@s.whatsapp.net",
        "+6281234567890", "Mesin down", null, null, woId, userId, T0));

    var page = queryService.list(admin, null, null, woId, null, 0, 10);
    assertThat(page.items()).hasSize(3); // inbound + sparepart rows excluded
    // Newest-first by sent_at; the FAILED row (null sent_at) sorts AFTER dated rows
    // (Postgres DESC puts NULLs FIRST without NULLS LAST — review 22-4 P3).
    assertThat(page.items().getLast().notificationJobId()).isEqualTo(failed.getId());
    assertThat(page.items().getLast().sentAt()).isNull();
    assertThat(page.items().subList(0, 2)).allMatch(v -> v.sentAt() != null);
    assertThat(page.items().getFirst().sentAt())
        .isAfterOrEqualTo(page.items().get(1).sentAt());
    assertThat(page.items()).extracting(MessageLogView::notificationJobId)
        .containsExactlyInAnyOrder(older.getId(), newer.getId(), failed.getId());
    assertThat(page.items()).allMatch(v -> "628***890".equals(v.recipientMasked()));
    assertThat(page.sort()).isEqualTo("sentAt:desc:nullslast,loggedAt:desc,id:desc");

    // Status filter branches are discriminating (review 22-4 P5): FAILED returns exactly
    // the failed row; SENT excludes it.
    var failedPage = queryService.list(admin, WhatsAppMessageLogStatus.FAILED, null, woId,
        null, 0, 10);
    assertThat(failedPage.items()).extracting(MessageLogView::notificationJobId)
        .containsExactly(failed.getId());
    var sentPage = queryService.list(admin, WhatsAppMessageLogStatus.SENT, null, woId,
        null, 0, 10);
    assertThat(sentPage.items()).extracting(MessageLogView::notificationJobId)
        .containsExactlyInAnyOrder(older.getId(), newer.getId());

    // targetType filter excludes the SPAREPART_REQUEST row even without a workorder filter.
    var byTarget = queryService.list(admin, null, "WORK_ORDER", null, null, 0, 100);
    assertThat(byTarget.items()).extracting(MessageLogView::notificationJobId)
        .contains(older.getId(), newer.getId(), failed.getId())
        .doesNotContain(sparepart.getId());
    var sparepartPage = queryService.list(admin, null, "SPAREPART_REQUEST", null, null, 0, 100);
    assertThat(sparepartPage.items()).extracting(MessageLogView::notificationJobId)
        .contains(sparepart.getId());

    // Trace filter.
    var byTrace = queryService.list(admin, null, null, null, "trace-read-1-" + traceSuffix,
        0, 10);
    assertThat(byTrace.items()).extracting(MessageLogView::notificationJobId)
        .containsExactly(older.getId());

    // Non-admin denied by the service gate (rego parity tested in the policy suite).
    var manager = new AuthenticatedUser(UUID.randomUUID().toString(), "mgr@syncro.test",
        ApplicationRole.MANAGER_MAINTENANCE);
    assertThatThrownBy(() -> queryService.list(manager, null, null, null, null, 0, 20))
        .isInstanceOf(WhatsAppMessageLogQueryService.WhatsAppMessageLogForbiddenException.class);
  }

  @Test
  @DisplayName("22.4-INT-007 P1 page-size clamp + negative page normalization (22-1 precedent)")
  void readClampsPageSize() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    assertThat(queryService.list(admin, null, null, null, null, 0, 500).size()).isEqualTo(100);
    assertThat(queryService.list(admin, null, null, null, null, 0, 0).size()).isEqualTo(20);
    assertThat(queryService.list(admin, null, null, null, null, -1, 20).page()).isZero();
  }
}
