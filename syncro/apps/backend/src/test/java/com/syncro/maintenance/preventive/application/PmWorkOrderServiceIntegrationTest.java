package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmScheduleDateRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmWorkOrderRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 19-4 integration test: PM work orders against a real Postgres container
 * (blueprint F6). Covers generate from an ACTIVE schedule (12 SCHEDULED workorders
 * with snapshots + audit CREATE), idempotent re-generate and per-date skip, the
 * strict lifecycle assign → start → complete with stamps and per-step audit UPDATE
 * rows, out-of-order and terminal re-transition rejection, the Clock-based overdue
 * sweep (past-due non-terminal → OVERDUE, completed + future untouched, count
 * returned), the duplicate-period unique-index backstop, the leader + assignee
 * gates, unknown-reference 404s, SUPER_ADMIN bypass, and scope-filtered reads.
 */
class PmWorkOrderServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  @Autowired
  private PmWorkOrderService workOrders;
  @Autowired
  private PmScheduleService schedules;
  @Autowired
  private PmChecksheetService checksheets;
  @Autowired
  private PmFrequencyService frequencies;
  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private AuthUserPlantAssignmentRepository assignments;
  @Autowired
  private MachineResponsibilityRepository responsibilities;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private PmWorkOrderRepository workOrderRows;
  @Autowired
  private PmScheduleDateRepository scheduleDates;
  /** The same Clock bean the service uses — sweep eligibility never uses wall clock. */
  @Autowired
  private Clock clock;

  private PlantEntity plant;
  private MachineEntity machine;
  private AuthenticatedUser leaderUser;
  private AuthenticatedUser staffUser;
  private AuthenticatedUser managerUser;
  private AuthenticatedUser techUser;
  private UUID technicianId;
  private UUID monthlyFrequencyId;

  @BeforeEach
  void setUp() {
    plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));

    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        leaderId, "leader-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SECTION_LEADER, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(leaderId, plant.getId(), T0));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, T0, T0));
    leaderUser = new AuthenticatedUser(leaderId.toString(), "leader@test",
        ApplicationRole.SECTION_LEADER);

    var staffId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        staffId, "staff-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.STAFF_MAINTENANCE, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(staffId, plant.getId(), T0));
    staffUser = new AuthenticatedUser(staffId.toString(), "staff@test",
        ApplicationRole.STAFF_MAINTENANCE);

    var managerId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        managerId, "manager-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.MANAGER_MAINTENANCE, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(managerId, plant.getId(), T0));
    managerUser = new AuthenticatedUser(managerId.toString(), "manager@test",
        ApplicationRole.MANAGER_MAINTENANCE);

    technicianId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        technicianId, "tech-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.TECHNICIAN, true, T0, T0));
    techUser = new AuthenticatedUser(technicianId.toString(), "tech@test",
        ApplicationRole.TECHNICIAN);

    // V7 seeds MONTHLY/ANNUAL.
    monthlyFrequencyId = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow().id();
  }

  /** Approved monthly checksheet on the fixture machine (15th anchor → 12 dates). */
  private UUID approvedMonthlyChecksheet() {
    var id = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), monthlyFrequencyId, null)).id();
    checksheets.approve(leaderUser, id, new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));
    return id;
  }

  /** Drives the full 19-3 approval chain to ACTIVE for a checksheet + year. */
  private UUID activeSchedule(UUID checksheetId, int year) {
    return activeSchedule(checksheetId, year, plant, machine, staffUser, leaderUser);
  }

  /**
   * Creator/approver-parameterized variant: schedule creation is plant-scoped per
   * creator, and 19-3 approve/activate steps re-check requireMutationAccess against
   * the machine — so approvals need a leader whose scope covers the schedule's
   * machine.
   */
  private UUID activeSchedule(UUID checksheetId, int year, PlantEntity targetPlant,
      MachineEntity targetMachine, AuthenticatedUser creator, AuthenticatedUser approver) {
    var created = schedules.create(creator,
        new CreateScheduleCommand(targetPlant.getId(), targetMachine.getId(), checksheetId,
            year));
    schedules.submit(creator, created.id());
    schedules.approveSpv(approver, created.id());
    schedules.approveProd(approver, created.id());
    schedules.activate(approver, created.id());
    return created.id();
  }

  // -------------------------------------------------------------------------
  // AC1: generate — snapshots, audit, idempotency
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-INT-001 P0 generate from ACTIVE schedule creates 12 SCHEDULED workorders with snapshots + audit CREATE")
  void generateCreatesWorkordersWithSnapshots() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);

    var created = workOrders.generate(leaderUser, scheduleId);
    assertThat(created).hasSize(12);
    assertThat(created).allMatch(w -> w.status() == PmWorkOrderStatus.SCHEDULED);
    var first = created.getFirst();
    assertThat(first.machineId()).isEqualTo(machine.getId());
    assertThat(first.templateId()).isNotNull();
    assertThat(first.frequencyCode()).isEqualTo("MONTHLY");
    assertThat(first.frequencyName()).isEqualTo("Monthly");
    assertThat(first.templateRevision()).isEqualTo(1);
    assertThat(first.scheduledDate()).isEqualTo(LocalDate.of(2027, 1, 15));
    assertThat(first.assignedTechnicianId()).isNull();
    assertThat(first.startedAt()).isNull();
    assertThat(first.completedAt()).isNull();

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(first.id());
    assertThat(audit).hasSize(1);
    assertThat(audit.getFirst().getEntityType()).isEqualTo(AuditEntityType.PM_WORK_ORDER);
    assertThat(audit.getFirst().getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.getFirst().getPlantId()).isEqualTo(plant.getId());
    assertThat(audit.getFirst().getEntityLabel())
        .isEqualTo(machine.getCode() + "@2027-01-15");
    assertThat(audit.getFirst().getNewValue()).contains("SCHEDULED");
    assertThat(audit.getFirst().getPreviousValue()).isNull();
  }

  @Test
  @DisplayName("19.4-INT-002 P0 re-generate the same schedule creates zero (idempotent per period)")
  void regenerateIsIdempotent() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    assertThat(workOrders.generate(leaderUser, scheduleId)).hasSize(12);

    assertThat(workOrders.generate(leaderUser, scheduleId)).isEmpty();
    assertThat(workOrderRows.count()).isEqualTo(12);
  }

  @Test
  @DisplayName("19.4-INT-003 P0 generate against a non-ACTIVE schedule → 409; unknown schedule → 404")
  void generateGuardsScheduleState() {
    var checksheetId = approvedMonthlyChecksheet();
    var draft = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    assertThatThrownBy(() -> workOrders.generate(leaderUser, draft.id()))
        .isInstanceOf(PmWorkOrderService.InvalidScheduleStateException.class);
    assertThatThrownBy(() -> workOrders.generate(leaderUser, UUID.randomUUID()))
        .isInstanceOf(PmWorkOrderService.PmScheduleNotFoundException.class);
  }

  @Test
  @DisplayName("19.4-INT-004 P0 duplicate (machine, template, scheduled_date) period is rejected by the V10 unique index")
  void duplicatePeriodRejectedByIndex() {
    var now = Instant.now(clock);
    var templateId = UUID.randomUUID();
    var date = LocalDate.of(2027, 3, 15);
    workOrderRows.saveAndFlush(new PmWorkOrderEntity(UUID.randomUUID(), machine.getId(),
        templateId, null, null, null, null, PmWorkOrderStatus.SCHEDULED, null, date,
        null, null, null, now, now));

    assertThatThrownBy(() -> workOrderRows.saveAndFlush(new PmWorkOrderEntity(
        UUID.randomUUID(), machine.getId(), templateId, null, null, null, null,
        PmWorkOrderStatus.SCHEDULED, null, date, null, null, null, now, now)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_pm_work_orders_period");
  }

  @Test
  @DisplayName("19.4-INT-005 P0 generate only creates workorders for dates that lack one (per-date skip)")
  void generateSkipsDatesThatAlreadyHaveWorkorders() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    assertThat(workOrders.generate(leaderUser, scheduleId)).hasSize(12);

    // A newly added schedule date gets exactly one workorder on re-generate.
    var now = Instant.now(clock);
    scheduleDates.saveAndFlush(new PmScheduleDateEntity(UUID.randomUUID(), scheduleId,
        LocalDate.of(2027, 7, 22), PmScheduleDateStatus.SCHEDULED, now, now));
    var second = workOrders.generate(leaderUser, scheduleId);
    assertThat(second).hasSize(1);
    assertThat(second.getFirst().scheduledDate()).isEqualTo(LocalDate.of(2027, 7, 22));
    assertThat(workOrderRows.count()).isEqualTo(13);
  }

  // -------------------------------------------------------------------------
  // AC2: lifecycle assign → start → complete
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-INT-006 P0 assign → start → complete advances strictly with stamps + per-step audit UPDATE rows")
  void lifecycleAdvancesWithStampsAndAudit() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    var wo = workOrders.generate(leaderUser, scheduleId).getFirst();

    var assigned = workOrders.assign(leaderUser, wo.id(), technicianId);
    assertThat(assigned.status()).isEqualTo(PmWorkOrderStatus.ASSIGNED);
    assertThat(assigned.assignedTechnicianId()).isEqualTo(technicianId);

    var started = workOrders.start(techUser, wo.id());
    assertThat(started.status()).isEqualTo(PmWorkOrderStatus.IN_PROGRESS);
    assertThat(started.startedAt()).isNotNull();

    var completed = workOrders.complete(techUser, wo.id(), "https://cert.example/x.pdf");
    assertThat(completed.status()).isEqualTo(PmWorkOrderStatus.COMPLETED);
    assertThat(completed.completedAt()).isNotNull();
    assertThat(completed.certificateUrl()).isEqualTo("https://cert.example/x.pdf");

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(wo.id());
    assertThat(audit).filteredOn(e -> e.getAction() == AuditAction.UPDATE).hasSize(3);
    assertThat(audit).allSatisfy(entry ->
        assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_WORK_ORDER));
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getPreviousValue()).contains("SCHEDULED");
      assertThat(entry.getNewValue()).contains("ASSIGNED");
    });
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getPreviousValue()).contains("ASSIGNED");
      assertThat(entry.getNewValue()).contains("IN_PROGRESS");
    });
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getPreviousValue()).contains("IN_PROGRESS");
      assertThat(entry.getNewValue()).contains("COMPLETED");
    });
  }

  @Test
  @DisplayName("19.4-INT-007 P0 out-of-order and terminal re-transitions are rejected with 409 semantics")
  void outOfOrderTransitionsRejected() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    var wo = workOrders.generate(leaderUser, scheduleId).getFirst();
    workOrders.assign(leaderUser, wo.id(), technicianId);

    // Complete before start (assignee acting, so the gate passes and the state check fires).
    assertThatThrownBy(() -> workOrders.complete(techUser, wo.id(), null))
        .isInstanceOf(PmWorkOrderService.InvalidWorkOrderTransitionException.class);
    // Double assign.
    assertThatThrownBy(() -> workOrders.assign(leaderUser, wo.id(), technicianId))
        .isInstanceOf(PmWorkOrderService.InvalidWorkOrderTransitionException.class);

    workOrders.start(techUser, wo.id());
    var completed = workOrders.complete(techUser, wo.id(), null);
    assertThat(completed.status()).isEqualTo(PmWorkOrderStatus.COMPLETED);
    // Terminal COMPLETED rejects every further transition.
    assertThatThrownBy(() -> workOrders.assign(leaderUser, wo.id(), technicianId))
        .isInstanceOf(PmWorkOrderService.InvalidWorkOrderTransitionException.class);
    assertThatThrownBy(() -> workOrders.start(techUser, wo.id()))
        .isInstanceOf(PmWorkOrderService.InvalidWorkOrderTransitionException.class);
    assertThatThrownBy(() -> workOrders.complete(techUser, wo.id(), null))
        .isInstanceOf(PmWorkOrderService.InvalidWorkOrderTransitionException.class);
  }

  @Test
  @DisplayName("19.4-INT-008 P0 start/complete by a non-assignee → 403; unknown technician on assign → 404")
  void executionIsAssigneeScoped() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    var wo = workOrders.generate(leaderUser, scheduleId).getFirst();

    assertThatThrownBy(() -> workOrders.assign(leaderUser, wo.id(), UUID.randomUUID()))
        .isInstanceOf(PmWorkOrderService.TechnicianNotFoundException.class);

    workOrders.assign(leaderUser, wo.id(), technicianId);
    // The leader who assigned is not the assignee — cannot execute.
    assertThatThrownBy(() -> workOrders.start(leaderUser, wo.id()))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "tech2-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, T0, T0));
    var otherTech = new AuthenticatedUser(otherId.toString(), "tech2@test",
        ApplicationRole.TECHNICIAN);
    assertThatThrownBy(() -> workOrders.start(otherTech, wo.id()))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    assertThatThrownBy(() -> workOrders.complete(otherTech, wo.id(), null))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    // The assignee may execute.
    assertThat(workOrders.start(techUser, wo.id()).status())
        .isEqualTo(PmWorkOrderStatus.IN_PROGRESS);
  }

  // -------------------------------------------------------------------------
  // AC3: overdue sweep
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-INT-009 P0 sweep marks past-due non-terminal workorders OVERDUE (audited, counted) and leaves completed + future untouched")
  void sweepMarksPastDueOverdue() {
    var checksheetId = approvedMonthlyChecksheet();
    // A schedule entirely in the past relative to the injected clock, and one in the
    // future — same machine + checksheet, different years (allowed by 19-3's uq).
    var pastYear = LocalDate.now(clock).getYear() - 1;
    var pastSchedule = activeSchedule(checksheetId, pastYear);
    var generated = workOrders.generate(leaderUser, pastSchedule);
    assertThat(generated).hasSize(12);
    assertThat(generated.getFirst().scheduledDate()).isBefore(LocalDate.now(clock));

    // Complete one through the full chain — it must survive the sweep.
    var done = generated.getFirst();
    workOrders.assign(leaderUser, done.id(), technicianId);
    workOrders.start(techUser, done.id());
    workOrders.complete(techUser, done.id(), null);

    var futureSchedule = activeSchedule(checksheetId, pastYear + 2);
    var future = workOrders.generate(leaderUser, futureSchedule);
    assertThat(future).hasSize(12);

    var swept = workOrders.sweepOverdue(managerUser);
    assertThat(swept).isEqualTo(11);

    assertThat(workOrders.get(managerUser, done.id()).status())
        .isEqualTo(PmWorkOrderStatus.COMPLETED);
    assertThat(workOrders.get(managerUser, future.getFirst().id()).status())
        .isEqualTo(PmWorkOrderStatus.SCHEDULED);
    var overdue = workOrders.get(managerUser, generated.get(1).id());
    assertThat(overdue.status()).isEqualTo(PmWorkOrderStatus.OVERDUE);
    assertThat(overdue.startedAt()).isNull();
    assertThat(overdue.completedAt()).isNull();

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(overdue.id());
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
      assertThat(entry.getPreviousValue()).contains("SCHEDULED");
      assertThat(entry.getNewValue()).contains("OVERDUE");
    });

    // OVERDUE is terminal — a second sweep finds nothing new.
    assertThat(workOrders.sweepOverdue(managerUser)).isZero();
    assertThatThrownBy(() -> workOrders.assign(leaderUser, overdue.id(), technicianId))
        .isInstanceOf(PmWorkOrderService.InvalidWorkOrderTransitionException.class);
  }

  @Test
  @DisplayName("19.4-INT-009b P0 sweep leaves another plant's past-due workorders untouched and excludes them from the count")
  void sweepLeavesOtherPlantsWorkordersUntouched() {
    var pastYear = LocalDate.now(clock).getYear() - 1;
    var ownSchedule = activeSchedule(approvedMonthlyChecksheet(), pastYear);
    var ownGenerated = workOrders.generate(leaderUser, ownSchedule);
    assertThat(ownGenerated).hasSize(12);

    // A second plant with its own machine/checksheet/schedule — all 12 dates past-due.
    // The other plant needs its own MANAGER_MAINTENANCE: every 19-2 checksheet and
    // 19-3 schedule step is machine-scope gated, and 19-4 generate/assign/sweep
    // re-check the same gates against the schedule's machine.
    var otherPlant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "Y" + UUID.randomUUID().toString().substring(0, 8), "OtherSweep", T0, T0));
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), otherPlant, "Other Sweep Group", T0, T0));
    var otherMachine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), otherPlant, otherGroup,
        "MC-" + UUID.randomUUID().toString().substring(0, 8), "Other Sweep Machine",
        MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var otherManagerId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherManagerId,
        "othermanager-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.MANAGER_MAINTENANCE, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(otherManagerId,
        otherPlant.getId(), T0));
    var otherManagerUser = new AuthenticatedUser(otherManagerId.toString(), "othermanager@test",
        ApplicationRole.MANAGER_MAINTENANCE);
    var otherChecksheetId = checksheets.create(otherManagerUser,
        new CreateChecksheetCommand(otherMachine.getId(), monthlyFrequencyId, null)).id();
    checksheets.approve(otherManagerUser, otherChecksheetId,
        new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));
    var otherScheduleId = schedules.create(otherManagerUser,
        new CreateScheduleCommand(otherPlant.getId(), otherMachine.getId(), otherChecksheetId,
            pastYear)).id();
    schedules.submit(otherManagerUser, otherScheduleId);
    schedules.approveSpv(otherManagerUser, otherScheduleId);
    schedules.approveProd(otherManagerUser, otherScheduleId);
    schedules.activate(otherManagerUser, otherScheduleId);
    var otherGenerated = workOrders.generate(otherManagerUser, otherScheduleId);
    assertThat(otherGenerated).hasSize(12);

    // The plant-scoped leader sweeps only their own 12; the other plant's 12 stay
    // SCHEDULED. SUPER_ADMIN (unscoped) verifies both sides.
    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(adminId, "sweepadmin-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SUPER_ADMIN, true, T0, T0));
    var admin = new AuthenticatedUser(adminId.toString(), "sweepadmin@test",
        ApplicationRole.SUPER_ADMIN);
    var swept = workOrders.sweepOverdue(leaderUser);
    assertThat(swept).isEqualTo(12);
    for (var wo : otherGenerated) {
      assertThat(workOrders.get(admin, wo.id()).status())
          .isEqualTo(PmWorkOrderStatus.SCHEDULED);
    }
    assertThat(workOrders.get(admin, ownGenerated.getFirst().id()).status())
        .isEqualTo(PmWorkOrderStatus.OVERDUE);
  }

  @Test
  @DisplayName("19.4-INT-010 P0 sweep by a non-leader role → 403")
  void sweepRequiresLeaderRole() {
    assertThatThrownBy(() -> workOrders.sweepOverdue(staffUser))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    assertThatThrownBy(() -> workOrders.sweepOverdue(techUser))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
  }

  // -------------------------------------------------------------------------
  // Gates, scope, SUPER_ADMIN
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-INT-011 P0 STAFF_MAINTENANCE cannot generate/assign (leader gate)")
  void staffCannotGenerateOrAssign() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    assertThatThrownBy(() -> workOrders.generate(staffUser, scheduleId))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    var wo = workOrders.generate(leaderUser, scheduleId).getFirst();
    assertThatThrownBy(() -> workOrders.assign(staffUser, wo.id(), technicianId))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
  }

  @Test
  @DisplayName("19.4-INT-012 P0 out-of-scope leader: 403 generate/assign, invisible on reads")
  void outOfScopeLeaderForbiddenAndInvisible() {
    // Read-ordering note (reviewed, accepted): get() loads the row first, so an
    // out-of-scope caller gets 403 on a real row but 404 on a random id — a
    // low-value existence signal. This matches the 19-2/19-3 get() posture
    // (load-then-gate) and isolated workorder UUIDs are not enumerable, so the
    // leak is accepted rather than adding a scope pre-pass on every read.
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    var wo = workOrders.generate(leaderUser, scheduleId).getFirst();

    var otherPlant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "Y" + UUID.randomUUID().toString().substring(0, 8), "Other", T0, T0));
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), otherPlant, "Other Group", T0, T0));
    var otherMachine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), otherPlant, otherGroup,
        "MC-" + UUID.randomUUID().toString().substring(0, 8), "Other Machine",
        MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(otherId, "other-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, T0, T0));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), otherMachine.getId(), otherId, ResponsibilityLevel.LEADER, T0, T0));
    var otherUser = new AuthenticatedUser(otherId.toString(), "other@test",
        ApplicationRole.SECTION_LEADER);

    assertThatThrownBy(() -> workOrders.generate(otherUser, scheduleId))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    assertThatThrownBy(() -> workOrders.assign(otherUser, wo.id(), technicianId))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    assertThatThrownBy(() -> workOrders.get(otherUser, wo.id()))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderForbiddenException.class);
    assertThat(workOrders.list(otherUser, null, null)).isEmpty();
    // The in-scope leader still sees their own rows.
    assertThat(workOrders.list(leaderUser, null, null)).hasSize(12);
  }

  @Test
  @DisplayName("19.4-INT-013 P0 SUPER_ADMIN with no assignments bypasses every gate end-to-end")
  void superAdminBypassesEveryGate() {
    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(adminId, "admin-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SUPER_ADMIN, true, T0, T0));
    var admin = new AuthenticatedUser(adminId.toString(), "admin@test",
        ApplicationRole.SUPER_ADMIN);

    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    var wo = workOrders.generate(admin, scheduleId).getFirst();
    workOrders.assign(admin, wo.id(), technicianId);
    // Admin is not the assignee but may execute (SUPER_ADMIN bypass).
    assertThat(workOrders.start(admin, wo.id()).status())
        .isEqualTo(PmWorkOrderStatus.IN_PROGRESS);
    assertThat(workOrders.complete(admin, wo.id(), "cert").status())
        .isEqualTo(PmWorkOrderStatus.COMPLETED);
    assertThat(workOrders.get(admin, wo.id()).id()).isEqualTo(wo.id());
    assertThat(workOrders.list(admin, null, null)).hasSize(12);
    // 2027 dates are all future relative to the injected clock — nothing to sweep.
    assertThat(workOrders.sweepOverdue(admin)).isZero();
  }

  // -------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-INT-014 P0 list filters by status and machineId; unknown machine → 404; unknown workorder → 404")
  void listFiltersAndNotFound() {
    var scheduleId = activeSchedule(approvedMonthlyChecksheet(), 2027);
    var generated = workOrders.generate(leaderUser, scheduleId);
    workOrders.assign(leaderUser, generated.getFirst().id(), technicianId);

    assertThat(workOrders.list(leaderUser, PmWorkOrderStatus.ASSIGNED, null))
        .extracting(w -> w.id()).containsExactly(generated.getFirst().id());
    assertThat(workOrders.list(leaderUser, null, machine.getId())).hasSize(12);
    assertThat(workOrders.list(leaderUser, PmWorkOrderStatus.COMPLETED, null)).isEmpty();

    assertThatThrownBy(() -> workOrders.list(leaderUser, null, UUID.randomUUID()))
        .isInstanceOf(PmWorkOrderService.MachineNotFoundException.class);
    assertThatThrownBy(() -> workOrders.get(leaderUser, UUID.randomUUID()))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderNotFoundException.class);
    assertThatThrownBy(() -> workOrders.assign(leaderUser, UUID.randomUUID(), technicianId))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderNotFoundException.class);
    assertThatThrownBy(() -> workOrders.start(techUser, UUID.randomUUID()))
        .isInstanceOf(PmWorkOrderService.PmWorkOrderNotFoundException.class);
  }
}
