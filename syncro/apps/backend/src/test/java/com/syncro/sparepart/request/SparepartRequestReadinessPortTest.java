package com.syncro.sparepart.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.maintenance.application.SparepartRequestReadinessPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Integration test for the real {@link SparepartRequestReadinessPort} (AD-5, story 12-2).
 * Proves the bound/unbound request set drives {@code hasLiveNonReadyRequest} against a
 * real database — READY/CLOSED are not live, everything else (REQUESTED/ACKED/PROCESSING/
 * PURCHASE_REQUESTED/PART_RECEIVED/PICKED_UP) is.
 *
 * <p>{@code @DirtiesContext(BEFORE_CLASS)} forces a fresh Spring context + container here:
 * the shared {@code AbstractPostgresIntegrationTest} context is cached by Spring and would
 * otherwise reuse a DataSource pointing at a stale container port when this class runs
 * after other db tests in the same JVM (Testcontainers port mismatch).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SparepartRequestReadinessPortTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private SparepartRequestReadinessPort readiness;

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-27T09:00:00Z"));

  @Test
  @DisplayName("12.2-DB-010 P0 a live non-READY request on a bound workorder reports true")
  void liveNonReadyRequestTrue() {
    var workOrderId = ensureWorkOrder();
    var requestId = insertRequest(workOrderId, "REQUESTED");
    try {
      assertThat(readiness.hasLiveNonReadyRequest(workOrderId)).isTrue();
    } finally {
      jdbc.update("DELETE FROM sparepart_requests WHERE id = ?", requestId);
    }
  }

  @Test
  @DisplayName("12.2-DB-011 P0 a READY request on a bound workorder reports false")
  void readyRequestFalse() {
    var workOrderId = ensureWorkOrder();
    var requestId = insertRequest(workOrderId, "READY");
    try {
      assertThat(readiness.hasLiveNonReadyRequest(workOrderId)).isFalse();
    } finally {
      jdbc.update("DELETE FROM sparepart_requests WHERE id = ?", requestId);
    }
  }

  @Test
  @DisplayName("12.2-DB-012 P0 a CLOSED request on a bound workorder reports false")
  void closedRequestFalse() {
    var workOrderId = ensureWorkOrder();
    var requestId = insertRequest(workOrderId, "CLOSED");
    try {
      assertThat(readiness.hasLiveNonReadyRequest(workOrderId)).isFalse();
    } finally {
      jdbc.update("DELETE FROM sparepart_requests WHERE id = ?", requestId);
    }
  }

  @Test
  @DisplayName("12.2-DB-013 P0 an ACKED request on a bound workorder reports true")
  void ackedRequestTrue() {
    var workOrderId = ensureWorkOrder();
    var requestId = insertRequest(workOrderId, "ACKED");
    try {
      assertThat(readiness.hasLiveNonReadyRequest(workOrderId)).isTrue();
    } finally {
      jdbc.update("DELETE FROM sparepart_requests WHERE id = ?", requestId);
    }
  }

  @Test
  @DisplayName("12.2-DB-014 P0 a PURCHASE_REQUESTED request on a bound workorder reports true")
  void purchaseRequestedTrue() {
    var workOrderId = ensureWorkOrder();
    var requestId = insertRequest(workOrderId, "PURCHASE_REQUESTED");
    try {
      assertThat(readiness.hasLiveNonReadyRequest(workOrderId)).isTrue();
    } finally {
      jdbc.update("DELETE FROM sparepart_requests WHERE id = ?", requestId);
    }
  }

  @Test
  @DisplayName("12.2-DB-015 P0 no requests on a workorder reports false")
  void noRequestsFalse() {
    assertThat(readiness.hasLiveNonReadyRequest("WO-2609-" + (int) (Math.random() * 100000))).isFalse();
  }

  private String ensureWorkOrder() {
    var machineId = ensureMachine();
    var workOrderId = "WO-2609-" + (int) (Math.random() * 100000);
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, created_at, updated_at)
        VALUES (?, 'INTERNAL', 'OPEN', ?, ?, ?)
        """, workOrderId, machineId, TS, TS);
    return workOrderId;
  }

  private UUID ensureMachine() {
    var plantId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?, 'P', 'Test', ?, ?)
        """, plantId, TS, TS);
    var groupId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)
        VALUES (?, ?, 'Test', ?, ?)
        """, groupId, plantId, TS, TS);
    var machineId = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, status, created_at, updated_at)
        VALUES (?, ?, ?, 'M', 'ACTIVE', ?, ?)
        """, machineId, plantId, groupId, TS, TS);
    return machineId;
  }

  private UUID insertRequest(String workOrderId, String status) {
    var id = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, work_order_id, quantity, status, requested_by, requested_at, created_at, updated_at)
        VALUES (?, 'SERVICE_EXTERNAL', ?, 1, ?, ?, ?, ?, ?)
        """, id, workOrderId, status, UUID.randomUUID(), TS, TS, TS);
    return id;
  }
}