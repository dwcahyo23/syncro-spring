package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.notification.domain.NotificationJobStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for {@link NotificationJobRepository#cancelActiveForRequest} — the
 * ack-stop bulk update that cancels sparepart-request escalation jobs when a request
 * reaches ACKED or CLOSED (story 12-3, FR-147).
 *
 * <p>Escalation jobs carry {@code alert_id = NULL} (V62) and an idempotency key of the
 * form {@code SPAREPART_REQUEST:{requestId}:{step}:{recipientId}}; the ack-stop matches
 * the prefix {@code SPAREPART_REQUEST:{requestId}:%}. Same container/@DataJpaTest slice
 * pattern as {@link NotificationJobCancelIntegrationTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class SparepartRequestEscalationCancelIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private NotificationJobRepository jobs;

  @Autowired
  private JdbcTemplate jdbc;

  private final UUID requestId = UUID.randomUUID();
  private final UUID otherRequestId = UUID.randomUUID();
  private final UUID recipientA = UUID.randomUUID();
  private final UUID recipientB = UUID.randomUUID();

  private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
  private static final Timestamp TS = Timestamp.from(NOW);

  @BeforeEach
  void setUp() {
    // No supporting rows needed — escalation jobs have alert_id NULL (V62), so the FK
    // chain is bypassed entirely.
  }

  @Test
  @DisplayName("12.3-DB-011 P0 ack-stop cancels PENDING/RATE_LIMITED escalation jobs for the request, not EXHAUSTED")
  void cancelsActiveJobsForRequest() {
    insertJob(requestId, "ACK_WAITING", recipientA, "PENDING");
    insertJob(requestId, "ACK_WAITING", recipientB, "RATE_LIMITED");
    insertJob(requestId, "PROCESS_WAITING", recipientA, "EXHAUSTED");

    int cancelled = jobs.cancelActiveForRequest(
        "SPAREPART_REQUEST:" + requestId + ":%",
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT,
            NotificationJobStatus.RATE_LIMITED),
        NotificationJobStatus.CANCELLED,
        NOW);

    assertThat(cancelled).isEqualTo(2);
    assertThat(statusOf(requestId, "ACK_WAITING", recipientA)).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(statusOf(requestId, "ACK_WAITING", recipientB)).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(statusOf(requestId, "PROCESS_WAITING", recipientA)).isEqualTo(NotificationJobStatus.EXHAUSTED);
  }

  @Test
  @DisplayName("12.3-DB-012 P0 ack-stop does not touch another request's jobs (prefix isolation)")
  void doesNotTouchOtherRequests() {
    insertJob(requestId, "ACK_WAITING", recipientA, "PENDING");
    insertJob(otherRequestId, "ACK_WAITING", recipientA, "PENDING");

    int cancelled = jobs.cancelActiveForRequest(
        "SPAREPART_REQUEST:" + requestId + ":%",
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT,
            NotificationJobStatus.RATE_LIMITED),
        NotificationJobStatus.CANCELLED,
        NOW);

    assertThat(cancelled).isEqualTo(1);
    assertThat(statusOf(requestId, "ACK_WAITING", recipientA)).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(statusOf(otherRequestId, "ACK_WAITING", recipientA)).isEqualTo(NotificationJobStatus.PENDING);
  }

  @Test
  @DisplayName("12.3-DB-013 P0 ack-stop with no matching jobs returns 0")
  void returnsZeroWhenNothingToCancel() {
    int cancelled = jobs.cancelActiveForRequest(
        "SPAREPART_REQUEST:" + requestId + ":%",
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT,
            NotificationJobStatus.RATE_LIMITED),
        NotificationJobStatus.CANCELLED,
        NOW);

    assertThat(cancelled).isZero();
  }

  @Test
  @DisplayName("12.3-DB-014 P0 escalation jobs with NULL alert_id insert cleanly (V62 FK fix)")
  void escalationJobWithNullAlertIdInserts() {
    insertJob(requestId, "PURCHASE_WAITING", recipientA, "PENDING");
    assertThat(jobs.findAll())
        .filteredOn(j -> j.getIdempotencyKey().startsWith("SPAREPART_REQUEST:" + requestId))
        .hasSize(1);
  }

  // ---- helpers ----

  private void insertJob(UUID forRequestId, String step, UUID recipientId, String status) {
    Object sentAt = "SENT".equals(status) ? TS : null;
    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, recipient_user_id, recipient_phone,
           idempotency_key, trace_id, sent_at, attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, 0, 3, 0, ?, ?)
        """,
        UUID.randomUUID(), step, status, recipientId, "628111",
        "SPAREPART_REQUEST:" + forRequestId + ":" + step + ":" + recipientId,
        "trace-escalation", sentAt, TS, TS);
  }

  private NotificationJobStatus statusOf(UUID forRequestId, String step, UUID recipientId) {
    return jobs.findAll().stream()
        .filter(j -> j.getIdempotencyKey().equals(
            "SPAREPART_REQUEST:" + forRequestId + ":" + step + ":" + recipientId))
        .map(NotificationJobEntity::getStatus)
        .findFirst()
        .orElseThrow(() -> new AssertionError("job not found"));
  }
}
