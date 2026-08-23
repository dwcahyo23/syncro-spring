package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "server.port=0",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "GARAGE_HOST=localhost",
    "GARAGE_S3_PORT=3900",
    "GARAGE_ACCESS_KEY=test",
    "GARAGE_SECRET_KEY=test",
    "GARAGE_BUCKET=test",
    "GARAGE_REGION=garage",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
@Testcontainers
class DbIndexHygieneMigrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private MachineRepository machineRepository;

  @Autowired
  private SparepartTaxonomyRepository taxonomyRepository;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-21T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @DisplayName("V17 drops the six redundant indexes after the full V1-V17 migration chain")
  void redundantIndexesAreDropped() {
    assertThat(toRegclass("public.idx_machine_sparepart_installations_machine_id_sparepart_id")).isNull();
    assertThat(toRegclass("public.uq_auth_user_plant_assignments_auth_user_plant")).isNull();
    assertThat(toRegclass("public.idx_auth_user_plant_assignments_auth_user_id")).isNull();
    assertThat(toRegclass("public.idx_machine_groups_plant_id")).isNull();
    assertThat(toRegclass("public.idx_machines_plant_id")).isNull();
    assertThat(toRegclass("public.idx_sparepart_taxonomy_dimension")).isNull();
  }

  @Test
  @DisplayName("V17 creates the three order-by-supporting indexes with expected definitions")
  void queryIndexesArePresentWithExpectedDefinitions() {
    assertThat(indexDef("idx_spareparts_code"))
        .isNotNull()
        .endsWith("USING btree (code)");
    assertThat(indexDef("idx_machines_code"))
        .isNotNull()
        .endsWith("USING btree (code)");
    assertThat(indexDef("idx_sparepart_taxonomy_dimension_name"))
        .isNotNull()
        .endsWith("USING btree (dimension, name)");
  }

  @Test
  @DisplayName("V17 keeps primary keys and unique constraints backing the dropped indexes")
  void keptConstraintsAndUniqueIndexesStillExist() {
    assertThat(toRegclass("public.pk_auth_user_plant_assignments")).isNotNull();
    assertThat(toRegclass("public.uq_machine_sparepart_installations_machine_sparepart_function")).isNotNull();
    assertThat(toRegclass("public.uq_machines_plant_id_lower_code")).isNotNull();
    assertThat(toRegclass("public.uq_sparepart_taxonomy_dimension_lower_code")).isNotNull();
    assertThat(toRegclass("public.uq_sparepart_taxonomy_dimension_lower_name")).isNotNull();
    assertThat(toRegclass("public.uq_machine_groups_plant_id_name")).isNotNull();
    assertThat(toRegclass("public.uq_machine_groups_id_plant_id")).isNotNull();
  }

  @Test
  @DisplayName("V17 keeps the FK-supporting single-column indexes")
  void keptSingleColumnIndexesStillExist() {
    assertThat(toRegclass("public.idx_machine_sparepart_installations_machine_id")).isNotNull();
    assertThat(toRegclass("public.idx_machine_sparepart_installations_sparepart_id")).isNotNull();
    assertThat(toRegclass("public.idx_auth_user_plant_assignments_plant_id")).isNotNull();
    assertThat(toRegclass("public.idx_machines_plant_id_status")).isNotNull();
    assertThat(toRegclass("public.idx_machines_machine_group_id")).isNotNull();
  }

  @Test
  @Transactional
  @DisplayName("ORDER_PRESERVED: findByDimensionOrderByNameAsc returns rows ordered by name after V17")
  void taxonomyOrderingPreservedAfterV17() {
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-02', 'Beta')",
        UUID.randomUUID());
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-01', 'Alpha')",
        UUID.randomUUID());
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name) VALUES (?, 'CATEGORY', 'C-03', 'Charlie')",
        UUID.randomUUID());

    List<String> names = taxonomyRepository
        .findByDimensionOrderByNameAsc(SparepartTaxonomyDimension.CATEGORY)
        .stream()
        .map(SparepartTaxonomyEntity::getName)
        .toList();

    assertThat(names).isSorted();
  }

  @Test
  @Transactional
  @DisplayName("JOIN_STILL_OK: findAllScoped/findAllUnscoped return machines ordered by code after V17")
  void machineListOrderingPreservedAfterV17() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name) VALUES (?, 'PLANT-A', 'Plant A')", plantId);

    UUID groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name) VALUES (?, ?, 'Group A')", groupId, plantId);

    UUID secondMachine = UUID.randomUUID();
    UUID firstMachine = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, 'M-002', 'Machine Two', 'ACTIVE')",
        secondMachine, plantId, groupId);
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, 'M-001', 'Machine One', 'ACTIVE')",
        firstMachine, plantId, groupId);

    List<String> scopedCodes = machineRepository
        .findAllScoped(List.of(plantId), null, null, null, null,
            PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "code")))
        .stream()
        .map(MachineEntity::getCode)
        .toList();
    assertThat(scopedCodes).containsExactly("M-001", "M-002");

    List<String> unscopedCodes = machineRepository
        .findAllUnscoped(null, null, null, null,
            PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "code")))
        .stream()
        .map(MachineEntity::getCode)
        .toList();
    assertThat(unscopedCodes).containsExactly("M-001", "M-002");
  }

  @Test
  @Transactional
  @DisplayName("V17 preserves FK enforcement: deleting a plant with machines is RESTRICTed after the index drops")
  void referentialIntegrityStillEnforcedAfterV17() {
    UUID plantId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name) VALUES (?, 'PLANT-FK', 'Plant FK')", plantId);
    UUID groupId = UUID.randomUUID();
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name) VALUES (?, ?, 'Group FK')", groupId, plantId);
    UUID machineId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO machines (id, plant_id, machine_group_id, code, name, status) VALUES (?, ?, ?, 'M-FK', 'Machine FK', 'ACTIVE')",
        machineId, plantId, groupId);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM plants WHERE id = ?", plantId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V34 adds the conditional CHECK chk_notification_jobs_sent_requires_sent_at")
  void v34SentRequiresSentAtConstraintExists() {
    assertThat(jdbc.queryForObject("""
        SELECT count(*) FROM pg_constraint
        WHERE conname = 'chk_notification_jobs_sent_requires_sent_at'
          AND conrelid = 'notification_jobs'::regclass
        """, Long.class)).isEqualTo(1L);
  }

  @Test
  @Transactional
  @DisplayName("V34 rejects a SENT job without sent_at but accepts one with sent_at")
  void v34SentJobsRequireSentAt() {
    UUID alertId = seedAlertForNotificationJob();

    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, sent_at, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "STAFF", "SENT", TS, "v34-key-sent-with",
        "trace-v34", TS, TS);
    assertThat(countJobsForAlert(alertId)).isEqualTo(1L);

    // The rejected insert must be last: it aborts the PostgreSQL transaction, which would
    // block any subsequent statement in the same transaction.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "TECHNICIAN", "SENT", "v34-key-sent-null",
        "trace-v34", TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V34 keeps PENDING jobs without sent_at insertable (normal application path)")
  void v34PendingJobsMayKeepNullSentAt() {
    UUID alertId = seedAlertForNotificationJob();

    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "TECHNICIAN", "PENDING", "v34-key-pending",
        "trace-v34", TS, TS);

    assertThat(countJobsForAlert(alertId)).isEqualTo(1L);
  }

  @Test
  @Transactional
  @DisplayName("V31 extends ck_audit_log_entity_type to allow exactly the 8 entity types incl. ALERT")
  void v31AuditEntityTypeConstraintAllowsAllEight() {
    String def = jdbc.queryForObject("""
        SELECT pg_get_constraintdef(oid) FROM pg_constraint
        WHERE conname = 'ck_audit_log_entity_type'
          AND conrelid = 'audit_log'::regclass
        """, String.class);

    assertThat(def).isNotNull();
    assertThat(constraintListValues(def)).containsExactly(
        "PLANT", "MACHINE_GROUP", "MACHINE", "SPAREPART_TAXONOMY",
        "SPAREPART", "INSTALLATION", "RESPONSIBILITY", "ALERT");
  }

  @Test
  @Transactional
  @DisplayName("V35 accepts a sparepart_alert threshold_percentage of 90 and rejects 150")
  void v35ThresholdCheckAcceptsInRangeAndRejectsAboveRange() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, 90);
    assertThat(alertCountForMachine(chain.machineId())).isEqualTo(1L);

    // The rejected insert must be last: it aborts the PostgreSQL transaction, which would
    // block any subsequent statement in the same transaction.
    assertThatThrownBy(() -> insertSparepartAlert(chain, 150))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V35 accepts a sparepart_alert threshold_percentage of 90 and rejects -1")
  void v35ThresholdCheckAcceptsInRangeAndRejectsBelowRange() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, 90);
    assertThat(alertCountForMachine(chain.machineId())).isEqualTo(1L);

    // The rejected insert must be last: it aborts the PostgreSQL transaction, which would
    // block any subsequent statement in the same transaction.
    assertThatThrownBy(() -> insertSparepartAlert(chain, -1))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V36 rejects a second notification_jobs row with a duplicate idempotency_key")
  void v36DuplicateIdempotencyKeyRejected() {
    UUID alertId = seedAlertForNotificationJob();

    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "STAFF", "PENDING", "v36-key-dup",
        "trace-v36", TS, TS);
    assertThat(countJobsForAlert(alertId)).isEqualTo(1L);

    // Distinct escalation_level so the only violated constraint is the V36 idempotency
    // key unique index (uq_notification_jobs_alert_level stays satisfied).
    // The rejected insert must be last: it aborts the PostgreSQL transaction, which would
    // block any subsequent statement in the same transaction.
    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "TECHNICIAN", "PENDING", "v36-key-dup",
        "trace-v36", TS, TS))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V36 allows notification_jobs rows with distinct idempotency keys")
  void v36DistinctIdempotencyKeysAllowed() {
    UUID alertId = seedAlertForNotificationJob();

    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "STAFF", "PENDING", "v36-key-a",
        "trace-v36", TS, TS);
    jdbc.update("""
        INSERT INTO notification_jobs
          (id, alert_id, escalation_level, status, idempotency_key, trace_id,
           attempt_count, max_attempts, version, created_at, updated_at)
        VALUES (?,?,?,?,?,?, 0, 3, 0, ?, ?)
        """, UUID.randomUUID(), alertId, "TECHNICIAN", "PENDING", "v36-key-b",
        "trace-v36", TS, TS);

    assertThat(countJobsForAlert(alertId)).isEqualTo(2L);
  }

  private Object toRegclass(String relation) {
    return jdbc.queryForObject("SELECT to_regclass(?)", Object.class, relation);
  }

  private String indexDef(String indexName) {
    return jdbc.query(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            (rs, rowNum) -> rs.getString("indexdef"),
            indexName)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * Seeds the full FK chain a {@code notification_jobs} row needs
   * (plant → machine group → machine → sparepart taxonomy → sparepart →
   * installation → alert), mirroring NotificationJobCancelIntegrationTest.
   */
  private UUID seedAlertForNotificationJob() {
    AlertChainSeed chain = seedAlertChain();
    UUID alertId = UUID.randomUUID();
    jdbc.update("INSERT INTO sparepart_alerts (id, machine_id, machine_sparepart_installation_id, threshold_percentage, current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot, trace_id, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        alertId, chain.machineId(), chain.installationId(), 80, 1000, 800, new BigDecimal("80.00"), "trace-v34", "OPEN", TS, TS);
    return alertId;
  }

  private AlertChainSeed seedAlertChain() {
    int seq = seedSeq.incrementAndGet();
    UUID plantId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID machineId = UUID.randomUUID();
    UUID spId = UUID.randomUUID();
    UUID catId = UUID.randomUUID();
    UUID brandId = UUID.randomUUID();
    UUID kindId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    UUID instId = UUID.randomUUID();
    String tag = "V34-" + seq;

    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plantId, tag, "Plant " + tag, TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        groupId, plantId, "Assembly " + seq, TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machineId, plantId, groupId, "M-" + tag, "Machine " + tag, "ACTIVE", TS, TS);

    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        catId, "CATEGORY", "CAT-" + tag, "Cat " + seq, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        brandId, "BRAND", "BRAND-" + tag, "Brand " + seq, catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        kindId, "KIND", "KIND-" + tag, "Kind " + seq, catId, TS, TS);
    jdbc.update("INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
        typeId, "TYPE", "TYPE-" + tag, "Type " + seq, catId, TS, TS);

    jdbc.update("INSERT INTO spareparts (id, code, name, machine_id, category_id, brand_id, kind_id, type_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        spId, "SP-" + tag, "Sparepart " + tag, machineId, catId, brandId, kindId, typeId, TS, TS);
    jdbc.update("INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, function_name, expected_production_count, baseline_counter, threshold_percentage, installed_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        instId, machineId, spId, "func-" + seq, 1000, 0, 80, TS, TS, TS);

    return new AlertChainSeed(machineId, instId);
  }

  private record AlertChainSeed(UUID machineId, UUID installationId) {
  }

  private void insertSparepartAlert(AlertChainSeed chain, int threshold) {
    jdbc.update("INSERT INTO sparepart_alerts (id, machine_id, machine_sparepart_installation_id, threshold_percentage, current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot, trace_id, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), chain.machineId(), chain.installationId(), threshold, 1000, 800,
        new BigDecimal("80.00"), "trace-v35", "OPEN", TS, TS);
  }

  private Long alertCountForMachine(UUID machineId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM sparepart_alerts WHERE machine_id = ?", Long.class, machineId);
  }

  private List<String> constraintListValues(String constraintDef) {
    return Pattern.compile("'([A-Z_]+)'")
        .matcher(constraintDef)
        .results()
        .map(match -> match.group(1))
        .toList();
  }

  private Long countJobsForAlert(UUID alertId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification_jobs WHERE alert_id = ?", Long.class, alertId);
  }
}
