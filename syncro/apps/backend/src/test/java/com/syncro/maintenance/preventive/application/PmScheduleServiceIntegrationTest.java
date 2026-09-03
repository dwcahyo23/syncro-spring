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
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmScheduleService.CreateScheduleCommand;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 19-3 integration test: PM yearly schedules + schedule dates against a real
 * Postgres container (blueprint F5). Covers create-with-materialization (MONTHLY
 * clamped dates / ANNUAL single date, effective-date anchor + clock fallback), the
 * strict approval chain (submit → approve-spv → approve-prod → activate) with actor/
 * timestamp stamps and per-step audit rows, out-of-order transition rejection, the
 * leader-only approval gate, ACTIVE-gated date transitions with repeatable statuses,
 * unapproved/non-active checksheet rejection, duplicate schedule rejection, and
 * scope-filtered reads.
 */
class PmScheduleServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

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
  /** The same Clock bean the service uses — timestamp asserts never use wall clock. */
  @Autowired
  private Clock clock;

  private PlantEntity plant;
  private MachineEntity machine;
  private MachineEntity machine2;
  private AuthenticatedUser leaderUser;
  private AuthenticatedUser staffUser;
  private AuthenticatedUser managerUser;
  private UUID monthlyFrequencyId;
  private UUID annualFrequencyId;

  @BeforeEach
  void setUp() {
    plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    machine2 = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine 2", MachineStatus.ACTIVE, null, null, null, null, T0, T0));

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

    // V7 seeds MONTHLY/ANNUAL.
    monthlyFrequencyId = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow().id();
    annualFrequencyId = frequencies.list().stream()
        .filter(f -> f.code().equals("ANNUAL")).findFirst().orElseThrow().id();
  }

  /** Approves a checksheet with an explicit effective date → deterministic anchor. */
  private UUID approvedChecksheet(UUID frequencyId, LocalDate effectiveDate) {
    return approvedChecksheet(machine, frequencyId, effectiveDate);
  }

  /** Approves a checksheet on a given machine (each machine = one chain per frequency). */
  private UUID approvedChecksheet(MachineEntity target, UUID frequencyId, LocalDate effectiveDate) {
    var id = checksheets.create(staffUser,
        new CreateChecksheetCommand(target.getId(), frequencyId, null)).id();
    checksheets.approve(leaderUser, id, new ApproveChecksheetCommand(effectiveDate));
    return id;
  }

  // -------------------------------------------------------------------------
  // AC1: create materializes dates (MONTHLY clamped / ANNUAL single)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.3-INT-001 P0 MONTHLY schedule materializes 12 clamped dates at the effective-date anchor")
  void createMonthlyMaterializesTwelveDates() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var view = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    assertThat(view.status()).isEqualTo(PmScheduleStatus.DRAFT);
    assertThat(view.checksheetRevisionNo()).isEqualTo(1);
    assertThat(view.frequencyCode()).isEqualTo("MONTHLY");
    assertThat(view.year()).isEqualTo(2027);
    assertThat(view.dates()).hasSize(12);
    assertThat(view.dates()).extracting(d -> d.plannedDate())
        .containsExactly(
            LocalDate.of(2027, 1, 15), LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 15),
            LocalDate.of(2027, 4, 15), LocalDate.of(2027, 5, 15), LocalDate.of(2027, 6, 15),
            LocalDate.of(2027, 7, 15), LocalDate.of(2027, 8, 15), LocalDate.of(2027, 9, 15),
            LocalDate.of(2027, 10, 15), LocalDate.of(2027, 11, 15), LocalDate.of(2027, 12, 15));
    assertThat(view.dates()).allMatch(d -> d.status() == PmScheduleDateStatus.SCHEDULED);

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(view.id());
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_SCHEDULE);
      assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
      assertThat(entry.getPlantId()).isEqualTo(plant.getId());
    });
  }

  @Test
  @DisplayName("19.3-INT-002 P0 ANNUAL schedule materializes one date at the anchor month/day")
  void createAnnualMaterializesOneDate() {
    var checksheetId = approvedChecksheet(annualFrequencyId, LocalDate.of(2026, 6, 30));
    var view = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2028));

    assertThat(view.status()).isEqualTo(PmScheduleStatus.DRAFT);
    assertThat(view.frequencyCode()).isEqualTo("ANNUAL");
    assertThat(view.dates()).hasSize(1);
    assertThat(view.dates().getFirst().plannedDate()).isEqualTo(LocalDate.of(2028, 6, 30));
  }

  @Test
  @DisplayName("19.3-INT-003 P0 Feb 29 anchor clamps to Feb 28 on non-leap years and Feb 29 on leap years")
  void monthlyClampsFeb29Anchor() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2024, 2, 29));
    var view = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    assertThat(view.dates()).hasSize(12);
    assertThat(view.dates().get(1).plannedDate()).isEqualTo(LocalDate.of(2027, 2, 28));
    assertThat(view.dates().get(0).plannedDate()).isEqualTo(LocalDate.of(2027, 1, 29));
    // April (30 days) clamps a 31-day anchor — on a second machine because 19-1
    // allows only ONE checksheet chain per (machine, frequency).
    var viewLeap = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine2.getId(),
            approvedChecksheet(machine2, monthlyFrequencyId, LocalDate.of(2024, 1, 31)), 2028));
    assertThat(viewLeap.dates().get(3).plannedDate()).isEqualTo(LocalDate.of(2028, 4, 30));
  }

  @Test
  @DisplayName("19.3-INT-004 P0 checksheet without effectiveDate anchors on today from the injected clock")
  void createWithoutEffectiveDateAnchorsOnClock() {
    var checksheetId = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), monthlyFrequencyId, null)).id();
    checksheets.approve(leaderUser, checksheetId, new ApproveChecksheetCommand(null));

    var view = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));
    var anchorDay = LocalDate.now(clock).getDayOfMonth();
    assertThat(view.dates()).hasSize(12);
    // The anchor day is today's day-of-month — same injected clock, never wall clock.
    assertThat(view.dates().getFirst().plannedDate())
        .isEqualTo(LocalDate.of(2027, 1, Math.min(anchorDay, 31)));
  }

  // -------------------------------------------------------------------------
  // AC2: strict approval chain with stamps + audit
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.3-INT-005 P0 submit → approve-spv → approve-prod → activate advances strictly with stamps and audit rows")
  void approvalChainAdvancesWithStamps() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    var submitted = schedules.submit(staffUser, created.id());
    assertThat(submitted.status()).isEqualTo(PmScheduleStatus.PENDING_SPV_APPROVAL);
    assertThat(submitted.submittedBy()).isNotNull();
    assertThat(submitted.submittedAt()).isNotNull();

    var spv = schedules.approveSpv(leaderUser, created.id());
    assertThat(spv.status()).isEqualTo(PmScheduleStatus.PENDING_PRODUCTION_APPROVAL);
    assertThat(spv.approvedBySpv()).isEqualTo(UUID.fromString(leaderUser.id()));
    assertThat(spv.approvedAtSpv()).isNotNull();

    var prod = schedules.approveProd(managerUser, created.id());
    assertThat(prod.status()).isEqualTo(PmScheduleStatus.APPROVED);
    assertThat(prod.approvedByProd()).isEqualTo(UUID.fromString(managerUser.id()));
    assertThat(prod.approvedAtProd()).isNotNull();

    var active = schedules.activate(leaderUser, created.id());
    assertThat(active.status()).isEqualTo(PmScheduleStatus.ACTIVE);

    // Per-step audit UPDATE rows with previous/new status (all UUIDs stringified).
    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(audit).filteredOn(e -> e.getAction() == AuditAction.UPDATE).hasSize(4);
    assertThat(audit).allSatisfy(entry ->
        assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_SCHEDULE));
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getNewValue()).contains("PENDING_SPV_APPROVAL");
      assertThat(entry.getPreviousValue()).contains("DRAFT");
    });
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getNewValue()).contains("ACTIVE");
      assertThat(entry.getPreviousValue()).contains("APPROVED");
    });
  }

  @Test
  @DisplayName("19.3-INT-006 P0 any out-of-order transition is rejected with 409 semantics")
  void outOfOrderTransitionsRejected() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    // Approve-spv before submit.
    assertThatThrownBy(() -> schedules.approveSpv(leaderUser, created.id()))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    // Activate before approval.
    assertThatThrownBy(() -> schedules.activate(leaderUser, created.id()))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    // Double submit.
    schedules.submit(staffUser, created.id());
    assertThatThrownBy(() -> schedules.submit(staffUser, created.id()))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    // Submit after the chain has moved on.
    schedules.approveSpv(leaderUser, created.id());
    assertThatThrownBy(() -> schedules.submit(staffUser, created.id()))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
    // Approve-spv twice.
    schedules.approveProd(managerUser, created.id());
    assertThatThrownBy(() -> schedules.approveSpv(leaderUser, created.id()))
        .isInstanceOf(PmScheduleService.InvalidScheduleTransitionException.class);
  }

  @Test
  @DisplayName("19.3-INT-007 P0 STAFF_MAINTENANCE can submit but cannot approve-spv/approve-prod/activate")
  void staffCannotApprove() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    schedules.submit(staffUser, created.id());
    assertThatThrownBy(() -> schedules.approveSpv(staffUser, created.id()))
        .isInstanceOf(PmScheduleService.PmScheduleForbiddenException.class);

    schedules.approveSpv(leaderUser, created.id());
    assertThatThrownBy(() -> schedules.approveProd(staffUser, created.id()))
        .isInstanceOf(PmScheduleService.PmScheduleForbiddenException.class);

    schedules.approveProd(leaderUser, created.id());
    assertThatThrownBy(() -> schedules.activate(staffUser, created.id()))
        .isInstanceOf(PmScheduleService.PmScheduleForbiddenException.class);
  }

  @Test
  @DisplayName("19.3-INT-008 P0 SUPER_ADMIN with no assignments bypasses every gate end-to-end")
  void superAdminBypassesEveryGate() {
    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        adminId, "admin-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SUPER_ADMIN, true, T0, T0));
    // Deliberately NO plant assignment and NO machine responsibility.
    var admin = new AuthenticatedUser(adminId.toString(), "admin@test",
        ApplicationRole.SUPER_ADMIN);

    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(admin,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));
    var submitted = schedules.submit(admin, created.id());
    var spv = schedules.approveSpv(admin, created.id());
    var prod = schedules.approveProd(admin, created.id());
    var active = schedules.activate(admin, created.id());
    assertThat(submitted.status()).isEqualTo(PmScheduleStatus.PENDING_SPV_APPROVAL);
    assertThat(spv.status()).isEqualTo(PmScheduleStatus.PENDING_PRODUCTION_APPROVAL);
    assertThat(prod.status()).isEqualTo(PmScheduleStatus.APPROVED);
    assertThat(active.status()).isEqualTo(PmScheduleStatus.ACTIVE);
    assertThat(schedules.get(admin, created.id()).id()).isEqualTo(created.id());
    assertThat(schedules.list(admin, 2027, null)).hasSize(1);
  }

  // -------------------------------------------------------------------------
  // AC4: date transitions on ACTIVE schedules
  // -------------------------------------------------------------------------

  private UUID activeScheduleId() {
    return activeScheduleId(machine);
  }

  private UUID activeScheduleId(MachineEntity target) {
    var checksheetId = approvedChecksheet(target, monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), target.getId(), checksheetId, 2027));
    schedules.submit(staffUser, created.id());
    schedules.approveSpv(leaderUser, created.id());
    schedules.approveProd(leaderUser, created.id());
    schedules.activate(leaderUser, created.id());
    return created.id();
  }

  @Test
  @DisplayName("19.3-INT-009 P0 SCHEDULED date → EXECUTED on an ACTIVE schedule updates + audits")
  void scheduledDateExecuted() {
    var scheduleId = activeScheduleId();
    var dateId = schedules.get(leaderUser, scheduleId).dates().getFirst().id();

    var executed = schedules.transitionDate(leaderUser, scheduleId, dateId,
        PmScheduleDateStatus.EXECUTED);
    assertThat(executed.status()).isEqualTo(PmScheduleDateStatus.EXECUTED);

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(dateId);
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_SCHEDULE_DATE);
      assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
      assertThat(entry.getPreviousValue()).contains("SCHEDULED");
      assertThat(entry.getNewValue()).contains("EXECUTED");
      assertThat(entry.getPlantId()).isEqualTo(plant.getId());
    });
  }

  @Test
  @DisplayName("19.3-INT-009b P0 SCHEDULED date → MISSED on an ACTIVE schedule persists + audits")
  void scheduledDateMissed() {
    var scheduleId = activeScheduleId();
    var dateId = schedules.get(leaderUser, scheduleId).dates().getFirst().id();

    var missed = schedules.transitionDate(leaderUser, scheduleId, dateId,
        PmScheduleDateStatus.MISSED);
    assertThat(missed.status()).isEqualTo(PmScheduleDateStatus.MISSED);

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(dateId);
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_SCHEDULE_DATE);
      assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
      assertThat(entry.getPreviousValue()).contains("SCHEDULED");
      assertThat(entry.getNewValue()).contains("MISSED");
    });
  }

  @Test
  @DisplayName("19.3-INT-010 P0 RESCHEDULED → EXECUTED is allowed (repeatable statuses); invalid from-states rejected")
  void dateTransitionsRepeatableAndGuarded() {
    var scheduleId = activeScheduleId();
    var dateId = schedules.get(leaderUser, scheduleId).dates().getFirst().id();

    // EXECUTED → MISSED is invalid (EXECUTED/MISSED only from SCHEDULED).
    schedules.transitionDate(leaderUser, scheduleId, dateId, PmScheduleDateStatus.EXECUTED);
    assertThatThrownBy(() -> schedules.transitionDate(leaderUser, scheduleId, dateId,
        PmScheduleDateStatus.MISSED))
        .isInstanceOf(PmScheduleService.InvalidScheduleDateTransitionException.class);

    // EXECUTED → RESCHEDULED is allowed.
    var rescheduled = schedules.transitionDate(leaderUser, scheduleId, dateId,
        PmScheduleDateStatus.RESCHEDULED);
    assertThat(rescheduled.status()).isEqualTo(PmScheduleDateStatus.RESCHEDULED);

    // RESCHEDULED → EXECUTED is allowed (no terminal state).
    var reExecuted = schedules.transitionDate(leaderUser, scheduleId, dateId,
        PmScheduleDateStatus.EXECUTED);
    assertThat(reExecuted.status()).isEqualTo(PmScheduleDateStatus.EXECUTED);

    // SCHEDULED → SCHEDULED is a no-op and rejected.
    var secondDateId = schedules.get(leaderUser, scheduleId).dates().get(1).id();
    assertThatThrownBy(() -> schedules.transitionDate(leaderUser, scheduleId, secondDateId,
        PmScheduleDateStatus.SCHEDULED))
        .isInstanceOf(PmScheduleService.InvalidScheduleDateTransitionException.class);
  }

  @Test
  @DisplayName("19.3-INT-011 P0 date transition on a non-ACTIVE schedule is rejected")
  void dateTransitionRequiresActiveSchedule() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));
    var dateId = created.dates().getFirst().id();

    assertThatThrownBy(() -> schedules.transitionDate(leaderUser, created.id(), dateId,
        PmScheduleDateStatus.EXECUTED))
        .isInstanceOf(PmScheduleService.InvalidScheduleDateTransitionException.class);
  }

  @Test
  @DisplayName("19.3-INT-012 P0 date transition for another schedule's date is a 404")
  void dateTransitionForeignDateNotFound() {
    var scheduleId = activeScheduleId();
    var otherScheduleId = activeScheduleId(machine2);
    var foreignDateId = schedules.get(leaderUser, otherScheduleId).dates().getFirst().id();

    assertThatThrownBy(() -> schedules.transitionDate(leaderUser, scheduleId, foreignDateId,
        PmScheduleDateStatus.EXECUTED))
        .isInstanceOf(PmScheduleService.PmScheduleDateNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // AC5: create guards — unapproved checksheet, duplicate, plant/machine
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.3-INT-013 P0 unapproved checksheet → 409 INVALID_CHECKSHEET_STATE")
  void unapprovedChecksheetRejected() {
    var checksheetId = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), monthlyFrequencyId, null)).id();

    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.InvalidChecksheetStateException.class);
  }

  @Test
  @DisplayName("19.3-INT-014 P0 approved but non-active checksheet → 409 INVALID_CHECKSHEET_STATE")
  void nonActivePointerChecksheetRejected() {
    var r1 = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    // A newer revision supersedes r1 — the pointer now targets r2.
    var r2 = checksheets.revise(leaderUser, r1, new PmChecksheetService.ReviseChecksheetCommand("newer")).id();
    checksheets.approve(leaderUser, r2, new ApproveChecksheetCommand(LocalDate.of(2026, 3, 1)));

    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), r1, 2027)))
        .isInstanceOf(PmScheduleService.InvalidChecksheetStateException.class);
  }

  @Test
  @DisplayName("19.3-INT-015 P0 duplicate (plant, machine, checksheet, year) → 409 SCHEDULE_ALREADY_EXISTS")
  void duplicateScheduleRejected() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.ScheduleAlreadyExistsException.class);
  }

  @Test
  @DisplayName("19.3-INT-015b P0 create against a deactivated frequency → 400 ScheduleValidationException")
  void createAgainstInactiveFrequencyRejected() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    // Deactivate the frequency.
    frequencies.update(managerUser, monthlyFrequencyId,
        new PmFrequencyService.UpdateFrequencyCommand("Monthly", null, null, false));
    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.ScheduleValidationException.class);
  }

  @Test
  @DisplayName("19.3-INT-015c P0 create against an unsupported frequency code (neither MONTHLY nor ANNUAL) → 400 ScheduleValidationException")
  void createWithUnsupportedFrequencyCodeRejected() {
    var weekly = frequencies.create(managerUser, new PmFrequencyService.CreateFrequencyCommand(
        "WEEKLY-" + UUID.randomUUID().toString().substring(0, 8), "Weekly", null, 10, true));
    // The checksheet's frequency is MONTHLY — we need a checksheet on the WEEKLY frequency.
    // But the checksheet must be approved and the active pointer must point at it.
    // Create a second machine for the WEEKLY chain.
    var checksheetId = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine2.getId(), weekly.id(), null)).id();
    checksheets.approve(leaderUser, checksheetId, new ApproveChecksheetCommand(LocalDate.of(2026, 1, 15)));
    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine2.getId(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.ScheduleValidationException.class);
  }

  @Test
  @DisplayName("19.3-INT-016 P0 unknown plant / unknown machine / machine of another plant are rejected")
  void createReferencesResolved() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));

    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(UUID.randomUUID(), machine.getId(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.PlantNotFoundException.class);
    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), UUID.randomUUID(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.MachineNotFoundException.class);
    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), UUID.randomUUID(), 2027)))
        .isInstanceOf(PmScheduleService.PmChecksheetNotFoundException.class);

    var otherPlant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "X" + UUID.randomUUID().toString().substring(0, 8), "Other", T0, T0));
    assertThatThrownBy(() -> schedules.create(staffUser,
        new CreateScheduleCommand(otherPlant.getId(), machine.getId(), checksheetId, 2027)))
        .isInstanceOf(PmScheduleService.ScheduleValidationException.class);
  }

  // -------------------------------------------------------------------------
  // AC: scope-filtered reads
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.3-INT-017 P0 out-of-scope user cannot read or mutate another plant's schedule")
  void outOfScopeInvisibleAndForbidden() {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var created = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));

    var otherPlant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "Y" + UUID.randomUUID().toString().substring(0, 8), "Other", T0, T0));
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), otherPlant, "Other Group", T0, T0));
    var otherMachine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), otherPlant, otherGroup,
        "MC-" + UUID.randomUUID().toString().substring(0, 8), "Other Machine",
        MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        otherId, "other-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SECTION_LEADER, true, T0, T0));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), otherMachine.getId(), otherId, ResponsibilityLevel.LEADER, T0, T0));
    var otherUser = new AuthenticatedUser(otherId.toString(), "other@test",
        ApplicationRole.SECTION_LEADER);

    assertThat(schedules.list(otherUser, null, null)).isEmpty();
    assertThatThrownBy(() -> schedules.get(otherUser, created.id()))
        .isInstanceOf(PmScheduleService.PmScheduleForbiddenException.class);
    assertThatThrownBy(() -> schedules.submit(otherUser, created.id()))
        .isInstanceOf(PmScheduleService.PmScheduleForbiddenException.class);
  }

  @Test
  @DisplayName("19.3-INT-018 P0 list filters by year and status, ordered newest first")
  void listFiltersAndOrdering() throws Exception {
    var checksheetId = approvedChecksheet(monthlyFrequencyId, LocalDate.of(2026, 1, 15));
    var s2027 = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2027));
    // Distinct createdAt so the newest-first ordering is deterministic (the injected
    // Clock can otherwise produce identical microsecond timestamps for two creates).
    Thread.sleep(2);
    var s2028 = schedules.create(staffUser,
        new CreateScheduleCommand(plant.getId(), machine.getId(), checksheetId, 2028));

    assertThat(schedules.list(staffUser, 2027, null))
        .extracting(s -> s.id()).containsExactly(s2027.id());
    assertThat(schedules.list(staffUser, null, PmScheduleStatus.DRAFT))
        .extracting(s -> s.id()).containsExactlyInAnyOrder(s2027.id(), s2028.id());
    assertThat(schedules.list(staffUser, null, null))
        .extracting(s -> s.id()).containsExactly(s2028.id(), s2027.id());
  }
}
