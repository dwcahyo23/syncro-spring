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
import com.syncro.compliance.domain.CalibrationStatus;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentEntity;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentRepository;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateCategoryCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.CreateItemCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.UpdateCategoryCommand;
import com.syncro.maintenance.preventive.application.PmChecklistService.UpdateItemCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ApproveChecksheetCommand;
import com.syncro.maintenance.preventive.application.PmChecksheetService.CreateChecksheetCommand;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 19-2 integration test: PM checklist categories &amp; items against a real
 * Postgres container (blueprint F4). Covers define/edit of categories and ordered
 * items against an unapproved checksheet revision, the approved-revision freeze
 * (409), MEASUREMENT bounds validation, foreign-category and unknown-calibration
 * rejection, scope-filtered reads, the four-role gates with SUPER_ADMIN bypass,
 * category-delete orphaning, and the audit trail.
 */
class PmChecklistServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  @Autowired
  private PmChecklistService checklist;
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
  private CalibrationInstrumentRepository calibrationInstruments;
  @Autowired
  private AuditLogRepository auditLogs;
  /** The same Clock bean the service uses — timestamp asserts never use wall clock. */
  @Autowired
  private Clock clock;

  private PlantEntity plant;
  private MachineEntity machine;
  private AuthenticatedUser leaderUser;
  private AuthenticatedUser staffUser;
  private UUID checksheetId;
  private UUID frequencyId;

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

    // V7 seeds MONTHLY/ANNUAL; resolve the MONTHLY id and create an unapproved r1.
    frequencyId = frequencies.list().stream()
        .filter(f -> f.code().equals("MONTHLY"))
        .findFirst().orElseThrow().id();
    checksheetId = checksheets.create(staffUser,
        new CreateChecksheetCommand(machine.getId(), frequencyId, null)).id();
  }

  // -------------------------------------------------------------------------
  // AC1: define categories + items on an unapproved revision
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-INT-001 P0 create category + MEASUREMENT/OK_NG items: persist in order, bounds/criticality intact, audit CREATE rows")
  void createCategoriesAndItemsPersistsAndAudits() {
    var category = checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "Lubrication", 1));
    assertThat(category.name()).isEqualTo("Lubrication");
    assertThat(category.sortOrder()).isEqualTo(1);
    assertThat(category.checksheetId()).isEqualTo(checksheetId);

    var measurement = checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        category.id(), null, "Bearing temperature", "Infrared gun", PmItemInputType.MEASUREMENT,
        "C", new BigDecimal("10"), new BigDecimal("55"), new BigDecimal("100"),
        true, "MAN-0042", null));
    var okNg = checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        category.id(), null, "Belt tension visual check", null, PmItemInputType.OK_NG,
        null, null, null, null, false, null, null));

    assertThat(measurement.sequence()).isEqualTo(1);
    assertThat(measurement.inputType()).isEqualTo(PmItemInputType.MEASUREMENT);
    assertThat(measurement.lsl()).isEqualByComparingTo("10");
    assertThat(measurement.nominal()).isEqualByComparingTo("55");
    assertThat(measurement.usl()).isEqualByComparingTo("100");
    assertThat(measurement.criticalFlag()).isTrue();
    assertThat(okNg.sequence()).isEqualTo(2);
    assertThat(okNg.inputType()).isEqualTo(PmItemInputType.OK_NG);
    assertThat(okNg.lsl()).isNull();
    assertThat(okNg.criticalFlag()).isFalse();

    // Ordered reads: categories by sort_order, items by sequence.
    var items = checklist.listItems(staffUser, checksheetId, null);
    assertThat(items).extracting(PmChecklistService.ItemView::sequence)
        .containsExactly(1, 2);
    assertThat(checklist.listItems(staffUser, checksheetId, category.id())).hasSize(2);
    assertThat(checklist.listCategories(staffUser, checksheetId))
        .extracting(PmChecklistService.CategoryView::name).containsExactly("Lubrication");

    // Audit CREATE rows with actor + new values (UUIDs stringified).
    var categoryAudit = auditLogs.findByEntityIdOrderByCreatedAtAsc(category.id());
    assertThat(categoryAudit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_CHECKLIST_CATEGORY);
      assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
      assertThat(entry.getActorId()).isEqualTo(UUID.fromString(staffUser.id()));
      assertThat(entry.getNewValue()).contains("\"checksheetId\":\"" + checksheetId + "\"");
    });
    var itemAudit = auditLogs.findByEntityIdOrderByCreatedAtAsc(measurement.id());
    assertThat(itemAudit).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_CHECKLIST_ITEM);
      assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
      assertThat(entry.getNewValue()).contains("\"lsl\":\"10\"").contains("\"usl\":\"100\"");
    });
  }

  @Test
  @DisplayName("19.2-INT-002 P0 sequence defaults to max+1 within the checksheet; explicit sequence wins")
  void sequenceDefaultsToMaxPlusOne() {
    checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, 50,
        "Explicit seq", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    var auto1 = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, null,
        "Auto 1", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    var auto2 = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, null,
        "Auto 2", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    assertThat(auto1.sequence()).isEqualTo(51);
    assertThat(auto2.sequence()).isEqualTo(52);
  }

  @Test
  @DisplayName("19.2-INT-003 P0 item without category is allowed (categoryId optional)")
  void itemWithoutCategoryAllowed() {
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, null,
        "Loose check", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    assertThat(item.categoryId()).isNull();
    assertThat(checklist.getItem(staffUser, item.id()).id()).isEqualTo(item.id());
  }

  // -------------------------------------------------------------------------
  // AC2: approved revisions are frozen
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-INT-004 P0 every mutation on an approved revision → 409 INVALID_CHECKSHEET_TRANSITION, nothing changes")
  void approvedRevisionFreezesChecklist() {
    var category = checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "Hydraulics", 1));
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, category.id(),
        null, "Oil level", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));

    checksheets.approve(leaderUser, checksheetId,
        new ApproveChecksheetCommand(LocalDate.now(clock)));

    assertThatThrownBy(() -> checklist.createCategory(leaderUser,
        new CreateCategoryCommand(checksheetId, "Late category", 2)))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
    assertThatThrownBy(() -> checklist.createItem(leaderUser, new CreateItemCommand(checksheetId,
        null, null, "Late item", null, PmItemInputType.OK_NG, null, null, null, null,
        null, null, null)))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
    assertThatThrownBy(() -> checklist.updateCategory(leaderUser, category.id(),
        new UpdateCategoryCommand("Renamed", null)))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
    assertThatThrownBy(() -> checklist.updateItem(leaderUser, item.id(),
        new UpdateItemCommand(null, null, "Renamed", null, PmItemInputType.OK_NG, null,
            null, null, null, null, null, null)))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
    assertThatThrownBy(() -> checklist.deleteCategory(leaderUser, category.id()))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);
    assertThatThrownBy(() -> checklist.deleteItem(leaderUser, item.id()))
        .isInstanceOf(PmChecksheetService.InvalidChecksheetTransitionException.class);

    // Nothing changed: the pre-approval content is intact.
    assertThat(checklist.listCategories(staffUser, checksheetId)).hasSize(1);
    assertThat(checklist.listItems(staffUser, checksheetId, null)).hasSize(1);
  }

  // -------------------------------------------------------------------------
  // AC3: validation & reference errors
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-INT-005 P0 MEASUREMENT with lsl > usl → 400 VALIDATION_ERROR; equal bounds allowed")
  void measurementBoundsRejectedWhenInverted() {
    assertThatThrownBy(() -> checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        null, null, "Pressure", null, PmItemInputType.MEASUREMENT, "bar",
        new BigDecimal("10"), null, new BigDecimal("5"), null, null, null)))
        .isInstanceOf(PmChecklistService.ChecklistValidationException.class);
    // lsl == usl is legal (lsl ≤ usl).
    var ok = checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        null, null, "Pressure", null, PmItemInputType.MEASUREMENT, "bar",
        new BigDecimal("5"), null, new BigDecimal("5"), null, null, null));
    assertThat(ok.lsl()).isEqualByComparingTo("5");
    // Bounds on an OK_NG item are not checked (only MEASUREMENT with both bounds).
    var okNg = checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        null, null, "Visual", null, PmItemInputType.OK_NG, null,
        new BigDecimal("10"), null, new BigDecimal("5"), null, null, null));
    assertThat(okNg.inputType()).isEqualTo(PmItemInputType.OK_NG);
  }

  @Test
  @DisplayName("19.2-INT-006 P0 category from another checksheet → 400; unknown category → 404")
  void foreignCategoryRejectedUnknownCategoryNotFound() {
    // Second checksheet on a second machine (same plant).
    var otherGroup = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group 2", T0, T0));
    var otherMachine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, otherGroup,
        "MC-" + UUID.randomUUID().toString().substring(0, 8), "Other", MachineStatus.ACTIVE,
        null, null, null, null, T0, T0));
    var otherChecksheet = checksheets.create(staffUser,
        new CreateChecksheetCommand(otherMachine.getId(), frequencyId, null)).id();
    var foreignCategory = checklist.createCategory(staffUser,
        new CreateCategoryCommand(otherChecksheet, "Foreign", 1));

    assertThatThrownBy(() -> checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        foreignCategory.id(), null, "Bad ref", null, PmItemInputType.OK_NG, null, null, null,
        null, null, null, null)))
        .isInstanceOf(PmChecklistService.ChecklistValidationException.class);
    assertThatThrownBy(() -> checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        UUID.randomUUID(), null, "Unknown ref", null, PmItemInputType.OK_NG, null, null, null,
        null, null, null, null)))
        .isInstanceOf(PmChecklistService.PmChecklistCategoryNotFoundException.class);
  }

  @Test
  @DisplayName("19.2-INT-007 P0 unknown calibration instrument → 404; existing one persists on the item")
  void calibrationInstrumentReference() {
    var instrument = calibrationInstruments.saveAndFlush(new CalibrationInstrumentEntity(
        UUID.randomUUID(), "CI-" + UUID.randomUUID().toString().substring(0, 8), "Torque wrench",
        "TW-500", null, null, 365, null, LocalDate.of(2027, 1, 1), null,
        CalibrationStatus.VALID, T0, T0));
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, null,
        "Flange torque", "Torque wrench", PmItemInputType.MEASUREMENT, "Nm",
        new BigDecimal("80"), new BigDecimal("100"), new BigDecimal("120"), null, null,
        instrument.getId()));
    assertThat(item.calibrationInstrumentId()).isEqualTo(instrument.getId());

    assertThatThrownBy(() -> checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        null, null, "Bad ref", null, PmItemInputType.OK_NG, null, null, null, null, null, null,
        UUID.randomUUID())))
        .isInstanceOf(PmChecklistService.CalibrationInstrumentNotFoundException.class);
  }

  @Test
  @DisplayName("19.2-INT-008 P0 blank/over-long text rejected at the service (400), unknown checksheet/item → 404")
  void textGuardsAndUnknownReferences() {
    assertThatThrownBy(() -> checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "   ", 1)))
        .isInstanceOf(PmChecklistService.ChecklistValidationException.class);
    assertThatThrownBy(() -> checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        null, null, " ".repeat(501), null, PmItemInputType.OK_NG, null, null, null, null,
        null, null, null)))
        .isInstanceOf(PmChecklistService.ChecklistValidationException.class);
    assertThatThrownBy(() -> checklist.createCategory(staffUser,
        new CreateCategoryCommand(UUID.randomUUID(), "Orphan", 1)))
        .isInstanceOf(PmChecksheetService.PmChecksheetNotFoundException.class);
    assertThatThrownBy(() -> checklist.getItem(staffUser, UUID.randomUUID()))
        .isInstanceOf(PmChecklistService.PmChecklistItemNotFoundException.class);
    assertThatThrownBy(() -> checklist.updateCategory(staffUser, UUID.randomUUID(),
        new UpdateCategoryCommand("Ghost", null)))
        .isInstanceOf(PmChecklistService.PmChecklistCategoryNotFoundException.class);
    // Negative sortOrder / sequence are rejected at the service (no DB CHECK exists).
    assertThatThrownBy(() -> checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "Negative", -1)))
        .isInstanceOf(PmChecklistService.ChecklistValidationException.class);
    assertThatThrownBy(() -> checklist.createItem(staffUser, new CreateItemCommand(checksheetId,
        null, -5, "Negative seq", null, PmItemInputType.OK_NG, null, null, null, null, null,
        null, null)))
        .isInstanceOf(PmChecklistService.ChecklistValidationException.class);
  }

  // -------------------------------------------------------------------------
  // AC4: gates & scope
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-INT-009 P0 out-of-scope leader: 403 on mutations, invisible on scoped reads")
  void outOfScopeLeaderRejectedAndInvisible() {
    checklist.createCategory(staffUser, new CreateCategoryCommand(checksheetId, "Hidden", 1));

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
    users.saveAndFlush(new AuthUserEntity(otherId, "other-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SECTION_LEADER, true, T0, T0));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), otherMachine.getId(), otherId, ResponsibilityLevel.LEADER, T0, T0));
    var otherUser = new AuthenticatedUser(otherId.toString(), "other@test",
        ApplicationRole.SECTION_LEADER);

    assertThatThrownBy(() -> checklist.createCategory(otherUser,
        new CreateCategoryCommand(checksheetId, "Nope", 1)))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
    assertThatThrownBy(() -> checklist.createItem(otherUser, new CreateItemCommand(checksheetId,
        null, null, "Nope", null, PmItemInputType.OK_NG, null, null, null, null, null, null,
        null)))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
    // Scoped reads: the checksheet's content is invisible (empty list), and a direct
    // get of a known item id is a 403.
    var existing = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null,
        null, "Visible to owner", null, PmItemInputType.OK_NG, null, null, null, null, null,
        null, null));
    assertThat(checklist.listCategories(otherUser, checksheetId)).isEmpty();
    assertThat(checklist.listItems(otherUser, checksheetId, null)).isEmpty();
    assertThatThrownBy(() -> checklist.getItem(otherUser, existing.id()))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
  }

  @Test
  @DisplayName("19.2-INT-010 P0 STAFF_MAINTENANCE in scope may define and edit (staff-level surface)")
  void staffInScopeCanDefineAndEdit() {
    var category = checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "Electrical", 1));
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, category.id(),
        null, "Terminal tightness", null, PmItemInputType.OK_NG, null, null, null, null, null,
        null, null));
    var renamed = checklist.updateCategory(staffUser, category.id(),
        new UpdateCategoryCommand("Electrical - HV", null));
    assertThat(renamed.name()).isEqualTo("Electrical - HV");
    // Null-merge: sortOrder kept when omitted.
    assertThat(renamed.sortOrder()).isEqualTo(1);
    var edited = checklist.updateItem(staffUser, item.id(), new UpdateItemCommand(null, null,
        "Terminal tightness (torque marked)", null, PmItemInputType.OK_NG, null, null, null,
        null, true, null, null));
    assertThat(edited.criticalFlag()).isTrue();
    checklist.deleteItem(staffUser, edited.id());
    assertThat(checklist.listItems(staffUser, checksheetId, null)).isEmpty();
  }

  @Test
  @DisplayName("19.2-INT-011 P0 SUPER_ADMIN with no assignments bypasses every gate (create/read/update/delete)")
  void superAdminBypassesScope() {
    var adminId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(adminId, "admin-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.SUPER_ADMIN, true, T0, T0));
    // Deliberately NO plant assignment and NO machine responsibility.
    var admin = new AuthenticatedUser(adminId.toString(), "admin@test",
        ApplicationRole.SUPER_ADMIN);

    var category = checklist.createCategory(admin,
        new CreateCategoryCommand(checksheetId, "Admin category", 1));
    var item = checklist.createItem(admin, new CreateItemCommand(checksheetId, category.id(),
        null, "Admin item", null, PmItemInputType.OK_NG, null, null, null, null, null, null,
        null));
    assertThat(checklist.listCategories(admin, checksheetId)).hasSize(1);
    assertThat(checklist.getItem(admin, item.id()).parameterText()).isEqualTo("Admin item");
    checklist.updateItem(admin, item.id(), new UpdateItemCommand(null, null, "Renamed by admin",
        null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    checklist.deleteItem(admin, item.id());
    checklist.deleteCategory(admin, category.id());
    assertThat(checklist.listCategories(admin, checksheetId)).isEmpty();
  }

  @Test
  @DisplayName("19.2-INT-012 P0 TECHNICIAN (below the four-role set) is denied mutations")
  void technicianDenied() {
    var techId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(techId, "tech-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.TECHNICIAN, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(techId, plant.getId(), T0));
    var tech = new AuthenticatedUser(techId.toString(), "tech@test", ApplicationRole.TECHNICIAN);

    assertThatThrownBy(() -> checklist.createCategory(tech,
        new CreateCategoryCommand(checksheetId, "Nope", 1)))
        .isInstanceOf(PmChecksheetService.PmChecksheetForbiddenException.class);
  }

  // -------------------------------------------------------------------------
  // Category delete orphaning & update null-merge
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-INT-013 P0 category delete nulls item.categoryId explicitly; orphaning is audit-logged")
  void categoryDeleteOrphansItemsWithAudit() {
    var category = checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "Pneumatics", 1));
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, category.id(),
        null, "Air pressure", null, PmItemInputType.MEASUREMENT, "bar", new BigDecimal("4"),
        null, new BigDecimal("8"), null, null, null));

    checklist.deleteCategory(staffUser, category.id());

    var orphaned = checklist.getItem(staffUser, item.id());
    assertThat(orphaned.categoryId()).isNull();
    assertThat(checklist.listCategories(staffUser, checksheetId)).isEmpty();

    // The orphaning UPDATE carries previous (categoryId set) and new (null) values.
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(item.id()))
        .anySatisfy(entry -> {
          assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_CHECKLIST_ITEM);
          assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
          assertThat(entry.getPreviousValue()).contains("\"categoryId\":\"" + category.id() + "\"");
          assertThat(entry.getNewValue()).contains("\"categoryId\":null");
        });
    // The category itself gets a DELETE row with previous values.
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(category.id()))
        .anySatisfy(entry -> {
          assertThat(entry.getAction()).isEqualTo(AuditAction.DELETE);
          assertThat(entry.getPreviousValue()).contains("\"name\":\"Pneumatics\"");
        });
  }

  @Test
  @DisplayName("19.2-INT-014 P0 item update null-merges omitted optionals and is audited with previous/new")
  void itemUpdateNullMergeAndAudit() {
    var category = checklist.createCategory(staffUser,
        new CreateCategoryCommand(checksheetId, "Cooling", 1));
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, category.id(),
        3, "Coolant pH", "Litmus", PmItemInputType.MEASUREMENT, "pH", new BigDecimal("6"),
        new BigDecimal("7"), new BigDecimal("8"), true, "SPEC-9", null));

    // PUT with only parameterText + inputType: category/sequence/bounds/criticality keep values.
    var updated = checklist.updateItem(staffUser, item.id(), new UpdateItemCommand(null, null,
        "Coolant pH (weekly)", null, PmItemInputType.MEASUREMENT, null, null, null, null,
        null, null, null));
    assertThat(updated.categoryId()).isEqualTo(category.id());
    assertThat(updated.sequence()).isEqualTo(3);
    assertThat(updated.lsl()).isEqualByComparingTo("6");
    assertThat(updated.usl()).isEqualByComparingTo("8");
    assertThat(updated.criticalFlag()).isTrue();
    assertThat(updated.unit()).isEqualTo("pH");
    assertThat(updated.nominal()).isEqualByComparingTo("7");
    assertThat(updated.createdAt()).isEqualTo(item.createdAt());

    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(item.id()))
        .anySatisfy(entry -> {
          assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
          assertThat(entry.getPreviousValue()).contains("Coolant pH\"");
          assertThat(entry.getNewValue()).contains("Coolant pH (weekly)");
        });
  }

  @Test
  @DisplayName("19.2-INT-015 P0 item delete is audited with previous values")
  void itemDeleteAudited() {
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, null,
        "Temporary", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    checklist.deleteItem(staffUser, item.id());

    assertThatThrownBy(() -> checklist.getItem(staffUser, item.id()))
        .isInstanceOf(PmChecklistService.PmChecklistItemNotFoundException.class);
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(item.id()))
        .anySatisfy(entry -> {
          assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.PM_CHECKLIST_ITEM);
          assertThat(entry.getAction()).isEqualTo(AuditAction.DELETE);
          assertThat(entry.getPreviousValue()).contains("\"parameterText\":\"Temporary\"");
          assertThat(entry.getNewValue()).isNull();
        });
  }

  @Test
  @DisplayName("19.2-INT-016 P0 MANAGER_MAINTENANCE (plant) and MAINTENANCE_LEADER (group) both pass the gate in scope")
  void managerAndMaintenanceLeaderInScope() {
    var managerId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(managerId, "manager-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.MANAGER_MAINTENANCE, true, T0, T0));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(managerId, plant.getId(), T0));
    var manager = new AuthenticatedUser(managerId.toString(), "manager@test",
        ApplicationRole.MANAGER_MAINTENANCE);
    var category = checklist.createCategory(manager,
        new CreateCategoryCommand(checksheetId, "Manager cat", 1));
    assertThat(category.name()).isEqualTo("Manager cat");

    var leaderId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(leaderId, "mleader-" + UUID.randomUUID() + "@test",
        "hash", ApplicationRole.MAINTENANCE_LEADER, true, T0, T0));
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine.getId(), leaderId, ResponsibilityLevel.LEADER, T0, T0));
    var mLeader = new AuthenticatedUser(leaderId.toString(), "mleader@test",
        ApplicationRole.MAINTENANCE_LEADER);
    var item = checklist.createItem(mLeader, new CreateItemCommand(checksheetId, null, null,
        "Leader item", null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    assertThat(item.parameterText()).isEqualTo("Leader item");
  }

  @Test
  @DisplayName("19.2-INT-017 P0 long parameterText truncates entity_label to a 255-char code-point-safe prefix")
  void longParameterTextTruncatesAuditLabel() {
    var longText = "T".repeat(300);
    var item = checklist.createItem(staffUser, new CreateItemCommand(checksheetId, null, null,
        longText, null, PmItemInputType.OK_NG, null, null, null, null, null, null, null));
    assertThat(item.parameterText()).isEqualTo(longText);

    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(item.id()))
        .anySatisfy(entry -> {
          assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
          assertThat(entry.getEntityLabel()).hasSize(255);
          assertThat(entry.getEntityLabel()).isEqualTo(longText.substring(0, 255));
        });
  }
}
