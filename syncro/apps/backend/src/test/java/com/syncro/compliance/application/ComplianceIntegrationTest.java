package com.syncro.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.compliance.application.EightDReportService.CreateEightDCommand;
import com.syncro.compliance.application.EightDReportService.EightDConflictException;
import com.syncro.compliance.application.EightDReportService.UpdateEightDCommand;
import com.syncro.compliance.application.EightDReportService.VerifyEffectivenessCommand;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.CreateNcCommand;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.application.NonConformanceService.NonConformanceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.UpdateNcCommand;
import com.syncro.compliance.domain.EightDStatus;
import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.compliance.infrastructure.db.NonConformanceRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 21-1 integration proof against a real PostgreSQL (V13 applied by Flyway):
 * severity persists on {@code non_conformances}, mutations write NON_CONFORMANCE /
 * EIGHT_D_REPORT audit rows with previous/new values, machine-scope filtering hides
 * out-of-scope NCs from a leader's list and detail, and the stable conflict codes
 * (DUPLICATE_IDENTIFIER, EIGHT_D_CONFLICT, INVALID_STATE_TRANSITION) surface from the
 * real unique constraints.
 */
class ComplianceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private MachineResponsibilityRepository responsibilities;
  @Autowired
  private NonConformanceRepository nonConformances;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkOrderRepository workOrders;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository categories;
  @Autowired
  private NonConformanceService ncService;
  @Autowired
  private EightDReportService eightDService;

  private record Fixture(PlantEntity plant, MachineGroupEntity groupA, MachineGroupEntity groupB,
      MachineEntity machineA, MachineEntity machineB) {
  }

  private Fixture fixture() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "P" + suffix, "Plant", T0, T0));
    var groupA = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant,
        "Group A " + suffix, T0, T0));
    var groupB = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant,
        "Group B " + suffix, T0, T0));
    var machineA = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, groupA,
        "MA-" + suffix, "Machine A", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var machineB = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, groupB,
        "MB-" + suffix, "Machine B", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    return new Fixture(plant, groupA, groupB, machineA, machineB);
  }

  /**
   * A persisted SECTION_LEADER with LEADER responsibility on the machine's group only —
   * no plant assignment, so the plant-OR-group scope predicate (review 21-1 P1) is
   * exercised on its group dimension: the sibling group's NC must stay hidden.
   */
  private AuthenticatedUser leaderOn(Fixture fx, MachineEntity machine) {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(),
        "nc-leader-" + suffix + "@syncro.test", "hash", ApplicationRole.SECTION_LEADER, true,
        T0, T0));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        machine.getId(), user.getId(), ResponsibilityLevel.LEADER, T0, T0));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(),
        ApplicationRole.SECTION_LEADER);
  }

  private AuthenticatedUser persistedUser(ApplicationRole role) {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(),
        "nc-" + role.name().toLowerCase() + "-" + suffix + "@syncro.test", "hash", role, true,
        T0, T0));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
  }

  @Test
  @DisplayName("21.1-INT-001 P0 V13 severity persists + create writes a NON_CONFORMANCE audit row")
  void severityPersistsAndAudits() {
    var fx = fixture();
    var admin = superAdmin();

    var created = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Dimensional drift on press head", NcSeverity.CRITICAL, null, null,
        fx.machineA().getId(), null, null));
    assertThat(created.severity()).isEqualTo(NcSeverity.CRITICAL);

    var reloaded = nonConformances.findById(created.id()).orElseThrow();
    assertThat(reloaded.getSeverity()).isEqualTo(NcSeverity.CRITICAL);
    assertThat(reloaded.getStatus()).isEqualTo(NcStatus.OPEN);

    var audits = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(audits).hasSize(1);
    var audit = audits.getFirst();
    assertThat(audit.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.getEntityType()).isEqualTo(AuditEntityType.NON_CONFORMANCE);
    assertThat(audit.getActorName()).isEqualTo("admin@syncro.test");
    assertThat(audit.getPreviousValue()).isNull();
    assertThat(audit.getNewValue()).contains("\"severity\":\"CRITICAL\"")
        .contains("\"status\":\"OPEN\"");
  }

  @Test
  @DisplayName("21.1-INT-002 P0 duplicate nc_number → DUPLICATE_IDENTIFIER (real unique constraint)")
  void duplicateNcNumber() {
    var admin = superAdmin();
    var number = "NC-" + UUID.randomUUID();
    ncService.create(admin, new CreateNcCommand(number, "First", null, null, null, null, null, null));

    assertThatThrownBy(() -> ncService.create(admin,
        new CreateNcCommand(number, "Second", null, null, null, null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.1-INT-003 P0 full lifecycle OPEN→IN_PROGRESS→CLOSED→VERIFIED with closedAt + UPDATE audits")
  void lifecycleTransitions() {
    var admin = superAdmin();
    var created = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Burr height out of spec", NcSeverity.MAJOR, null, null, null, null, null));

    var inProgress = ncService.update(admin, created.id(),
        new UpdateNcCommand(NcStatus.IN_PROGRESS, null, null, null, null, null, null));
    assertThat(inProgress.status()).isEqualTo(NcStatus.IN_PROGRESS);

    var closed = ncService.update(admin, created.id(),
        new UpdateNcCommand(NcStatus.CLOSED, null, null, "worn guide rail", "replaced rail",
            null, null));
    assertThat(closed.status()).isEqualTo(NcStatus.CLOSED);
    assertThat(closed.closedAt()).isNotNull();

    var verified = ncService.update(admin, created.id(),
        new UpdateNcCommand(NcStatus.VERIFIED, null, null, null, null, null, null));
    assertThat(verified.status()).isEqualTo(NcStatus.VERIFIED);

    // Illegal: VERIFIED back to OPEN.
    assertThatThrownBy(() -> ncService.update(admin, created.id(),
        new UpdateNcCommand(NcStatus.OPEN, null, null, null, null, null, null)))
        .isInstanceOf(InvalidStateTransitionException.class);

    var audits = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(audits).extracting(a -> a.getAction()).containsExactly(
        AuditAction.CREATE, AuditAction.UPDATE, AuditAction.UPDATE, AuditAction.UPDATE);
    // The close audit carries previous IN_PROGRESS → new CLOSED.
    var closeAudit = audits.get(2);
    assertThat(closeAudit.getPreviousValue()).contains("\"status\":\"IN_PROGRESS\"");
    assertThat(closeAudit.getNewValue()).contains("\"status\":\"CLOSED\"");
  }

  @Test
  @DisplayName("21.1-INT-004 P0 machine-scope filtering: leader sees own group + unlinked, not sibling")
  void scopeFiltering() {
    var fx = fixture();
    var admin = superAdmin();
    var leader = leaderOn(fx, fx.machineA());

    var inScope = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "In scope", null, null, null, fx.machineA().getId(), null, null));
    var outOfScope = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Out of scope", null, null, null, fx.machineB().getId(), null, null));
    var unlinked = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "No machine", null, null, null, null, null, null));

    var visible = ncService.list(leader, null);
    assertThat(visible).extracting(NonConformanceService.NcView::id)
        .contains(inScope.id(), unlinked.id())
        .doesNotContain(outOfScope.id());

    // Detail of the out-of-scope NC is 404 for the leader, 200 for SUPER_ADMIN.
    assertThatThrownBy(() -> ncService.get(leader, outOfScope.id()))
        .isInstanceOf(NonConformanceNotFoundException.class);
    assertThat(ncService.get(admin, outOfScope.id()).id()).isEqualTo(outOfScope.id());

    // Create-time scope gate (review 21-1 P3): the group-only leader cannot link an
    // NC to the sibling machine — the record would be invisible to its own creator.
    assertThatThrownBy(() -> ncService.create(leader, new CreateNcCommand(
        "NC-" + UUID.randomUUID(), "Out of scope link", null, null, null,
        fx.machineB().getId(), null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);

    // Audit plant dimension (review 21-1 P2): the machine-linked CREATE audit carries
    // the machine's plant; the unlinked one carries null.
    var linkedAudits = auditLogs.findByEntityIdOrderByCreatedAtAsc(inScope.id());
    assertThat(linkedAudits).hasSize(1);
    assertThat(linkedAudits.getFirst().getPlantId()).isEqualTo(fx.plant().getId());
    var unlinkedAudits = auditLogs.findByEntityIdOrderByCreatedAtAsc(unlinked.id());
    assertThat(unlinkedAudits).hasSize(1);
    assertThat(unlinkedAudits.getFirst().getPlantId()).isNull();
  }

  @Test
  @DisplayName("21.1-INT-005 P0 technician is read-only: unlinked NCs visible, mutation → FORBIDDEN")
  void technicianReadOnly() {
    var fx = fixture();
    var admin = superAdmin();
    var technician = persistedUser(ApplicationRole.TECHNICIAN);
    var machineLinked = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Drift", null, null, null, fx.machineA().getId(), null, null));
    var unlinked = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Plant-level finding", null, null, null, null, null, null));

    // I/O matrix: technician scope = own groups (none here) → only unlinked NCs visible.
    var visible = ncService.list(technician, null);
    assertThat(visible).extracting(NonConformanceService.NcView::id)
        .contains(unlinked.id())
        .doesNotContain(machineLinked.id());
    assertThatThrownBy(() -> ncService.create(technician,
        new CreateNcCommand("NC-" + UUID.randomUUID(), "Nope", null, null, null, null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.1-INT-006 P0 one 8D per NC → EIGHT_D_CONFLICT; verify-effectiveness audits EIGHT_D_REPORT")
  void eightDChain() {
    var admin = superAdmin();
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE);
    var created = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Drift", NcSeverity.MAJOR, null, null, null, null, null));

    var report = eightDService.create(admin, created.id(), new CreateEightDCommand(
        "8D-" + UUID.randomUUID(), Map.of("members", List.of("Andi", "Budi")), "Burr height",
        "Sorted 200 pcs", Map.of("cause", "worn guide", "verified", true), "Replace rail",
        "Replaced 2026-09-02", "Recurring wear", "Closed after 3 clean lots"));
    assertThat(report.status()).isEqualTo(EightDStatus.DRAFT);
    assertThat(report.d1Team()).containsEntry("members", List.of("Andi", "Budi"));
    assertThat(report.d4RootCause()).containsEntry("verified", true);

    // Second report for the same NC conflicts.
    assertThatThrownBy(() -> eightDService.create(admin, created.id(), new CreateEightDCommand(
        "8D-" + UUID.randomUUID(), null, null, null, null, null, null, null, null)))
        .isInstanceOf(EightDConflictException.class);

    // DRAFT → IN_PROGRESS → CLOSED via section update.
    eightDService.update(admin, created.id(),
        new UpdateEightDCommand(EightDStatus.IN_PROGRESS, null, null, null, null, null, null,
            null, null, null));
    eightDService.update(admin, created.id(),
        new UpdateEightDCommand(EightDStatus.CLOSED, null, null, null, null, null, null, null,
            null, null));

    // Verify by manager: stamps verifiedAt + audits UPDATE with previous/new.
    var verified = eightDService.verifyEffectiveness(manager, created.id(),
        new VerifyEffectivenessCommand(EightDStatus.EFFECTIVE));
    assertThat(verified.status()).isEqualTo(EightDStatus.EFFECTIVE);
    assertThat(verified.effectivenessVerifiedAt()).isNotNull();

    var audits = auditLogs.findByEntityIdOrderByCreatedAtAsc(report.id());
    assertThat(audits).extracting(a -> a.getEntityType())
        .containsOnly(AuditEntityType.EIGHT_D_REPORT);
    assertThat(audits).extracting(a -> a.getAction())
        .containsExactly(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.UPDATE,
            AuditAction.UPDATE);
    var last = audits.get(audits.size() - 1);
    assertThat(last.getPreviousValue()).contains("\"status\":\"CLOSED\"");
    assertThat(last.getNewValue()).contains("\"status\":\"EFFECTIVE\"")
        .contains("\"effectivenessVerifiedAt\"");
  }

  @Test
  @DisplayName("21.1-INT-007 P1 section leader cannot verify effectiveness (narrower gate)")
  void verifyNarrowerRoleGate() {
    var fx = fixture();
    var admin = superAdmin();
    var leader = leaderOn(fx, fx.machineA());
    var created = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Drift", null, null, null, fx.machineA().getId(), null, null));
    eightDService.create(admin, created.id(), new CreateEightDCommand("8D-" + UUID.randomUUID(),
        null, null, null, null, null, null, null, null));
    eightDService.update(admin, created.id(), new UpdateEightDCommand(EightDStatus.IN_PROGRESS,
        null, null, null, null, null, null, null, null, null));
    eightDService.update(admin, created.id(), new UpdateEightDCommand(EightDStatus.CLOSED,
        null, null, null, null, null, null, null, null, null));

    assertThatThrownBy(() -> eightDService.verifyEffectiveness(leader, created.id(),
        new VerifyEffectivenessCommand(EightDStatus.EFFECTIVE)))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.1-INT-008 P1 NC detail exposes its workOrderId evidence reference")
  void workOrderEvidenceReference() {
    var fx = fixture();
    var admin = superAdmin();
    // Real workorder row — the service pre-validates the evidence link (FK in V1).
    var category = categories.saveAndFlush(new com.syncro.maintenance.infrastructure.db
        .WorkOrderCategoryEntity(UUID.randomUUID(), "01", "Breakdown", null, T0, T0));
    var workOrder = workOrders.saveAndFlush(new com.syncro.maintenance.infrastructure.db
        .WorkOrderEntity("NC-EVID-" + UUID.randomUUID().toString().substring(0, 8), "INTERNAL",
        null, com.syncro.maintenance.domain.workorder.WorkOrderStatus.OPEN, category.getId(),
        fx.machineA().getId(), "Evidence carrier", 0L, null, null, null, T0, T0));

    var created = ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Drift", null, null, workOrder.getId(), null, null, null));
    assertThat(ncService.get(admin, created.id()).workOrderId()).isEqualTo(workOrder.getId());

    // Unknown workorder reference → 404 WORK_ORDER_NOT_FOUND (no dangling evidence link).
    assertThatThrownBy(() -> ncService.create(admin, new CreateNcCommand("NC-" + UUID.randomUUID(),
        "Drift", null, null, "NOPE-404", null, null, null)))
        .isInstanceOfSatisfying(NonConformanceService.ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("WORK_ORDER_NOT_FOUND"));
  }
}
