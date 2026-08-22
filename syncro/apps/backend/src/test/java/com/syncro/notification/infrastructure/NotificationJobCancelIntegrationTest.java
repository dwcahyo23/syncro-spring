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
 * Integration tests for {@link NotificationJobRepository#cancelActiveForAlert}.
 *
 * <p>Uses {@code @DataJpaTest} (JPA slice) to avoid loading InfluxDB/MQTT/Redis beans
 * that crash in this environment. Seeds data via JdbcTemplate to bypass the full JPA
 * FK chain (plant → machine group → machine → sparepart → installation → alert → job).
 * The test is about the JPQL bulk-update query, not entity construction.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class NotificationJobCancelIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

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

  private UUID alertId;
  private UUID otherAlertId;

  private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
  private static final Timestamp TS = Timestamp.from(NOW);
  private static final Timestamp TS_EARLIER = Timestamp.from(NOW.minusSeconds(7200));

  @BeforeEach
  void setUp() {
    alertId = UUID.randomUUID();
    otherAlertId = UUID.randomUUID();

    // Seed the minimum supporting rows via raw SQL to avoid the full JPA FK chain.
    // plant → machine_group → machine → sparepart taxonomy → sparepart
    //   → machine_sparepart_installation → sparepart_alert

    UUID plantId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID machineId = UUID.randomUUID();
    UUID spId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();
    UUID kindId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    UUID instId = UUID.randomUUID();
    UUID inst2Id = UUID.randomUUID();

    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, "PLANT-55-IT", "Plant 55 IT", TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        groupId, plantId, "Assembly", TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machineId, plantId, groupId, "MCH-55-IT", "Machine 55 IT", "ACTIVE", TS, TS);

    // Taxonomy entries for sparepart
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        catId, "CATEGORY", "CAT-55", "Cat 55", TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        brandId, "BRAND", "BRAND-55", "Brand 55", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        kindId, "KIND", "KIND-55", "Kind 55", catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        typeId, "TYPE", "TYPE-55", "Type 55", catId, TS, TS);

    jdbc.update("INSERT INTO spareparts (id, code, name, machine_id, category_id, brand_id, kind_id, type_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        spId, "SP-55-IT", "Sparepart 55 IT", machineId, catId, brandId, kindId, typeId, TS, TS);
    // Two distinct installations so two OPEN alerts don't clash on the unique partial index
    jdbc.update("INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, function_name, expected_production_count, baseline_counter, threshold_percentage, installed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        instId, machineId, spId, "func-1", 1000, 0, 80, TS_EARLIER, TS, TS);
    jdbc.update("INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, function_name, expected_production_count, baseline_counter, threshold_percentage, installed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        inst2Id, machineId, spId, "func-2", 1000, 0, 80, TS_EARLIER, TS, TS);

    jdbc.update("INSERT INTO sparepart_alerts (id, machine_id, machine_sparepart_installation_id, threshold_percentage, current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot, trace_id, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        alertId, machineId, instId, 80, 1000, 800, 80.00, "trace-5-5", "OPEN", TS, TS);
    jdbc.update("INSERT INTO sparepart_alerts (id, machine_id, machine_sparepart_installation_id, threshold_percentage, current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot, trace_id, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        otherAlertId, machineId, inst2Id, 80, 1000, 800, 80.00, "trace-5-5", "OPEN", TS, TS);
  }

  @Test
  @DisplayName("5-5-AC1: acknowledge cancels PENDING and SENT jobs; EXHAUSTED/ESCALATED untouched")
  void cancelsPendingAndSentJobs() {
    insertJob(alertId, "TECHNICIAN", "PENDING");
    insertJob(alertId, "STAFF", "SENT");
    insertJob(alertId, "LEADER", "EXHAUSTED");
    insertJob(alertId, "SPV", "ESCALATED");

    int cancelled = jobs.cancelActiveForAlert(
        alertId,
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT),
        NotificationJobStatus.CANCELLED,
        NOW);

    assertThat(cancelled).isEqualTo(2);
    assertThat(statusOf(alertId, "TECHNICIAN")).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(statusOf(alertId, "STAFF")).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(statusOf(alertId, "LEADER")).isEqualTo(NotificationJobStatus.EXHAUSTED);
    assertThat(statusOf(alertId, "SPV")).isEqualTo(NotificationJobStatus.ESCALATED);
  }

  @Test
  @DisplayName("5-5-AC2: cancel does not touch jobs belonging to other alerts")
  void doesNotTouchOtherAlerts() {
    insertJob(alertId, "TECHNICIAN", "PENDING");
    insertJob(otherAlertId, "TECHNICIAN", "PENDING");

    int cancelled = jobs.cancelActiveForAlert(
        alertId,
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT),
        NotificationJobStatus.CANCELLED,
        NOW);

    assertThat(cancelled).isEqualTo(1);
    assertThat(statusOf(alertId, "TECHNICIAN")).isEqualTo(NotificationJobStatus.CANCELLED);
    assertThat(statusOf(otherAlertId, "TECHNICIAN")).isEqualTo(NotificationJobStatus.PENDING);
  }

  @Test
  @DisplayName("5-5-AC3: returns 0 when there are no active jobs to cancel")
  void returnsZeroWhenNothingToCancel() {
    int cancelled = jobs.cancelActiveForAlert(
        alertId,
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT),
        NotificationJobStatus.CANCELLED,
        NOW);

    assertThat(cancelled).isZero();
  }

  @Test
  @DisplayName("5-5-AC4: cancelled jobs are not returned by findPendingJobsDue or findSentJobsDueForEscalation")
  void cancelledJobsAreExcludedFromWorkerQueries() {
    insertJob(alertId, "TECHNICIAN", "PENDING");
    insertJob(alertId, "STAFF", "SENT");

    jobs.cancelActiveForAlert(
        alertId,
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT),
        NotificationJobStatus.CANCELLED,
        NOW);

    var pending = jobs.findPendingJobsDue(List.of(NotificationJobStatus.PENDING), NOW.plusSeconds(60));
    var sent = jobs.findSentJobsDueForEscalation(NotificationJobStatus.SENT, NOW.plusSeconds(60));

    assertThat(pending).isEmpty();
    assertThat(sent).isEmpty();
  }

  // ---- helpers ----

  private void insertJob(UUID forAlertId, String level, String status) {
    // V34 CHECK (status <> 'SENT' OR sent_at IS NOT NULL): SENT rows must carry sent_at.
    Object sentAt = "SENT".equals(status) ? TS : null;
    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, sent_at, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """,
        UUID.randomUUID(), forAlertId, level, status, sentAt,
        forAlertId + "::" + level, "trace-5-5", TS, TS);
  }

  private NotificationJobStatus statusOf(UUID forAlertId, String level) {
    return jobs.findAll().stream()
        .filter(j -> j.getAlertId().equals(forAlertId))
        .filter(j -> j.getEscalationLevel().equals(level))
        .map(NotificationJobEntity::getStatus)
        .findFirst()
        .orElseThrow(() -> new AssertionError("job not found: " + forAlertId + "/" + level));
  }
}
