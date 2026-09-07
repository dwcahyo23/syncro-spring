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
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.compliance.application.CalibrationService.CreateInstrumentCommand;
import com.syncro.compliance.application.CalibrationService.RecalibrateCommand;
import com.syncro.compliance.application.CalibrationService.UpdateInstrumentCommand;
import com.syncro.compliance.application.EquipmentChangeNoticeService.ApproveEcnCommand;
import com.syncro.compliance.application.EquipmentChangeNoticeService.CreateEcnCommand;
import com.syncro.compliance.application.EquipmentChangeNoticeService.EcnView;
import com.syncro.compliance.application.EquipmentChangeNoticeService.ExecuteEcnCommand;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.domain.CalibrationStatus;
import com.syncro.compliance.domain.EcnStatus;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentRepository;
import com.syncro.compliance.infrastructure.db.CalibrationRecordRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Story 21-2 integration proof against a real PostgreSQL (V17 applied by Flyway):
 * calibration status is derived on read from real dates (EXPIRED/EXPIRING_SOON/
 * VALID vs the 14-day window), recalibration appends an immutable record and
 * advances the instrument, the ECN lifecycle DRAFT→UNDER_REVIEW→APPROVED→
 * EXECUTED→CLOSED stamps actors/dates and writes EQUIPMENT_CHANGE_NOTICE audit
 * rows with previous/new values, plant/machine scope filtering hides foreign
 * rows, and stale-version writes surface as VERSION_CONFLICT.
 */
class ComplianceCalibrationEcnIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  /**
   * Review 21-2 L13: the service derives status from LocalDate.now(clock) — with
   * the system clock a UTC-midnight crossing between class load and assertion
   * flakes the derived-status tests. A @Primary fixed Clock pins "today" for the
   * whole context (this class gets its own cached context).
   */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.now(FIXED_CLOCK);

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    Clock fixedComplianceClock() {
      return FIXED_CLOCK;
    }
  }

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
  private CalibrationInstrumentRepository instruments;
  @Autowired
  private CalibrationRecordRepository records;
  @Autowired
  private WorkOrderRepository workOrders;
  @Autowired
  private WorkOrderCategoryRepository categories;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private CalibrationService calibration;
  @Autowired
  private EquipmentChangeNoticeService ecnService;

  @PersistenceContext
  private EntityManager entityManager;

  private record Fixture(PlantEntity plant, MachineGroupEntity groupA, MachineGroupEntity groupB,
      MachineEntity machineA, MachineEntity machineB) {
  }

  private Fixture fixture() {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "P" + suffix, "Plant",
        T0, T0));
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

  private AuthenticatedUser persistedUser(ApplicationRole role) {
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(),
        "cal-" + role.name().toLowerCase() + "-" + suffix + "@syncro.test", "hash", role, true,
        T0, T0));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
  }

  /** Grants a persisted user plant access so the create-time scope gate admits them. */
  private void assignPlant(AuthenticatedUser user, UUID plantId) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()),
        plantId, T0));
  }

  private CreateInstrumentCommand instrument(String code, LocalDate next, UUID plantId) {
    return new CreateInstrumentCommand(code, "Digital caliper " + code, "Mitutoyo 500-752",
        "SN-" + code, "QC bench", 180, TODAY.minusDays(180), next, "B4T Lab", plantId);
  }

  @Test
  @DisplayName("21.2-INT-001 P0 V17 columns persist: instrument plant_id + version through the service")
  void v17ColumnsPersist() {
    var fx = fixture();
    var admin = superAdmin();

    var created = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(120), fx.plant().getId()));
    assertThat(created.plantId()).isEqualTo(fx.plant().getId());

    var reloaded = instruments.findById(created.id()).orElseThrow();
    assertThat(reloaded.getPlantId()).isEqualTo(fx.plant().getId());
    assertThat(reloaded.getVersion()).isZero();

    // A global (null-plant) instrument is also creatable.
    var global = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(120), null));
    assertThat(instruments.findById(global.id()).orElseThrow().getPlantId()).isNull();
  }

  @Test
  @DisplayName("21.2-INT-002 P0 derived status vs real dates: EXPIRED / EXPIRING_SOON / VALID + status filter")
  void derivedStatusVsRealDates() {
    var admin = superAdmin();
    calibration.create(admin, instrument("CAL-" + UUID.randomUUID(), TODAY.minusDays(1), null));
    calibration.create(admin, instrument("CAL-" + UUID.randomUUID(), TODAY.plusDays(7), null));
    calibration.create(admin, instrument("CAL-" + UUID.randomUUID(), TODAY.plusDays(60), null));

    var all = calibration.list(admin, null);
    assertThat(all).extracting(CalibrationService.InstrumentView::status)
        .contains(CalibrationStatus.EXPIRED, CalibrationStatus.EXPIRING_SOON,
            CalibrationStatus.VALID);

    assertThat(calibration.list(admin, CalibrationStatus.EXPIRED))
        .allSatisfy(view -> assertThat(view.nextCalibrationDate()).isBefore(TODAY));
    assertThat(calibration.list(admin, CalibrationStatus.EXPIRING_SOON))
        .allSatisfy(view -> assertThat(view.nextCalibrationDate())
            .isAfterOrEqualTo(TODAY)
            .isBeforeOrEqualTo(TODAY.plusDays(14)));
  }

  @Test
  @DisplayName("21.2-INT-003 P0 recalibrate appends a record row + advances instrument dates + audits both")
  void recalibrateAppendsAndAdvances() {
    // Recorded_by FKs to auth_users — the recoder must be a persisted user.
    var fx = fixture();
    var staff = persistedUser(ApplicationRole.STAFF_MAINTENANCE);
    assignPlant(staff, fx.plant().getId());
    var created = calibration.create(staff, instrument("CAL-" + UUID.randomUUID(),
        TODAY.minusDays(5), fx.plant().getId()));

    var record = calibration.recalibrate(staff, created.id(), new RecalibrateCommand(
        TODAY, TODAY.plusDays(180), "B4T Lab", "CERT-" + UUID.randomUUID(),
        "garage://cal/cert.pdf", "PASS", "Within tolerance"));

    var reloaded = instruments.findById(created.id()).orElseThrow();
    assertThat(reloaded.getLastCalibrationDate()).isEqualTo(TODAY);
    assertThat(reloaded.getNextCalibrationDate()).isEqualTo(TODAY.plusDays(180));
    assertThat(reloaded.getStatus()).isEqualTo(CalibrationStatus.VALID);
    assertThat(records.findByInstrumentIdOrderByCalibrationDateDesc(created.id()))
        .extracting(r -> r.getId()).containsExactly(record.id());

    var recordAudits = auditLogs.findByEntityIdOrderByCreatedAtAsc(record.id());
    assertThat(recordAudits).hasSize(1);
    assertThat(recordAudits.getFirst().getEntityType())
        .isEqualTo(AuditEntityType.CALIBRATION_RECORD);
    assertThat(recordAudits.getFirst().getAction()).isEqualTo(AuditAction.CREATE);
    var instrumentAudits = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(instrumentAudits).extracting(a -> a.getAction())
        .containsExactly(AuditAction.CREATE, AuditAction.UPDATE);
    var update = instrumentAudits.get(1);
    assertThat(update.getEntityType()).isEqualTo(AuditEntityType.CALIBRATION_INSTRUMENT);
    assertThat(update.getPreviousValue()).contains("\"status\":\"EXPIRED\"");
    assertThat(update.getNewValue()).contains("\"status\":\"VALID\"");

    // Backward window rejected (record next <= date).
    assertThatThrownBy(() -> calibration.recalibrate(staff, created.id(),
        new RecalibrateCommand(TODAY, TODAY, null, null, null, null, null)))
        .isInstanceOf(NonConformanceService.ComplianceValidationException.class);
  }

  @Test
  @DisplayName("21.2-INT-004 P0 instrument scope: plant-assigned user sees own + global, not other plant")
  void instrumentScopeFiltering() {
    var fx = fixture();
    var otherPlant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(),
        "Q" + UUID.randomUUID().toString().substring(0, 8), "Other", T0, T0));
    var admin = superAdmin();
    var staff = persistedUser(ApplicationRole.STAFF_MAINTENANCE);
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(staff.id()),
        fx.plant().getId(), T0));

    var own = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(120), fx.plant().getId()));
    var global = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(120), null));
    var foreign = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(120), otherPlant.getId()));

    var visible = calibration.list(staff, null);
    assertThat(visible).extracting(CalibrationService.InstrumentView::id)
        .contains(own.id(), global.id())
        .doesNotContain(foreign.id());
    assertThatThrownBy(() -> calibration.get(staff, foreign.id()))
        .isInstanceOf(CalibrationService.CalibrationInstrumentNotFoundException.class);
    // SUPER_ADMIN sees everything.
    assertThat(calibration.list(admin, null)).extracting(CalibrationService.InstrumentView::id)
        .contains(own.id(), global.id(), foreign.id());
  }

  @Test
  @DisplayName("21.2-INT-005 P0 duplicate instrument_code → DUPLICATE_IDENTIFIER (real unique constraint)")
  void duplicateInstrumentCode() {
    var admin = superAdmin();
    var code = "CAL-" + UUID.randomUUID();
    calibration.create(admin, instrument(code, TODAY.plusDays(120), null));

    assertThatThrownBy(() -> calibration.create(admin, instrument(code, TODAY.plusDays(120), null)))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.2-INT-005b P0 instrument list ordered by next_calibration_date ASC (7c)")
  void instrumentListOrdering() {
    var admin = superAdmin();
    var late = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(90), null));
    var overdue = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.minusDays(2), null));
    var soon = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(7), null));

    var mine = java.util.Set.of(late.id(), overdue.id(), soon.id());
    var ordered = calibration.list(admin, null).stream()
        .filter(view -> mine.contains(view.id()))
        .map(CalibrationService.InstrumentView::nextCalibrationDate)
        .toList();
    assertThat(ordered).containsExactly(TODAY.minusDays(2), TODAY.plusDays(7),
        TODAY.plusDays(90));
  }

  @Test
  @DisplayName("21.2-INT-005c P0 ECN list ?status=DRAFT executes the non-null filter branch (7a)")
  void ecnStatusFilterOnRealPostgres() {
    var fx = fixture();
    // submitted_by FKs to auth_users — the submitter must be a persisted user.
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE);
    assignPlant(manager, fx.plant().getId());
    var draft = ecnService.create(manager, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineA().getId(), "Still draft", null, null, null, null));
    var submitted = ecnService.create(manager, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineA().getId(), "Going up", null, null, null, null));
    ecnService.submit(manager, submitted.id());

    var drafts = ecnService.list(manager, EcnStatus.DRAFT);
    assertThat(drafts).extracting(EcnView::id).contains(draft.id()).doesNotContain(submitted.id());
    assertThat(drafts).allSatisfy(view -> assertThat(view.status()).isEqualTo(EcnStatus.DRAFT));
    var underReview = ecnService.list(manager, EcnStatus.UNDER_REVIEW);
    assertThat(underReview).extracting(EcnView::id).contains(submitted.id())
        .doesNotContain(draft.id());
    assertThat(underReview).allSatisfy(view -> assertThat(view.status())
        .isEqualTo(EcnStatus.UNDER_REVIEW));
  }

  @Test
  @DisplayName("21.2-INT-006 P0 full ECN lifecycle with actor/date stamps + audit rows + WO evidence link")
  void ecnLifecycleWithAudit() {
    var fx = fixture();
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE);
    var staff = persistedUser(ApplicationRole.STAFF_MAINTENANCE);
    // Both act on machineA's ECN — plant access keeps them in scope (21-1 P3 gate).
    assignPlant(manager, fx.plant().getId());
    assignPlant(staff, fx.plant().getId());

    var created = ecnService.create(staff, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineA().getId(), "Retrofit safety guard", "Add light curtain", "IMPROVEMENT",
        "Near-miss report Q2", null));
    assertThat(created.status()).isEqualTo(EcnStatus.DRAFT);

    var submitted = ecnService.submit(staff, created.id());
    assertThat(submitted.status()).isEqualTo(EcnStatus.UNDER_REVIEW);
    assertThat(submitted.submittedBy()).isEqualTo(UUID.fromString(staff.id()));

    var approved = ecnService.approve(manager, created.id(),
        new ApproveEcnCommand(LocalDate.of(2026, 10, 1)));
    assertThat(approved.status()).isEqualTo(EcnStatus.APPROVED);
    assertThat(approved.reviewedBy()).isEqualTo(UUID.fromString(manager.id()));
    assertThat(approved.approvedBy()).isEqualTo(UUID.fromString(manager.id()));
    assertThat(approved.effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(approved.signOffAt()).isNotNull();

    // Real workorder as execution evidence.
    var category = categories.saveAndFlush(new WorkOrderCategoryEntity(UUID.randomUUID(),
        "ECN" + UUID.randomUUID().toString().substring(0, 8), "ECN carrier", null, T0, T0));
    var workOrder = workOrders.saveAndFlush(new WorkOrderEntity(
        "ECN-WO-" + UUID.randomUUID().toString().substring(0, 8), "INTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.OPEN, category.getId(),
        fx.machineA().getId(), "ECN implementation", 0L, null, null, null, T0, T0));

    var executed = ecnService.execute(manager, created.id(),
        new ExecuteEcnCommand(workOrder.getId(), "garage://ecn/after.jpg"));
    assertThat(executed.status()).isEqualTo(EcnStatus.EXECUTED);
    assertThat(executed.executedWoId()).isEqualTo(workOrder.getId());
    assertThat(executed.afterPhotoUrl()).isEqualTo("garage://ecn/after.jpg");

    var closed = ecnService.close(manager, created.id());
    assertThat(closed.status()).isEqualTo(EcnStatus.CLOSED);

    // Illegal: CLOSED is terminal.
    assertThatThrownBy(() -> ecnService.submit(staff, created.id()))
        .isInstanceOf(InvalidStateTransitionException.class);

    var audits = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(audits).hasSize(5);
    assertThat(audits).extracting(a -> a.getEntityType())
        .containsOnly(AuditEntityType.EQUIPMENT_CHANGE_NOTICE);
    assertThat(audits).extracting(a -> a.getAction())
        .containsExactly(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.UPDATE,
            AuditAction.UPDATE, AuditAction.UPDATE);
    // The approve audit carries previous UNDER_REVIEW → new APPROVED + machine plant.
    var approveAudit = audits.get(2);
    assertThat(approveAudit.getPreviousValue()).contains("\"status\":\"UNDER_REVIEW\"");
    assertThat(approveAudit.getNewValue()).contains("\"status\":\"APPROVED\"")
        .contains("\"effectiveDate\":\"2026-10-01\"");
    assertThat(approveAudit.getPlantId()).isEqualTo(fx.plant().getId());
  }

  @Test
  @DisplayName("21.2-INT-007 P0 ECN machine scope: group-A leader sees own machine, not sibling group")
  void ecnScopeFiltering() {
    var fx = fixture();
    var admin = superAdmin();
    var leader = persistedUser(ApplicationRole.SECTION_LEADER);
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        fx.machineA().getId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, T0, T0));

    var inScope = ecnService.create(admin, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineA().getId(), "In scope", null, null, null, null));
    var outOfScope = ecnService.create(admin, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineB().getId(), "Out of scope", null, null, null, null));

    assertThat(ecnService.list(leader, null)).extracting(EcnView::id)
        .contains(inScope.id()).doesNotContain(outOfScope.id());
    assertThatThrownBy(() -> ecnService.get(leader, outOfScope.id()))
        .isInstanceOf(EquipmentChangeNoticeService.EquipmentChangeNoticeNotFoundException.class);

    // Create-time scope gate: the group-A leader cannot file against machine B.
    assertThatThrownBy(() -> ecnService.create(leader, new CreateEcnCommand(
        "ECN-" + UUID.randomUUID(), fx.machineB().getId(), "Nope", null, null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.2-INT-008 P0 staff cannot approve/execute/close (narrower lifecycle gate)")
  void approvalRoleGate() {
    var fx = fixture();
    var admin = superAdmin();
    var staff = persistedUser(ApplicationRole.STAFF_MAINTENANCE);
    assignPlant(staff, fx.plant().getId());
    var created = ecnService.create(admin, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineA().getId(), "Guard", null, null, null, null));
    ecnService.submit(staff, created.id());

    assertThatThrownBy(() -> ecnService.approve(staff, created.id(), new ApproveEcnCommand(null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    assertThatThrownBy(() -> ecnService.execute(staff, created.id(),
        new ExecuteEcnCommand(null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    assertThatThrownBy(() -> ecnService.close(staff, created.id()))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.2-INT-009 P0 stale-version concurrent write → VERSION_CONFLICT (V17 @Version)")
  void optimisticLockConflict() {
    var admin = superAdmin();
    var created = calibration.create(admin, instrument("CAL-" + UUID.randomUUID(),
        TODAY.plusDays(120), null));

    // Detach a copy, advance the row through the service (version 0→1), then write
    // the stale copy back — the lost update must surface as an optimistic-lock
    // failure, never silently overwrite.
    var stale = instruments.findById(created.id()).orElseThrow();
    entityManager.detach(stale);
    calibration.update(admin, created.id(), new UpdateInstrumentCommand("Renamed first",
        null, null, null, null, null, null));

    stale.updateContent("Renamed second", null, null, null, null, null, null,
        Instant.now(Clock.systemUTC()));
    assertThatThrownBy(() -> instruments.saveAndFlush(stale))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("21.2-INT-010 P1 unknown machine/workorder references → stable 404 codes")
  void referenceNotFoundCodes() {
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE);
    var fx = fixture();
    assignPlant(manager, fx.plant().getId());

    assertThatThrownBy(() -> ecnService.create(manager, new CreateEcnCommand(
        "ECN-" + UUID.randomUUID(), UUID.randomUUID(), "Guard", null, null, null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("MACHINE_NOT_FOUND"));

    var created = ecnService.create(manager, new CreateEcnCommand("ECN-" + UUID.randomUUID(),
        fx.machineA().getId(), "Guard", null, null, null, null));
    ecnService.submit(manager, created.id());
    ecnService.approve(manager, created.id(), new ApproveEcnCommand(null));
    assertThatThrownBy(() -> ecnService.execute(manager, created.id(),
        new ExecuteEcnCommand("NOPE-404", null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("WORK_ORDER_NOT_FOUND"));
  }
}
