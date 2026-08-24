package com.syncro.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration evidence for V41 (story 8-7): the alert-type discriminator defaults existing rows to
 * THRESHOLD_PERCENTAGE, threshold and snapshot columns are nullable, the procurement-risk partial
 * unique index dedupes non-RESOLVED rows, the threshold dedupe index stays untouched, and the new
 * CHECK constraints accept valid rows while rejecting invalid ones.
 */
class SparepartAlertTypeMigrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-24T09:00:00Z"));
  private static final AtomicInteger seedSeq = new AtomicInteger();

  @Test
  @Transactional
  @DisplayName("V41 alert_type DEFAULT backfills rows that omit the column to THRESHOLD_PERCENTAGE")
  void alertTypeColumnDefaultApplies() {
    AlertChainSeed chain = seedAlertChain();
    UUID id = UUID.randomUUID();
    // Omit alert_type entirely: the ADD COLUMN ... DEFAULT 'THRESHOLD_PERCENTAGE' mechanism —
    // the same DEFAULT that backfilled rows existing before V41 — must apply it.
    jdbc.update("""
        INSERT INTO sparepart_alerts
          (id, machine_id, machine_sparepart_installation_id, threshold_percentage,
           current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot,
           trace_id, status, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        """, id, chain.machineId(), chain.installationId(), 80,
        100L, 90L, new BigDecimal("90.00"), "tr-default", "OPEN", TS, TS);

    String alertType = jdbc.queryForObject(
        "SELECT alert_type FROM sparepart_alerts WHERE id = ?",
        String.class, id);
    assertThat(alertType).isEqualTo("THRESHOLD_PERCENTAGE");
  }

  @Test
  @Transactional
  @DisplayName("V41 accepts a null threshold for PROCUREMENT_RISK and rejects it for THRESHOLD_PERCENTAGE")
  void thresholdRequiredCheckEnforced() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", TS.toInstant(), "tr-t");
    assertThat(alertCountForInstallation(chain.installationId())).isEqualTo(1L);

    assertThatThrownBy(() -> insertSparepartAlert(chain, "THRESHOLD_PERCENTAGE", null,
        null, null, null, null, "tr-t2"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V41 alert_type CHECK accepts both values and rejects anything else")
  void alertTypeCheckEnforced() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, "THRESHOLD_PERCENTAGE", 80, null, null, null, null, "tr-a");
    insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "ROLLING_30_DAY", TS.toInstant(), "tr-b");

    assertThatThrownBy(() -> insertSparepartAlert(chain, "SOMETHING_ELSE", 80, null, null, null, null, "tr-c"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V41 PROCUREMENT_RISK rows require all four evidence columns")
  void procurementEvidenceCheckEnforced() {
    AlertChainSeed chain = seedAlertChain();
    // Missing projected_depletion_at -> rejected
    assertThatThrownBy(() -> insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", null, "tr-ev"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V41 calculation_basis CHECK accepts the two bases and rejects others")
  void calcBasisCheckEnforced() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", TS.toInstant(), "tr-b1");

    assertThatThrownBy(() -> insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "LAST_YEAR", TS.toInstant(), "tr-b2"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V41 threshold dedupe index still allows two THRESHOLD alerts with different thresholds")
  void thresholdDedupeSemanticsPreserved() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, "THRESHOLD_PERCENTAGE", 80, null, null, null, null, "tr-t1");
    insertSparepartAlert(chain, "THRESHOLD_PERCENTAGE", 90, null, null, null, null, "tr-t2");

    assertThat(alertCountForInstallation(chain.installationId())).isEqualTo(2L);
  }

  @Test
  @Transactional
  @DisplayName("V41 procurement-risk partial unique index rejects a second non-RESOLVED row")
  void procurementRiskDedupeIndexRejectsDuplicateOpen() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", TS.toInstant(), "tr-d1");

    // Same installation, another non-RESOLVED PROCUREMENT_RISK -> unique index violation.
    // Rejected insert must be last: it aborts the PostgreSQL transaction.
    assertThatThrownBy(() -> insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", TS.toInstant(), "tr-d2"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Transactional
  @DisplayName("V41 a RESOLVED procurement-risk alert allows a fresh open one (dedupe excludes RESOLVED)")
  void procurementRiskDedupeAllowsAfterResolved() {
    AlertChainSeed chain = seedAlertChain();
    UUID first = insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", TS.toInstant(), "tr-r1");
    jdbc.update("UPDATE sparepart_alerts SET status = 'RESOLVED' WHERE id = ?", first);

    insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "FULL_HISTORY", TS.toInstant(), "tr-r2");

    assertThat(alertCountForInstallation(chain.installationId())).isEqualTo(2L);
  }

  @Test
  @Transactional
  @DisplayName("V41 a THRESHOLD and a PROCUREMENT_RISK alert coexist on one installation")
  void bothTypesCoexistOnOneInstallation() {
    AlertChainSeed chain = seedAlertChain();
    insertSparepartAlert(chain, "THRESHOLD_PERCENTAGE", 80, null, null, null, null, "tr-c1");
    insertSparepartAlert(chain, "PROCUREMENT_RISK", null,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), "ROLLING_30_DAY", TS.toInstant(), "tr-c2");

    assertThat(alertCountForInstallation(chain.installationId())).isEqualTo(2L);
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
    String tag = "V41-" + seq;

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

  private UUID insertSparepartAlert(AlertChainSeed chain, String alertType, Integer threshold,
      BigDecimal leadTime, BigDecimal rate, String basis, Instant projectedDepletionAt, String trace) {
    UUID id = UUID.randomUUID();
    // Mirror CHECKs: THRESHOLD rows must carry snapshots; PROCUREMENT_RISK rows must have nulls.
    boolean risk = "PROCUREMENT_RISK".equals(alertType);
    jdbc.update("""
        INSERT INTO sparepart_alerts
          (id, machine_id, machine_sparepart_installation_id, alert_type, threshold_percentage,
           current_counter_snapshot, consumed_production_count_snapshot, consumed_percentage_snapshot,
           trace_id, status, created_at, updated_at,
           lead_time_hours, rate_per_operating_hour, calculation_basis, projected_depletion_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, id, chain.machineId(), chain.installationId(), alertType, threshold,
        risk ? null : 100L, risk ? null : 90L, risk ? null : new BigDecimal("90.00"), trace,
        "OPEN", TS, TS,
        leadTime, rate, basis, projectedDepletionAt == null ? null : Timestamp.from(projectedDepletionAt));
    return id;
  }

  private Long alertCountForInstallation(UUID installationId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM sparepart_alerts WHERE machine_sparepart_installation_id = ?",
        Long.class, installationId);
  }

  private record AlertChainSeed(UUID machineId, UUID installationId) {
  }
}
