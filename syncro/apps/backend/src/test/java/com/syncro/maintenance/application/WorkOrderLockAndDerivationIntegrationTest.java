package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DW-136 + DW-138: real-Postgres coverage for the pessimistic lock finders and the
 * end-to-end derived PENDING_SPAREPART derivation wired to the real
 * {@code SparepartRequestReadinessService} ({@code @Primary} bean, not a mock).
 */
class WorkOrderLockAndDerivationIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private WorkOrderRepository workOrders;

  @Autowired
  private WorkOrderService workOrderService;

  @Test
  @DisplayName("DW-136 P0 findByIdForUpdate returns the seeded row against real Postgres")
  void findByIdForUpdateLoadsRow() {
    var chain = seedChain();

    var locked = workOrders.findByIdForUpdate(chain.workOrderId);

    assertThat(locked).isPresent();
    assertThat(locked.get().getId()).isEqualTo(chain.workOrderId);
    assertThat(locked.get().getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("DW-136 P0 findByParentIdForUpdate returns child rows against real Postgres")
  void findByParentIdForUpdateLoadsChildren() {
    var chain = seedChain();
    var childId = "WO-DW-CHILD-" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("""
        INSERT INTO work_orders (id, source, parent_id, status, machine_id, created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', ?, 'OPEN', ?::uuid, now(), now(), 1)
        """, childId, chain.workOrderId, chain.machineId.toString());

    var children = workOrders.findByParentIdForUpdate(chain.workOrderId);

    assertThat(children).hasSize(1);
    assertThat(children.getFirst().getId()).isEqualTo(childId);
  }

  @Test
  @DisplayName("DW-138 P0 recompute derives PENDING_SPAREPART via the real readiness port")
  void recomputeDerivesPendingSparepartWithRealPort() {
    var chain = seedChain();
    seedLiveRequest(chain.workOrderId, chain.machineId);

    workOrderService.recomputeProcurementState(chain.workOrderId);

    var status = jdbc.queryForObject(
        "SELECT status FROM work_orders WHERE id = ?", String.class, chain.workOrderId);
    assertThat(status).isEqualTo("PENDING_SPAREPART");
    // The DERIVED/SYSTEM history row is written (AD-5 evidence).
    var history = jdbc.queryForObject("""
        SELECT count(*) FROM work_order_status_history
        WHERE work_order_id = ? AND to_status = 'PENDING_SPAREPART' AND source = 'DERIVED'
        """, Integer.class, chain.workOrderId);
    assertThat(history).isEqualTo(1);
  }

  @Test
  @DisplayName("DW-138 P0 recompute resumes IN_PROGRESS when no live non-READY request exists")
  void recomputeResumesInProgressWithRealPort() {
    var chain = seedChain();

    workOrderService.recomputeProcurementState(chain.workOrderId);

    var status = jdbc.queryForObject(
        "SELECT status FROM work_orders WHERE id = ?", String.class, chain.workOrderId);
    assertThat(status).isEqualTo("IN_PROGRESS");
  }

  private Chain seedChain() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var workOrderId = "WO-DW-" + UUID.randomUUID().toString().substring(0, 8);

    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at)
        VALUES (?::uuid, 'DW-PLANT', 'DW Plant', now(), now())
        """, plantId.toString());
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, 'DW Group', now(), now())
        """, groupId.toString(), plantId.toString());
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?::uuid, 'DW-MC', 'DW Machine', 'ACTIVE', now(), now())
        """, machineId.toString(), plantId.toString(), groupId.toString());
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at)
        VALUES (?::uuid, '99', 'DW Category', now(), now())
        """, categoryId.toString());
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, category_id, machine_id, description, created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', 'IN_PROGRESS', ?::uuid, ?::uuid, 'DW derivation test', now(), now(), 1)
        """, workOrderId, categoryId.toString(), machineId.toString());

    return new Chain(workOrderId, machineId);
  }

  private void seedLiveRequest(String workOrderId, UUID machineId) {
    var actor = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO sparepart_requests (id, request_type, work_order_id, machine_id, quantity, status,
                                        requested_by, requested_at, created_at, updated_at)
        VALUES (?::uuid, 'SPAREPART', ?, ?::uuid, 1, 'REQUESTED', ?::uuid, now(), now(), now())
        """, UUID.randomUUID().toString(), workOrderId, machineId.toString(), actor.toString());
  }

  private record Chain(String workOrderId, UUID machineId) {
  }
}
