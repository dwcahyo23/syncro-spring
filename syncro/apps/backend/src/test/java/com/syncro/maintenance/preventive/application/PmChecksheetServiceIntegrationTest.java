package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ReviseChecksheetCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 19-1 integration test: PM checksheet revision/approval workflow against
 * a real Postgres container (blueprint F2/F3). Covers the full create → revise →
 * approve → active-pointer chain including the second-approval path (deactivate
 * old + pointer update), workflow guards, audit trail, and frequency seeding.
 */
class PmChecksheetServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

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
  /** The same Clock bean the service uses — INT-008 asserts against it, not wall clock. */
  @Autowired
  private Clock clock;

  private PlantEntity plant;
  private MachineGroupEntity group;
  private MachineEntity machine;
  private AuthenticatedUser leaderUser;
  private AuthenticatedUser staffUser;
  private UUID frequencyId;

  @BeforeEach
  void setUp() {
    plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "P" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));

    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        leaderId, "leader-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SECTION_LEADER, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(
        leaderId, plant.getId(), T0));
    // SECTION_LEADER scope comes from a LEADER responsibility on the machine group.
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, T0, T0));
    leaderUser = new AuthenticatedUser(leaderId.toString(),
        "leader@test", ApplicationRole.SECTION_LEADER);

    var staffId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        staffId, "staff-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.STAFF_MAINTENANCE, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(
        staffId, plant.getId(), T0));
    staffUser = new AuthenticatedUser(staffId.toString(),
        "staff@test", ApplicationRole.STAFF_MAINTENANCE);

    // V7 seeds MONTHLY/ANNUAL; resolve the MONTHLY id.
    frequencyId = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY"))
        .findFirst().orElseThrow().id();
  }

  @Test
  @DisplayName("19.1-INT-001 P0 create → revise → approve: pointer created, no deactivation yet")
  void firstApprovalCreatesPointer() {
    // Create revision 1 (unapproved, inactive)
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    assertThat(r1.revisionNo()).isEqualTo(1);
    assertThat(r1.isActive()).isFalse();
    assertThat(r1.approvedBy()).isNull();

    // No pointer row exists before any approval.
    assertThatThrownBy(() -> checksheets.getActive(leaderUser, machine.getId(), frequencyId))
        .isInstanceOf(PmChecksheetService.ActiveChecksheetNotFoundException.class);

    // Revise → revision 2, supersedes = r1
    var r2 = checksheets.revise(leaderUser, r1.id(),
        new ReviseChecksheetCommand("Annual review"));
    assertThat(r2.revisionNo()).isEqualTo(2);
    assertThat(r2.supersedes()).isEqualTo(r1.id());
    assertThat(r2.isActive()).isFalse();

    // Approve revision 2 → stamps approver, activates, creates pointer.
    // r1 was never active, so nothing is deactivated on this first approval.
    var approved = checksheets.approve(leaderUser, r2.id(),
        new ApproveChecksheetCommand(null));
    assertThat(approved.approvedBy()).isNotNull();
    assertThat(approved.approvedAt()).isNotNull();
    assertThat(approved.isActive()).isTrue();

    // Active pointer points at r2.
    var active = checksheets.getActive(leaderUser, machine.getId(), frequencyId);
    assertThat(active.checksheetId()).isEqualTo(approved.id());
    assertThat(active.revisionNo()).isEqualTo(2);

    // r1 stays inactive (it was created inactive; no deactivation needed).
    assertThat(checksheets.get(leaderUser, r1.id()).isActive()).isFalse();
  }

  @Test
  @DisplayName("19.1-INT-002 P0 second approval deactivates the old revision and updates the pointer")
  void secondApprovalDeactivatesOldAndFlipsPointer() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    checksheets.approve(leaderUser, r1.id(), new ApproveChecksheetCommand(null));

    // r1 is now the active revision and the pointer targets it.
    assertThat(checksheets.get(leaderUser, r1.id()).isActive()).isTrue();
    assertThat(checksheets.getActive(leaderUser, machine.getId(), frequencyId).checksheetId())
        .isEqualTo(r1.id());

    var r2 = checksheets.revise(leaderUser, r1.id(),
        new ReviseChecksheetCommand("Revision 2"));
    checksheets.approve(leaderUser, r2.id(), new ApproveChecksheetCommand(null));

    // deactivate() ran on r1; the pointer update branch ran to r2.
    assertThat(checksheets.get(leaderUser, r1.id()).isActive()).isFalse();
    var active = checksheets.getActive(leaderUser, machine.getId(), frequencyId);
    assertThat(active.checksheetId()).isEqualTo(r2.id());
    assertThat(active.revisionNo()).isEqualTo(2);

    // Audit trail: r1 has CREATE + approve UPDATE + deactivate UPDATE; r2 CREATE + approve UPDATE.
    var r1Audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(r1.id());
    assertThat(r1Audit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_CHECKSHEET);
      assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    });
    assertThat(r1Audit).anySatisfy(entry ->
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE));
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(r2.id()))
        .anySatisfy(entry ->
            assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_CHECKSHEET));
  }

  @Test
  @DisplayName("19.1-INT-003 P0 approving an already-approved revision is rejected")
  void approveAlreadyApprovedRejected() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    checksheets.approve(leaderUser, r1.id(), new ApproveChecksheetCommand(null));

    assertThatThrownBy(() ->
        checksheets.approve(leaderUser, r1.id(), new ApproveChecksheetCommand(null)))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
  }

  @Test
  @DisplayName("19.1-INT-004 P0 approving a stale (non-latest) revision is rejected")
  void approveStaleRevisionRejected() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    checksheets.revise(leaderUser, r1.id(), new ReviseChecksheetCommand("newer"));

    // r1 is unapproved but no longer the latest revision → pointer regression guard.
    assertThatThrownBy(() ->
        checksheets.approve(leaderUser, r1.id(), new ApproveChecksheetCommand(null)))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
  }

  @Test
  @DisplayName("19.1-INT-005 P0 revising a superseded revision is rejected")
  void reviseSupersededRejected() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    checksheets.revise(leaderUser, r1.id(), new ReviseChecksheetCommand("r2"));

    assertThatThrownBy(() ->
        checksheets.revise(leaderUser, r1.id(), new ReviseChecksheetCommand("fork")))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
  }

  @Test
  @DisplayName("19.1-INT-006 P0 creating a second checksheet for the same pair is rejected")
  void duplicateChecksheetPairRejected() {
    checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));

    assertThatThrownBy(() ->
        checksheets.create(staffUser,
            new CreateChecksheetCommand(machine.getId(), frequencyId, null)))
        .isInstanceOf(PmChecksheetService.ChecksheetAlreadyExistsException.class);
  }

  @Test
  @DisplayName("19.1-INT-007 P0 STAFF_MAINTENANCE can create but cannot approve")
  void staffCannotApprove() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));

    // STAFF_MAINTENANCE cannot approve (leader role required)
    assertThatThrownBy(() ->
        checksheets.approve(staffUser, r1.id(), new ApproveChecksheetCommand(null)))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
  }

  @Test
  @DisplayName("19.1-INT-008 P0 approve with null effectiveDate defaults to today from the injected clock")
  void approveDefaultsEffectiveDateToToday() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    var approved = checksheets.approve(leaderUser, r1.id(), new ApproveChecksheetCommand(null));

    // Same Clock bean the service uses — never wall clock (flaky across midnight).
    assertThat(approved.effectiveDate()).isEqualTo(LocalDate.now(clock));
  }

  @Test
  @DisplayName("19.1-INT-009 P0 duplicate frequency code is rejected (constraint backstop → 409)")
  void duplicateFrequencyCodeRejected() {
    // MONTHLY already exists (V7 seed); try to create with same code
    assertThatThrownBy(() ->
        frequencies.create(staffUser, new PmFrequencyService.CreateFrequencyCommand(
            "MONTHLY", "Monthly copy", null, null, null)))
        .isInstanceOf(PmFrequencyService.DuplicateFrequencyCodeException.class);
  }

  @Test
  @DisplayName("19.1-INT-010 P0 frequency list is ordered by sort_order asc")
  void frequencyListOrderedBySortOrder() {
    var list = frequencies.list();
    assertThat(list).extracting(PmFrequencyService.FrequencyView::code)
        .startsWith("MONTHLY", "ANNUAL");
    assertThat(list).extracting(PmFrequencyService.FrequencyView::sortOrder).isSorted();
  }

  @Test
  @DisplayName("19.1-INT-011 P0 checksheet list filters: pair asc, machine desc, frequency desc")
  void checksheetListFilterBranches() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    var r2 = checksheets.revise(leaderUser, r1.id(), new ReviseChecksheetCommand("r2"));

    var pair = checksheets.list(leaderUser, machine.getId(), frequencyId);
    assertThat(pair).extracting(PmChecksheetService.ChecksheetView::revisionNo)
        .containsExactly(1, 2);

    var byMachine = checksheets.list(leaderUser, machine.getId(), null);
    assertThat(byMachine).extracting(PmChecksheetService.ChecksheetView::revisionNo)
        .containsExactly(2, 1);

    var byFrequency = checksheets.list(leaderUser, null, frequencyId);
    assertThat(byFrequency).extracting(PmChecksheetService.ChecksheetView::id)
        .containsExactly(r2.id(), r1.id());
  }

  @Test
  @DisplayName("19.1-INT-012 P0 out-of-scope user cannot read another plant's checksheet")
  void outOfScopeReadRejected() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));

    // A second SECTION_LEADER with no responsibility on this machine's group.
    var otherId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        otherId, "other-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SECTION_LEADER, true, T0, T0));
    var otherUser = new AuthenticatedUser(otherId.toString(),
        "other@test", ApplicationRole.SECTION_LEADER);

    assertThatThrownBy(() -> checksheets.get(otherUser, r1.id()))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
    assertThatThrownBy(() -> checksheets.getActive(otherUser, machine.getId(), frequencyId))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
    assertThat(checksheets.list(otherUser, null, null)).isEmpty();
  }

  @Test
  @DisplayName("19.1-INT-013 P0 out-of-scope leader cannot create/revise/approve")
  void outOfScopeMutationRejected() {
    // A SECTION_LEADER of a DIFFERENT plant/group — no scope on this machine.
    var otherPlant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "X" + UUID.randomUUID().toString().substring(0, 8), "Other", T0, T0));
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
    var otherUser = new AuthenticatedUser(otherId.toString(),
        "other@test", ApplicationRole.SECTION_LEADER);

    assertThatThrownBy(() -> checksheets.create(otherUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null)))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);

    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    assertThatThrownBy(() -> checksheets.revise(otherUser, r1.id(),
        new ReviseChecksheetCommand("nope")))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
    assertThatThrownBy(() -> checksheets.approve(otherUser, r1.id(),
        new ApproveChecksheetCommand(null)))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
  }

  @Test
  @DisplayName("19.1-INT-014 P0 SUPER_ADMIN with no plant assignments bypasses every gate")
  void superAdminBypassesScope() {
    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        adminId, "admin-" + UUID.randomUUID() + "@test", "hash",
        ApplicationRole.SUPER_ADMIN, true, T0, T0));
    // Deliberately NO plant assignment and NO machine responsibility.
    var admin = new AuthenticatedUser(adminId.toString(), "admin@test", ApplicationRole.SUPER_ADMIN);

    var r1 = checksheets.create(admin,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    var approved = checksheets.approve(admin, r1.id(), new ApproveChecksheetCommand(null));
    assertThat(approved.isActive()).isTrue();

    assertThat(checksheets.getActive(admin, machine.getId(), frequencyId).checksheetId())
        .isEqualTo(r1.id());
    assertThat(checksheets.get(admin, r1.id()).id()).isEqualTo(r1.id());
    // plantIds == null → unrestricted list branch.
    assertThat(checksheets.list(admin, machine.getId(), frequencyId)).hasSize(1);
  }

  @Test
  @DisplayName("19.1-INT-015 P0 create against an inactive frequency is rejected (400)")
  void createAgainstInactiveFrequencyRejected() {
    var monthly = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY")).findFirst().orElseThrow();
    frequencies.update(staffUser, monthly.id(),
        new PmFrequencyService.UpdateFrequencyCommand("Monthly", null, null, false));

    assertThatThrownBy(() -> checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), monthly.id(), null)))
        .isInstanceOf(PmChecksheetService.ChecksheetValidationException.class);
  }

  @Test
  @DisplayName("19.1-INT-016 P0 revise against an inactive frequency is rejected (400)")
  void reviseAgainstInactiveFrequencyRejected() {
    var r1 = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null));
    frequencies.update(staffUser, frequencyId,
        new PmFrequencyService.UpdateFrequencyCommand("Monthly", null, null, false));

    assertThatThrownBy(() -> checksheets.revise(leaderUser, r1.id(),
        new ReviseChecksheetCommand("nope")))
        .isInstanceOf(PmChecksheetService.ChecksheetValidationException.class);
  }

  @Test
  @DisplayName("19.1-INT-017 P0 frequency update null-merges description/sortOrder/isActive and is audited")
  void frequencyUpdateNullMergeAndAudit() {
    var created = frequencies.create(staffUser, new PmFrequencyService.CreateFrequencyCommand(
        "WEEKLY-" + UUID.randomUUID().toString().substring(0, 8), "Weekly", "Original description",
        5, true));

    // PUT with only {name}: description/sortOrder/isActive must keep their values.
    var updated = frequencies.update(staffUser, created.id(),
        new PmFrequencyService.UpdateFrequencyCommand("Weekly v2", null, null, null));

    assertThat(updated.name()).isEqualTo("Weekly v2");
    assertThat(updated.description()).isEqualTo("Original description");
    assertThat(updated.sortOrder()).isEqualTo(5);
    assertThat(updated.active()).isTrue();
    assertThat(updated.createdAt()).isEqualTo(created.createdAt());

    var audit = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(audit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_FREQUENCY);
      assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    });
  }
}
