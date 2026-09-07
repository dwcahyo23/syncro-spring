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
import com.syncro.compliance.application.LessonLearnedService.CreateLessonCommand;
import com.syncro.compliance.application.LessonLearnedService.LessonNotFoundException;
import com.syncro.compliance.application.LessonLearnedService.LessonView;
import com.syncro.compliance.application.MachineSetupBaselineService.BaselineView;
import com.syncro.compliance.application.MachineSetupBaselineService.CreateBaselineCommand;
import com.syncro.compliance.application.MachineSetupBaselineService.MachineSetupBaselineNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.domain.EightDStatus;
import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.compliance.infrastructure.db.EightDReportEntity;
import com.syncro.compliance.infrastructure.db.EightDReportRepository;
import com.syncro.compliance.infrastructure.db.LessonLearnedRepository;
import com.syncro.compliance.infrastructure.db.MachineSetupBaselineRepository;
import com.syncro.compliance.infrastructure.db.NonConformanceEntity;
import com.syncro.compliance.infrastructure.db.NonConformanceRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Story 21-3 integration proof against a real PostgreSQL (V18 applied by
 * Flyway): baseline versions are server-assigned per machine and creating one
 * flips the machine's siblings to inactive (is_active), activate re-points the
 * flag; lesson event links persist against real NC/8D/workorder rows and the
 * ON DELETE SET NULL FK keeps the lesson when its source event is deleted;
 * evidence JSONB round-trips; q/tag search and plant-OR-group scope filtering
 * run the real SQL; stale-version writes surface as VERSION_CONFLICT.
 */
class ComplianceBaselineLessonIntegrationTest extends AbstractPostgresIntegrationTest {

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
  private MachineSetupBaselineRepository baselines;
  @Autowired
  private LessonLearnedRepository lessons;
  @Autowired
  private NonConformanceRepository nonConformances;
  @Autowired
  private EightDReportRepository eightDReports;
  @Autowired
  private WorkOrderRepository workOrders;
  @Autowired
  private WorkOrderCategoryRepository categories;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private MachineSetupBaselineService baselineService;
  @Autowired
  private LessonLearnedService lessonService;
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
        "bl-" + role.name().toLowerCase() + "-" + suffix + "@syncro.test", "hash", role, true,
        T0, T0));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
  }

  private CreateBaselineCommand baseline(UUID machineId) {
    return new CreateBaselineCommand(machineId, null, Map.of(
        "tolerances", Map.of("clampPressureBar", 6.5),
        "references", List.of("SOP-12")));
  }

  private CreateLessonCommand lesson(String projectId, UUID machineId, List<String> tags) {
    return new CreateLessonCommand(projectId, machineId, "KAIZEN", "Forming press guide wear",
        "Recurring forming defects on the setup changeover", null, null, null, null, null,
        tags, null, null, null, null);
  }

  @Test
  @DisplayName("21.3-INT-001 P0 V18 applies: server-assigned version + supersede flips is_active")
  void versioningAndSupersede() {
    var fx = fixture();
    var admin = superAdmin();

    var first = baselineService.create(admin, baseline(fx.machineA().getId()));
    assertThat(first.version()).isEqualTo(1);
    assertThat(first.active()).isTrue();

    var second = baselineService.create(admin, baseline(fx.machineA().getId()));
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.active()).isTrue();

    var reloadedFirst = baselines.findById(first.id()).orElseThrow();
    assertThat(reloadedFirst.isActive()).isFalse();
    assertThat(reloadedFirst.getVersion()).isEqualTo(1);
    // The other machine's baselines are untouched (per-machine versioning).
    var other = baselineService.create(admin, baseline(fx.machineB().getId()));
    assertThat(other.version()).isEqualTo(1);

    // activate re-points the flag: v1 active again, v2 superseded.
    var activated = baselineService.activate(admin, first.id());
    assertThat(activated.active()).isTrue();
    assertThat(baselines.findById(second.id()).orElseThrow().isActive()).isFalse();
    assertThat(baselines.findByMachineIdAndActiveTrue(fx.machineA().getId()))
        .extracting(b -> b.getId()).containsExactly(first.id());

    // Every flip is audited with previous/new values: CREATE, the deactivation
    // from the second create, then the activation from the explicit activate.
    var audits = auditLogs.findByEntityIdOrderByCreatedAtAsc(first.id());
    assertThat(audits).extracting(a -> a.getAction())
        .containsExactly(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.UPDATE);
    assertThat(audits.get(1).getPreviousValue()).contains("\"active\":true");
    assertThat(audits.get(1).getNewValue()).contains("\"active\":false");
    var activation = audits.get(2);
    assertThat(activation.getEntityType()).isEqualTo(AuditEntityType.MACHINE_SETUP_BASELINE);
    assertThat(activation.getPreviousValue()).contains("\"active\":false");
    assertThat(activation.getNewValue()).contains("\"active\":true");
    assertThat(activation.getPlantId()).isEqualTo(fx.plant().getId());
  }

  @Test
  @DisplayName("21.3-INT-002 P0 lesson event links persist against real NC/8D/WO rows")
  void eventLinksPersist() {
    var fx = fixture();
    var admin = superAdmin();
    var nc = nonConformances.saveAndFlush(new NonConformanceEntity(UUID.randomUUID(), null, null,
        fx.machineA().getId(), "NC-" + UUID.randomUUID(), "Drift", null, null, null,
        NcStatus.OPEN, NcSeverity.MAJOR, null, null, T0, T0));
    var report = eightDReports.saveAndFlush(new EightDReportEntity(UUID.randomUUID(), nc.getId(),
        "8D-" + UUID.randomUUID(), null, null, null, null, null, null, null, null,
        EightDStatus.DRAFT, null, null, T0, T0));
    var category = categories.saveAndFlush(new WorkOrderCategoryEntity(UUID.randomUUID(),
        "BL" + UUID.randomUUID().toString().substring(0, 8), "Baseline carrier", null, T0, T0));
    var workOrder = workOrders.saveAndFlush(new WorkOrderEntity(
        "BL-WO-" + UUID.randomUUID().toString().substring(0, 8), "INTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.OPEN, category.getId(),
        fx.machineA().getId(), "Lesson carrier", 0L, null, null, null, T0, T0));

    var created = lessonService.create(admin, new CreateLessonCommand("PRJ-" + UUID.randomUUID(),
        fx.machineA().getId(), "KAIZEN", "Guide wear", "Recurring failures", null, null, null,
        null, null, List.of("mechanical"), nc.getId(), report.getId(), workOrder.getId(),
        List.of(Map.of("objectKey", "garage/k1", "filename", "photo.jpg"))));

    var reloaded = lessons.findById(created.id()).orElseThrow();
    assertThat(reloaded.getNcId()).isEqualTo(nc.getId());
    assertThat(reloaded.getEightDId()).isEqualTo(report.getId());
    assertThat(reloaded.getWorkOrderId()).isEqualTo(workOrder.getId());
    assertThat(reloaded.getEvidence()).containsExactly(
        Map.of("objectKey", "garage/k1", "filename", "photo.jpg"));

    // Unknown references → stable 404 codes (AC2).
    assertThatThrownBy(() -> lessonService.create(admin, new CreateLessonCommand(
        "PRJ-" + UUID.randomUUID(), null, null, "T", "P", null, null, null, null, null, null,
        UUID.randomUUID(), null, null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("NON_CONFORMANCE_NOT_FOUND"));
    assertThatThrownBy(() -> lessonService.create(admin, new CreateLessonCommand(
        "PRJ-" + UUID.randomUUID(), null, null, "T", "P", null, null, null, null, null, null,
        null, UUID.randomUUID(), null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("EIGHT_D_REPORT_NOT_FOUND"));
    assertThatThrownBy(() -> lessonService.create(admin, new CreateLessonCommand(
        "PRJ-" + UUID.randomUUID(), null, null, "T", "P", null, null, null, null, null, null,
        null, null, "NOPE-404", null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("WORK_ORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-INT-003 P0 event-link FK is ON DELETE SET NULL — lesson survives its NC")
  void eventLinkSetNull() {
    var fx = fixture();
    var admin = superAdmin();
    var nc = nonConformances.saveAndFlush(new NonConformanceEntity(UUID.randomUUID(), null, null,
        fx.machineA().getId(), "NC-" + UUID.randomUUID(), "Drift", null, null, null,
        NcStatus.OPEN, NcSeverity.MINOR, null, null, T0, T0));
    var created = lessonService.create(admin, new CreateLessonCommand("PRJ-" + UUID.randomUUID(),
        null, null, "Guide wear", "Recurring failures", null, null, null, null, null, null,
        nc.getId(), null, null, null));

    // Hard-delete the source event (native — bypasses JPA cascade config); the
    // lesson must survive with a cleared link.
    entityManager.createNativeQuery("DELETE FROM non_conformances WHERE id = :id")
        .setParameter("id", nc.getId()).executeUpdate();
    entityManager.flush();
    entityManager.clear();

    var reloaded = lessons.findById(created.id()).orElseThrow();
    assertThat(reloaded.getNcId()).isNull();
    assertThat(reloaded.getTitle()).isEqualTo("Guide wear");
  }

  @Test
  @DisplayName("21.3-INT-004 P0 duplicate project_id → DUPLICATE_IDENTIFIER (real unique)")
  void duplicateProjectId() {
    var admin = superAdmin();
    var projectId = "PRJ-" + UUID.randomUUID();
    lessonService.create(admin, lesson(projectId, null, List.of("setup")));

    assertThatThrownBy(() -> lessonService.create(admin, lesson(projectId, null, List.of())))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.3-INT-005 P0 tag + q search over real JSONB (tags::text containment)")
  void tagAndSubstringSearch() {
    var admin = superAdmin();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var hit = lessonService.create(admin, new CreateLessonCommand("PRJ-" + suffix + "-hit", null,
        null, "Forming press guide wear", "Setup changeover defects", null, null, null, null,
        null, List.of("forming", "setup"), null, null, null, null));
    var miss = lessonService.create(admin, new CreateLessonCommand("PRJ-" + suffix + "-miss",
        null, null, "Paint booth filter", "Unrelated problem", null, null, null, null, null,
        List.of("paint"), null, null, null, null));

    var byTag = lessonService.search(admin, null, "setup").stream()
        .filter(view -> view.projectId().startsWith("PRJ-" + suffix))
        .toList();
    assertThat(byTag).extracting(LessonView::id).containsExactly(hit.id());

    // q is case-insensitive over title AND problem_summary.
    var byQuery = lessonService.search(admin, "FORMING", null).stream()
        .filter(view -> view.projectId().startsWith("PRJ-" + suffix))
        .toList();
    assertThat(byQuery).extracting(LessonView::id).containsExactly(hit.id());

    var combined = lessonService.search(admin, "changeover", "forming");
    assertThat(combined).extracting(LessonView::id).contains(hit.id());
    assertThat(combined).extracting(LessonView::id).doesNotContain(miss.id());
  }

  @Test
  @DisplayName("21.3-INT-006 P0 scope: group-A leader sees own + unlinked, not sibling group")
  void scopeFiltering() {
    var fx = fixture();
    var admin = superAdmin();
    var leader = persistedUser(ApplicationRole.SECTION_LEADER);
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(),
        fx.machineA().getId(), UUID.fromString(leader.id()), ResponsibilityLevel.LEADER, T0, T0));

    var inScope = baselineService.create(admin, baseline(fx.machineA().getId()));
    var outOfScope = baselineService.create(admin, baseline(fx.machineB().getId()));
    var linked = lessonService.create(admin, lesson("PRJ-" + UUID.randomUUID(),
        fx.machineA().getId(), List.of("a")));
    var unlinked = lessonService.create(admin, lesson("PRJ-" + UUID.randomUUID(), null,
        List.of("b")));
    var foreign = lessonService.create(admin, lesson("PRJ-" + UUID.randomUUID(),
        fx.machineB().getId(), List.of("c")));

    assertThat(baselineService.list(leader, null, false)).extracting(BaselineView::id)
        .contains(inScope.id()).doesNotContain(outOfScope.id());
    assertThatThrownBy(() -> baselineService.get(leader, outOfScope.id()))
        .isInstanceOf(MachineSetupBaselineNotFoundException.class);

    // Lessons without a machine link are visible to all authenticated (NC precedent).
    assertThat(lessonService.search(leader, null, null)).extracting(LessonView::id)
        .contains(linked.id(), unlinked.id()).doesNotContain(foreign.id());
    assertThatThrownBy(() -> lessonService.get(leader, foreign.id()))
        .isInstanceOf(LessonNotFoundException.class);

    // SUPER_ADMIN sees everything.
    assertThat(baselineService.list(admin, null, false)).extracting(BaselineView::id)
        .contains(inScope.id(), outOfScope.id());
  }

  @Test
  @DisplayName("21.3-INT-007 P0 stale-version concurrent write → VERSION_CONFLICT (V18 @Version)")
  void optimisticLockConflict() {
    var admin = superAdmin();
    var fx = fixture();
    var created = baselineService.create(admin, baseline(fx.machineA().getId()));

    // Detach a copy, advance the row through the service (the second create
    // supersedes v1 → its lock_version 0→1; activate on an already-active row is
    // a no-op per review L3), then write the stale copy back — the lost update
    // must surface as an optimistic lock failure, never silently overwrite.
    var stale = baselines.findById(created.id()).orElseThrow();
    entityManager.detach(stale);
    baselineService.create(admin, baseline(fx.machineA().getId()));

    stale.deactivate(Instant.now());
    assertThatThrownBy(() -> baselines.saveAndFlush(stale))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("21.3-INT-007b P0 lesson @Version: stale write surfaces as OptimisticLockingFailure (VG-1)")
  void lessonOptimisticLockConflict() {
    var admin = superAdmin();
    var created = lessonService.create(admin, lesson("PRJ-" + UUID.randomUUID(), null,
        List.of("setup")));

    var stale = lessons.findById(created.id()).orElseThrow();
    entityManager.detach(stale);
    lessonService.update(admin, created.id(),
        new LessonLearnedService.UpdateLessonCommand("Renamed first", null, null, null, null,
            null, null, null, null, null, null, null));

    stale.updateContent("Renamed second", null, null, null, null, null, null, null, null,
        Instant.now());
    assertThatThrownBy(() -> lessons.saveAndFlush(stale))
        .isInstanceOf(OptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("21.3-INT-009 P0 baseline create with valid ECN link persists ecn_id + audits it (VG-6)")
  void baselineWithEcnLink() {
    var fx = fixture();
    var admin = superAdmin();
    var ecn = ecnService.create(admin,
        new EquipmentChangeNoticeService.CreateEcnCommand("ECN-" + UUID.randomUUID(),
            fx.machineA().getId(), "Setup change", null, null, null, null));

    var created = baselineService.create(admin, new CreateBaselineCommand(fx.machineA().getId(),
        ecn.id(), Map.of("clampPressureBar", 6.5)));

    var reloaded = baselines.findById(created.id()).orElseThrow();
    assertThat(reloaded.getEcnId()).isEqualTo(ecn.id());
    var audits = auditLogs.findByEntityIdOrderByCreatedAtAsc(created.id());
    assertThat(audits.getFirst().getNewValue()).contains("\"ecnId\":\"" + ecn.id() + "\"");
  }

  @Test
  @DisplayName("21.3-INT-008 P1 baseline list ?machineId + ?activeOnly filters run the real SQL")
  void baselineListFilters() {
    var fx = fixture();
    var admin = superAdmin();
    var first = baselineService.create(admin, baseline(fx.machineA().getId()));
    var second = baselineService.create(admin, baseline(fx.machineA().getId()));
    baselineService.create(admin, baseline(fx.machineB().getId()));

    var machineAOnly = baselineService.list(admin, fx.machineA().getId(), false);
    assertThat(machineAOnly).extracting(BaselineView::id)
        .containsExactlyInAnyOrder(first.id(), second.id());

    var activeOnly = baselineService.list(admin, fx.machineA().getId(), true);
    assertThat(activeOnly).extracting(BaselineView::id).containsExactly(second.id());
  }
}
