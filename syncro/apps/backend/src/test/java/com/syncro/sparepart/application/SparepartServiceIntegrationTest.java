package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.sparepart.application.SparepartService.DuplicateSparepartException;
import com.syncro.sparepart.application.SparepartService.SparepartCommand;
import com.syncro.sparepart.application.SparepartService.SparepartFilters;
import com.syncro.sparepart.application.SparepartService.SparepartMutationForbiddenException;
import com.syncro.sparepart.application.SparepartService.SparepartNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartReviewTransitionException;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyDimensionMismatchException;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyReferenceNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartValidationException;
import com.syncro.sparepart.domain.BomReviewStatus;
import com.syncro.sparepart.domain.SparepartDerivation;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class SparepartServiceIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired
  private SparepartService sparepartService;

  @Autowired
  private SparepartRepository spareparts;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("2.5-SVC-001 P1 MANAGER_MAINTENANCE creates normalized sparepart with taxonomy references")
  void manageCreatesNormalizedSparepartWithTaxonomyReferences() {
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-sparepart@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    var refs = taxonomyRefs();

    var created = sparepartService.create(user, command(" PLC-WECON-LX5 ", " Wecon LX5 PLC ", machine, refs));

    assertThat(created.code()).startsWith("MCH-1PLANT-1ELEPLCWEC");
    assertThat(created.code()).endsWith("000");
    assertThat(created.category().id()).isEqualTo(refs.category().getId());
    assertThat(created.brand().id()).isEqualTo(refs.brand().getId());
    assertThat(created.kind().id()).isEqualTo(refs.kind().getId());
    assertThat(created.type().id()).isEqualTo(refs.type().getId());
    assertThat(spareparts.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.R-SVC-003 P1 generated BOM sparepart code increments for same machine, category, kind, and brand while excluding type")
  void generatedBomSparepartCodeIncrementsForSameBomPrefixExcludingType() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var samePrefixDifferentType = new TaxonomyRefs(
        refs.category(),
        refs.brand(),
        refs.kind(),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX7", "LX7", refs.category(),
            Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"))));
    var machine = machine();
    var first = sparepartService.create(admin, command("IGNORED-1", "Wecon LX5 PLC", machine, refs));
    var second = sparepartService.create(admin, command("IGNORED-2", "Wecon LX7 PLC", machine, samePrefixDifferentType));

    assertThat(first.code()).isEqualTo("MCH-1PLANT-1ELEPLCWEC000");
    assertThat(second.code()).isEqualTo("MCH-1PLANT-1ELEPLCWEC001");
  }

  @Test
  @DisplayName("2.R-SVC-004 P0 same machine and taxonomy identity is rejected even when code is client supplied differently")
  void sameMachineAndTaxonomyIdentityRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var machine = machine();
    sparepartService.create(admin, command("CLIENT-1", "Wecon LX5 PLC", machine, refs));

    assertThatThrownBy(() -> sparepartService.create(admin, command("CLIENT-2", "Wecon LX5 PLC Duplicate", machine, refs)))
        .isInstanceOf(DuplicateSparepartException.class);
  }

  @Test
  @DisplayName("2.5-SVC-005 P1 list filters by category, brand, kind, type, and search")
  void listFiltersByTaxonomyAndSearch() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var otherRefs = taxonomyRefs("MECHANIC", "Mechanical", "OMRON", "Omron", "RELAY", "Relay", "MY2N", "MY2N");
    var plc = sparepartService.create(admin, command("PLC-WECON-LX5", "Wecon LX5 PLC", refs));
    sparepartService.create(admin, command("RELAY-OMRON-MY2N", "Omron MY2N Relay", otherRefs));

    var result = sparepartService.list(admin, new SparepartFilters(
        refs.category().getId(), refs.brand().getId(), refs.kind().getId(), refs.type().getId(), "lx5", null, null, null), PageRequest.of(0, 200));

    assertThat(result.items()).extracting(sparepart -> sparepart.id()).containsExactly(plc.id());
  }

  @Test
  @DisplayName("2.5-SVC-006 P1 list paginates results and reports total count")
  void listPaginatesResultsAndReportsTotalCount() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var alphaRefs = taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "LX5", "LX5");
    var betaRefs = taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "LX7", "LX7");
    var gammaRefs = taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "LX9", "LX9");
    sparepartService.create(admin, command("PLC-001", "Alpha PLC", alphaRefs));
    var beta = sparepartService.create(admin, command("PLC-002", "Beta PLC", betaRefs));
    sparepartService.create(admin, command("PLC-003", "Gamma PLC", gammaRefs));

    var result = sparepartService.list(admin, new SparepartFilters(null, null, null, null, null, null, null, null), PageRequest.of(1, 1));

    assertThat(result.items()).extracting(sparepart -> sparepart.id()).containsExactly(beta.id());
    assertThat(result.totalElements()).isEqualTo(3);
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("2.5-SVC-007 P1 list search treats wildcard characters literally")
  void listSearchTreatsWildcardCharactersLiterally() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var literalRefs = taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "100%", "100%");
    var otherRefs = taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "1000", "1000");
    var literal = sparepartService.create(admin, command("PLC_10", "Percent 100% PLC", literalRefs));
    sparepartService.create(admin, command("PLC-10", "Percent 1000 PLC", otherRefs));

    var underscore = sparepartService.list(admin, new SparepartFilters(null, null, null, null, "_", null, null, null), PageRequest.of(0, 200));
    var percent = sparepartService.list(admin, new SparepartFilters(null, null, null, null, "100%", null, null, null), PageRequest.of(0, 200));

    assertThat(underscore.items()).isEmpty();
    assertThat(percent.items()).extracting(sparepart -> sparepart.id()).containsExactly(literal.id());
  }

  @Test
  @DisplayName("2.5-SVC-008 P1 update changes sparepart fields and preserves id")
  void updateChangesSparepartFieldsAndPreservesId() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var created = sparepartService.create(admin, command("PLC-WECON-LX5", "Wecon LX5 PLC", refs));

    var updated = sparepartService.update(admin, created.id(), command("PLC-WECON-LX5-A", "Wecon LX5 PLC A", refs));

    assertThat(updated.id()).isEqualTo(created.id());
    assertThat(updated.code()).isEqualTo(created.code());
    assertThat(updated.category().id()).isEqualTo(refs.category().getId());
  }

  @Test
  @DisplayName("2.5-SVC-007 P0 AUDITOR can list spareparts but cannot mutate")
  void viewerCanListButCannotMutateSpareparts() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-sparepart@syncro.dev");
    var refs = taxonomyRefs();
    var created = sparepartService.create(admin, command("PLC-WECON-LX5", "Wecon LX5 PLC", refs));

    assertThat(sparepartService.list(viewer, new SparepartFilters(null, null, null, null, null, null, null, null), PageRequest.of(0, 200)).items()).extracting(sparepart -> sparepart.id()).containsExactly(created.id());
    assertThatThrownBy(() -> sparepartService.create(viewer, command("PLC-WECON-LX5-B", "Wecon LX5 PLC Backup", refs)))
        .isInstanceOf(SparepartMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.5-SVC-008 P1 missing taxonomy reference is rejected safely")
  void missingTaxonomyReferenceRejectedSafely() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();

    assertThatThrownBy(() -> sparepartService.create(admin, new SparepartCommand(
        machine().getId(), UUID.randomUUID(), refs.brand().getId(), refs.kind().getId(), refs.type().getId())))
        .isInstanceOf(SparepartTaxonomyReferenceNotFoundException.class);
  }

  @Test
  @DisplayName("2.5-SVC-009 P1 wrong taxonomy dimension is rejected safely")
  void wrongTaxonomyDimensionRejectedSafely() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();

    assertThatThrownBy(() -> sparepartService.create(admin, new SparepartCommand(
        machine().getId(), refs.brand().getId(), refs.brand().getId(), refs.kind().getId(), refs.type().getId())))
        .isInstanceOf(SparepartTaxonomyDimensionMismatchException.class);
  }

  @Test
  @DisplayName("2.R-SVC-002 P0 mismatched linked taxonomy is rejected safely")
  void mismatchedLinkedTaxonomyRejectedSafely() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var otherRefs = taxonomyRefs("MECHANIC", "Mechanic", "OMRON", "Omron", "RELAY", "Relay", "MY2N", "MY2N");

    assertThatThrownBy(() -> sparepartService.create(admin, new SparepartCommand(
        machine().getId(), refs.category().getId(), otherRefs.brand().getId(), refs.kind().getId(), refs.type().getId())))
        .isInstanceOf(SparepartTaxonomyDimensionMismatchException.class);
  }

  @Test
  @DisplayName("2.5-SVC-010 P1 missing sparepart entry is rejected safely")
  void missingSparepartEntryRejectedSafely() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> sparepartService.get(admin, UUID.randomUUID()))
        .isInstanceOf(SparepartNotFoundException.class);
  }

  @Test
  @DisplayName("2.5-SVC-011 P1 blank and missing command fields are rejected")
  void blankAndMissingCommandFieldsRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();

    assertThatThrownBy(() -> sparepartService.create(admin, new SparepartCommand(
        null, refs.category().getId(), refs.brand().getId(), refs.kind().getId(), refs.type().getId())))
        .isInstanceOf(SparepartValidationException.class);
    assertThatThrownBy(() -> sparepartService.create(admin, new SparepartCommand(
        machine().getId(), refs.category().getId(), null, refs.kind().getId(), refs.type().getId())))
        .isInstanceOf(SparepartValidationException.class);
  }

  @Test
  @DisplayName("2.5-SVC-012 P1 delete removes sparepart without dependents")
  void deleteRemovesSparepartWithoutDependents() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var created = sparepartService.create(admin, command("PLC-WECON-LX5", "Wecon LX5 PLC", refs));

    sparepartService.delete(admin, created.id());

    assertThat(spareparts.findById(created.id())).isEmpty();
  }

  @Test
  @DisplayName("2.5-SVC-013 P0 spareparts enforce taxonomy foreign keys")
  void sparepartsEnforceTaxonomyForeignKeys() {
    assertThatThrownBy(() -> spareparts.saveAndFlush(new com.syncro.sparepart.infrastructure.SparepartEntity(
        UUID.randomUUID(), "PLC-WECON-LX5", "Wecon LX5 PLC", null, null, null, null, null,
        Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private SparepartCommand command(String code, String name, TaxonomyRefs refs) {
    return command(code, name, machine(), refs);
  }

  private SparepartCommand command(String code, String name, MachineEntity machine, TaxonomyRefs refs) {
    return new SparepartCommand(machine.getId(), refs.category().getId(), refs.brand().getId(), refs.kind().getId(), refs.type().getId());
  }

  private MachineEntity machine() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var plant = plants.findByCodeIgnoreCase("PLANT-1")
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-1", "Plant 1", now, now)));
    var group = machineGroups.findByPlantIdAndNameIgnoreCase(plant.getId(), "Assembly")
        .orElseGet(() -> machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Assembly", now, now)));
    return machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "MCH-1")
        .orElseGet(() -> machines.saveAndFlush(new MachineEntity(
            UUID.randomUUID(), plant, group, "MCH-1", "Machine 1", MachineStatus.ACTIVE, null, null, null, List.of(), now, now)));
  }

  private MachineEntity machineWithCode(String code) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var plant = plants.findByCodeIgnoreCase("PLANT-1")
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-1", "Plant 1", now, now)));
    var group = machineGroups.findByPlantIdAndNameIgnoreCase(plant.getId(), "Assembly")
        .orElseGet(() -> machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Assembly", now, now)));
    return machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), code)
        .orElseGet(() -> machines.saveAndFlush(new MachineEntity(
            UUID.randomUUID(), plant, group, code, "Machine " + code, MachineStatus.ACTIVE, null, null, null, List.of(), now, now)));
  }

  private TaxonomyRefs taxonomyRefs() {
    return taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "LX5", "LX5");
  }

  private TaxonomyRefs taxonomyRefs(String categoryCode, String categoryName, String brandCode, String brandName,
      String kindCode, String kindName, String typeCode, String typeName) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var category = taxonomy.findByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension.CATEGORY, categoryCode)
        .orElseGet(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, categoryCode, categoryName, now, now)));
    return new TaxonomyRefs(
        category,
        taxonomy.findByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension.BRAND, brandName).orElseGet(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.BRAND, uniqueCode(brandCode), brandName, category, now, now))),
        taxonomy.findByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension.KIND, kindName).orElseGet(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.KIND, uniqueCode(kindCode), kindName, category, now, now))),
        taxonomy.findByDimensionAndNameIgnoreCase(SparepartTaxonomyDimension.TYPE, typeName).orElseGet(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, uniqueCode(typeCode), typeName, category, now, now))));
  }

  private String uniqueCode(String code) {
    return code + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-28T00:00:00Z")));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private record TaxonomyRefs(
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type) {
  }

  // --- DW-121: BOM series LIKE escape ---

  @Test
  @DisplayName("DW-121 underscore in machine code does not leak into another machines BOM series")
  void underscorePrefixDoesNotWildcardMatchSiblingSeries() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();

    // Sibling machine whose code is IDENTICAL to MCH_A except the underscore slot -
    // without the LIKE escape this sibling's series inflates maxSeries (red/green guard).
    var sibling = machineWithCode("MCHAA");
    var first = sparepartService.create(admin, command("IGNORED-SIB", "Sibling PLC", sibling, refs));
    assertThat(first.code()).startsWith("MCHAA").endsWith("000");

    // First sparepart for the underscore machine must get its own series 000, not 001.
    var target = machineWithCode("MCH_A");
    var created = sparepartService.create(admin, command("IGNORED-TGT", "Target PLC", target, refs));

    assertThat(created.code()).contains("MCH_A");
    assertThat(created.code()).endsWith("000");
  }

  // --- Story 8-2: procurement readiness (material code + lead time) ---

  @Autowired
  private com.syncro.machine.infrastructure.MachineResponsibilityRepository responsibilities;
  @Autowired
  private com.syncro.audit.infrastructure.AuditLogRepository auditLogs;
  @Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  @Autowired
  private jakarta.persistence.EntityManager entityManager;

  @Test
  @DisplayName("8.2-SVC-001 P0 LEADER-scoped MANAGER_MAINTENANCE patches procurement values; list and detail expose them")
  void leaderScopedManagePatchesProcurementValues() {
    var manageLeader = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "leader-sparepart@syncro.dev");
    var machine = machine();
    assign(manageLeader, machine.getPlant());
    assignJobScope(manageLeader, machine, com.syncro.machine.domain.ResponsibilityLevel.LEADER);
    var created = sparepartService.create(
        authenticatedUser(ApplicationRole.SUPER_ADMIN), command("PLC-PROC-1", "Wecon LX5 PLC", machine, taxonomyRefs()));

    var patched = sparepartService.patchProcurement(manageLeader, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-001", new java.math.BigDecimal("36")));

    assertThat(patched.materialCode()).isEqualTo("MC-001");
    assertThat(patched.leadTimeHours()).isEqualByComparingTo("36");
    var listed = sparepartService.list(manageLeader,
        new SparepartFilters(null, null, null, null, null, null, null, null), PageRequest.of(0, 200));
    assertThat(listed.items()).extracting(SparepartService.SparepartView::materialCode).contains("MC-001");
    var detail = sparepartService.get(manageLeader, created.id());
    assertThat(detail.leadTimeHours()).isEqualByComparingTo("36");
  }

  @Test
  @DisplayName("8.2-SVC-002 P1 null clears procurement values and the clear is audited")
  void nullClearsProcurementValues() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("PLC-PROC-2", "Wecon LX5 PLC", taxonomyRefs()));
    sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-CLEAR", new java.math.BigDecimal("7.5")));

    var cleared = sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand(null, null));

    assertThat(cleared.materialCode()).isNull();
    assertThat(cleared.leadTimeHours()).isNull();
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getPreviousValue()).contains("MC-CLEAR").contains("7.5");
    assertThat(entry.getNewValue()).contains("\"materialCode\":null");
  }

  @Test
  @DisplayName("8.2-SVC-003 P0 duplicate material code is rejected case-insensitively across spareparts")
  void duplicateMaterialCodeRejectedCaseInsensitively() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var first = sparepartService.create(admin, command("PLC-DUP-A", "Wecon LX5 PLC A", taxonomyRefs()));
    var second = sparepartService.create(admin, command("PLC-DUP-B", "Wecon LX5 PLC B",
        taxonomyRefs("ELECTRIC", "Electric", "OMRON", "Omron", "RELAY", "Relay", "MY2N", "MY2N")));
    sparepartService.patchProcurement(admin, first.id(),
        new SparepartService.SparepartProcurementCommand("MC-DUP", null));

    assertThatThrownBy(() -> sparepartService.patchProcurement(admin, second.id(),
        new SparepartService.SparepartProcurementCommand("mc-dup ", null)))
        .isInstanceOf(SparepartService.DuplicateMaterialCodeException.class);
  }

  @Test
  @DisplayName("8.2-SVC-004 P1 fractional lead time survives persistence at scale 2")
  void fractionalLeadTimeRoundTrips() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("PLC-FRAC", "Wecon LX5 PLC", taxonomyRefs()));

    var patched = sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand(null, new java.math.BigDecimal("7.5")));

    assertThat(patched.leadTimeHours()).isEqualByComparingTo(new java.math.BigDecimal("7.50"));
  }

  @Test
  @DisplayName("8.2-SVC-005 P0 MANAGER_MAINTENANCE without LEADER+ job scope is denied with no mutation and no audit")
  void manageWithoutLeaderScopeDenied() {
    var manageNoScope = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "noscope-sparepart@syncro.dev");
    var machine = machine();
    assign(manageNoScope, machine.getPlant());
    var created = sparepartService.create(
        authenticatedUser(ApplicationRole.SUPER_ADMIN), command("PLC-NOSCOPE", "Wecon LX5 PLC", machine, taxonomyRefs()));
    var auditCountBefore = auditCountFor(created.id());

    assertThatThrownBy(() -> sparepartService.patchProcurement(manageNoScope, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-X", java.math.BigDecimal.ONE)))
        .isInstanceOf(com.syncro.auth.application.JobScopeForbiddenException.class)
        .hasMessageContaining("LEADER");

    assertThat(auditCountFor(created.id())).isEqualTo(auditCountBefore);
    assertThat(spareparts.findById(created.id()).orElseThrow().getMaterialCode()).isNull();
  }

  @Test
  @DisplayName("8.2-SVC-006 P1 SUPER_ADMIN bypasses job scope without responsibility rows")
  void superAdminBypassesJobScope() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("PLC-BYPASS", "Wecon LX5 PLC", taxonomyRefs()));

    var patched = sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-ADMIN", null));

    assertThat(patched.materialCode()).isEqualTo("MC-ADMIN");
  }

  @Test
  @DisplayName("8.2-SVC-007 P0 AUDITOR cannot patch procurement even with LEADER responsibility")
  void viewerDeniedByAppRoleFirst() {
    var viewerWithScope = persistedUser(ApplicationRole.AUDITOR, "viewer-proc@syncro.dev");
    var machine = machine();
    assign(viewerWithScope, machine.getPlant());
    assignJobScope(viewerWithScope, machine, com.syncro.machine.domain.ResponsibilityLevel.LEADER);
    var created = sparepartService.create(
        authenticatedUser(ApplicationRole.SUPER_ADMIN), command("PLC-VIEWER", "Wecon LX5 PLC", machine, taxonomyRefs()));

    assertThatThrownBy(() -> sparepartService.patchProcurement(viewerWithScope, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-V", null)))
        .isInstanceOf(SparepartMutationForbiddenException.class);
  }

  @Test
  @DisplayName("8.2-SVC-008 P1 out-of-plant sparepart is masked as not found for scoped MANAGER_MAINTENANCE")
  void wrongPlantSparepartMaskedAsNotFound() {
    var outsider = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "outsider-sparepart@syncro.dev");
    assignJobScope(outsider, machine(), com.syncro.machine.domain.ResponsibilityLevel.MANAGER);
    var created = sparepartService.create(
        authenticatedUser(ApplicationRole.SUPER_ADMIN), command("PLC-OTHER", "Wecon LX5 PLC", taxonomyRefs()));
    // create() itself writes a CREATE audit row — capture the baseline before the denied PATCH.
    var auditCountBefore = auditCountFor(created.id());

    assertThatThrownBy(() -> sparepartService.patchProcurement(outsider, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-O", null)))
        .isInstanceOf(SparepartNotFoundException.class);
    assertThat(auditCountFor(created.id())).isEqualTo(auditCountBefore);
  }

  @Test
  @DisplayName("8.2-SVC-009 P0 audit entry captures previous and new procurement values")
  void auditCapturesPreviousAndNewValues() throws Exception {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("PLC-AUDIT", "Wecon LX5 PLC", taxonomyRefs()));

    sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-A1", new java.math.BigDecimal("180")));

    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var entry = latestAuditEntryFor(created.id());
    var previous = mapper.readTree(entry.getPreviousValue());
    var newValue = mapper.readTree(entry.getNewValue());
    assertThat(previous.get("materialCode").isNull()).isTrue();
    assertThat(newValue.get("materialCode").asText()).isEqualTo("MC-A1");
    // Scale in the audit JSON follows the submitted value; compare numerically.
    assertThat(new java.math.BigDecimal(newValue.get("leadTimeHours").asText()))
        .isEqualByComparingTo("180");
  }

  @Test
  @DisplayName("8.2-SVC-010 P0 DB constraint name backing DuplicateMaterialCodeException matches the index")
  void materialCodeConstraintNameMatchesServiceConstant() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var first = sparepartService.create(admin, command("PLC-DBC-1", "Wecon LX5 PLC A", refs));
    var second = sparepartService.create(admin, command("PLC-DBC-2", "Wecon LX5 PLC B",
        taxonomyRefs("ELECTRIC", "Electric", "OMRON", "Omron", "RELAY", "Relay", "MY2N", "MY2N")));
    sparepartService.patchProcurement(admin, first.id(),
        new SparepartService.SparepartProcurementCommand("MC-DBC", null));

    // Insert a conflicting row directly, bypassing the service pre-check, so the DB-level
    // index (the real TOCTOU backstop) fires — exactly the violation save() translates.
    // Raw value carries no normalization (service trims), so it must match exactly.
    assertThatThrownBy(() -> {
      var entity = spareparts.findById(second.id()).orElseThrow();
      entity.updateProcurement("mc-dbc", null, Instant.parse("2026-05-28T00:00:00Z"));
      spareparts.saveAndFlush(entity);
    }).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("8.2-SVC-011 P1 repeated identical PATCH is a no-op: no extra audit row")
  void identicalPatchIsNoOpWithoutAuditRow() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("PLC-NOOP", "Wecon LX5 PLC", taxonomyRefs()));
    sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-NOOP", new java.math.BigDecimal("36")));
    var auditCountAfterSet = auditCountFor(created.id());
    var updatedAtAfterSet = spareparts.findById(created.id()).orElseThrow().getUpdatedAt();

    sparepartService.patchProcurement(admin, created.id(),
        new SparepartService.SparepartProcurementCommand("MC-NOOP", new java.math.BigDecimal("36.00")));

    assertThat(auditCountFor(created.id())).isEqualTo(auditCountAfterSet);
    assertThat(spareparts.findById(created.id()).orElseThrow().getUpdatedAt()).isEqualTo(updatedAtAfterSet);
  }

  private void assignJobScope(AuthenticatedUser user, MachineEntity machine,
      com.syncro.machine.domain.ResponsibilityLevel level) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var userEntity = users.findById(UUID.fromString(user.id())).orElseThrow();
    responsibilities.saveAndFlush(new com.syncro.machine.infrastructure.MachineResponsibilityEntity(
        UUID.randomUUID(), machine, userEntity, level, now, now));
  }

  private com.syncro.audit.infrastructure.AuditLogEntity latestAuditEntryFor(UUID entityId) {
    return auditLogs.findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
        .stream()
        .filter(entry -> entityId.equals(entry.getEntityId()))
        .findFirst()
        .orElseThrow();
  }

  private long auditCountFor(UUID entityId) {
    return auditLogs.findAll().stream().filter(entry -> entityId.equals(entry.getEntityId())).count();
  }

  // --- Story 18-1: BOM master identity and review lifecycle ---

  @Test
  @DisplayName("18.1-SVC-001 P0 create starts PENDING_REVIEW with derived BOM identity fields")
  void createStartsPendingReviewWithDerivedBomIdentity() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var machine = machine();

    var created = sparepartService.create(admin, command("IGNORED-1801", "Wecon LX5 PLC", machine, refs));

    assertThat(created.reviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
    assertThat(created.bomCodeVersion()).isEqualTo(1);
    assertThat(created.bomSerial()).isEqualTo("000");
    assertThat(created.bomCode()).isEqualTo("MCH-1PLANT-1ELEPLCWEC000");
    assertThat(created.code()).isEqualTo(created.bomCode());
    assertThat(created.rejectionReason()).isNull();
    assertThat(created.hierarchyIdentityKey()).isEqualTo(SparepartDerivation.hierarchyIdentityKey(
        "MCH-1", "PLANT-1", refs.category().getCode(), refs.kind().getCode(), refs.brand().getCode(), refs.type().getCode()));
    var stored = spareparts.findById(created.id()).orElseThrow();
    assertThat(stored.getReviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
    assertThat(stored.getBomCode()).isEqualTo("MCH-1PLANT-1ELEPLCWEC000");
    assertThat(stored.getHierarchyIdentityKey()).isEqualTo(created.hierarchyIdentityKey());
  }

  @Test
  @DisplayName("18.1-SVC-002 P0 approve transitions PENDING_REVIEW to ACTIVE, clears reason, and audits")
  void approveTransitionsToActiveAndAudits() throws Exception {
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "approve-1801@syncro.dev");
    var machine = machine();
    assign(manager, machine.getPlant());
    var created = sparepartService.create(manager, command("IGNORED-1802", "Wecon LX5 PLC", machine, taxonomyRefs()));

    var approved = sparepartService.approve(manager, created.id());

    assertThat(approved.reviewStatus()).isEqualTo(BomReviewStatus.ACTIVE);
    assertThat(approved.rejectionReason()).isNull();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(com.syncro.audit.domain.AuditAction.UPDATE);
    assertThat(entry.getActorName()).isEqualTo("approve-1801@syncro.dev");
    assertThat(mapper.readTree(entry.getPreviousValue()).get("reviewStatus").asText()).isEqualTo("PENDING_REVIEW");
    assertThat(mapper.readTree(entry.getNewValue()).get("reviewStatus").asText()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("18.1-SVC-003 P0 reject transitions to REJECTED with stored reason and audit")
  void rejectStoresReasonAndAudits() throws Exception {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("IGNORED-1803", "Wecon LX5 PLC", taxonomyRefs()));

    var rejected = sparepartService.reject(admin, created.id(), "Duplicate of existing LX5 module");

    assertThat(rejected.reviewStatus()).isEqualTo(BomReviewStatus.REJECTED);
    assertThat(rejected.rejectionReason()).isEqualTo("Duplicate of existing LX5 module");
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var entry = latestAuditEntryFor(created.id());
    assertThat(mapper.readTree(entry.getNewValue()).get("reviewStatus").asText()).isEqualTo("REJECTED");
    assertThat(mapper.readTree(entry.getNewValue()).get("rejectionReason").asText()).isEqualTo("Duplicate of existing LX5 module");
  }

  @Test
  @DisplayName("18.1-SVC-004 P0 reject without a reason is a validation error with no transition")
  void rejectWithoutReasonRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("IGNORED-1804", "Wecon LX5 PLC", taxonomyRefs()));

    assertThatThrownBy(() -> sparepartService.reject(admin, created.id(), "   "))
        .isInstanceOf(SparepartValidationException.class);
    assertThat(spareparts.findById(created.id()).orElseThrow().getReviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
  }

  @Test
  @DisplayName("18.1-SVC-005 P0 review transitions from non-PENDING_REVIEW targets are rejected")
  void reviewTransitionFromTerminalStateRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var approved = sparepartService.create(admin, command("IGNORED-1805A", "Wecon LX5 PLC",
        taxonomyRefs("ELECTRIC", "Electric", "WECON", "Wecon", "PLC", "PLC", "LX5", "LX5")));
    sparepartService.approve(admin, approved.id());
    var rejected = sparepartService.create(admin, command("IGNORED-1805B", "Omron MY2N Relay",
        taxonomyRefs("MECHANIC", "Mechanic", "OMRON", "Omron", "RELAY", "Relay", "MY2N", "MY2N")));
    sparepartService.reject(admin, rejected.id(), "obsolete");

    assertThatThrownBy(() -> sparepartService.approve(admin, approved.id()))
        .isInstanceOf(SparepartReviewTransitionException.class);
    assertThatThrownBy(() -> sparepartService.reject(admin, approved.id(), "second thought"))
        .isInstanceOf(SparepartReviewTransitionException.class);
    assertThatThrownBy(() -> sparepartService.approve(admin, rejected.id()))
        .isInstanceOf(SparepartReviewTransitionException.class);
  }

  @Test
  @DisplayName("18.1-SVC-006 P0 non-privileged roles cannot approve or reject")
  void nonPrivilegedRolesCannotReview() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("IGNORED-1806", "Wecon LX5 PLC", taxonomyRefs()));
    var inventory = authenticatedUser(ApplicationRole.INVENTORY_MAINTENANCE);
    var staff = authenticatedUser(ApplicationRole.STAFF_MAINTENANCE);
    var technician = authenticatedUser(ApplicationRole.TECHNICIAN);

    assertThatThrownBy(() -> sparepartService.approve(inventory, created.id()))
        .isInstanceOf(SparepartMutationForbiddenException.class);
    assertThatThrownBy(() -> sparepartService.reject(staff, created.id(), "nope"))
        .isInstanceOf(SparepartMutationForbiddenException.class);
    assertThatThrownBy(() -> sparepartService.approve(technician, created.id()))
        .isInstanceOf(SparepartMutationForbiddenException.class);
    assertThat(spareparts.findById(created.id()).orElseThrow().getReviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
  }

  @Test
  @DisplayName("18.1-SVC-007 P0 duplicate full identity is rejected before BOM allocation")
  void duplicateHierarchyIdentityRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var machine = machine();
    sparepartService.create(admin, command("IGNORED-1807A", "Wecon LX5 PLC", machine, refs));

    assertThatThrownBy(() -> sparepartService.create(admin, command("IGNORED-1807B", "Wecon LX5 PLC again", machine, refs)))
        .isInstanceOf(DuplicateSparepartException.class);
  }

  @Test
  @DisplayName("18.1-SVC-008 P1 serial increments across same-prefix creates excluding type; bom fields track the code")
  void bomIdentityFieldsIncrementAcrossSamePrefixTypes() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var machine = machine();
    var lx7 = new TaxonomyRefs(
        refs.category(),
        refs.brand(),
        refs.kind(),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX7", "LX7", refs.category(),
            Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"))));

    var first = sparepartService.create(admin, command("IGNORED-1808A", "Wecon LX5 PLC", machine, refs));
    var second = sparepartService.create(admin, command("IGNORED-1808B", "Wecon LX7 PLC", machine, lx7));

    assertThat(first.bomSerial()).isEqualTo("000");
    assertThat(second.bomSerial()).isEqualTo("001");
    assertThat(second.bomCode()).isEqualTo("MCH-1PLANT-1ELEPLCWEC001");
    assertThat(first.hierarchyIdentityKey()).isNotEqualTo(second.hierarchyIdentityKey());
    assertThat(first.bomCodeVersion()).isEqualTo(1);
    assertThat(second.bomCodeVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("18.1-SVC-009 P1 update with changed prefix re-derives code and bumps version; stable prefix keeps identity")
  void updateWithChangedPrefixReDerivesCodeAndBumpsVersion() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machine();
    var created = sparepartService.create(admin, command("IGNORED-1809A", "Wecon LX5 PLC", machine, taxonomyRefs()));

    var samePrefix = sparepartService.update(admin, created.id(),
        new SparepartCommand(machine.getId(), created.category().id(), created.brand().id(), created.kind().id(), created.type().id()));
    assertThat(samePrefix.bomCode()).isEqualTo(created.bomCode());
    assertThat(samePrefix.bomSerial()).isEqualTo("000");
    assertThat(samePrefix.bomCodeVersion()).isEqualTo(1);

    var omronKindPlc = taxonomyRefs("ELECTRIC", "Electric", "OMRON", "Omron", "PLC", "PLC", "LX5", "LX5");
    var changedPrefix = sparepartService.update(admin, created.id(), command("IGNORED-1809B", "Omron LX5 PLC", machine, omronKindPlc));

    var expectedPrefix = SparepartDerivation.bomPrefix("MCH-1", "PLANT-1", "ELECTRIC", "PLC", omronKindPlc.brand().getCode());
    assertThat(changedPrefix.bomCodeVersion()).isEqualTo(2);
    assertThat(changedPrefix.bomCode()).startsWith(expectedPrefix);
    assertThat(changedPrefix.bomSerial()).isEqualTo("001");
    assertThat(changedPrefix.code()).isEqualTo(changedPrefix.bomCode());
    assertThat(changedPrefix.hierarchyIdentityKey()).endsWith("|" + omronKindPlc.type().getCode());
  }

  @Test
  @DisplayName("18.1-SVC-010 P1 list filters by reviewStatus")
  void listFiltersByReviewStatus() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machine();
    var refs = taxonomyRefs();
    var lx7 = new TaxonomyRefs(
        refs.category(),
        refs.brand(),
        refs.kind(),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX7", "LX7", refs.category(),
            Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"))));
    var pending = sparepartService.create(admin, command("IGNORED-1810A", "Wecon LX5 PLC", machine, refs));
    var approved = sparepartService.create(admin, command("IGNORED-1810B", "Wecon LX7 PLC", machine, lx7));
    sparepartService.approve(admin, approved.id());

    var activeOnly = sparepartService.list(admin,
        new SparepartFilters(null, null, null, null, null, null, null, BomReviewStatus.ACTIVE), PageRequest.of(0, 200));
    var pendingOnly = sparepartService.list(admin,
        new SparepartFilters(null, null, null, null, null, null, null, BomReviewStatus.PENDING_REVIEW), PageRequest.of(0, 200));

    assertThat(activeOnly.items()).extracting(SparepartService.SparepartView::id).containsExactly(approved.id());
    assertThat(pendingOnly.items()).extracting(SparepartService.SparepartView::id).containsExactly(pending.id());
    // Guard the countQuery: the filter must narrow totalElements, not just the page items.
    assertThat(activeOnly.totalElements()).isEqualTo(1);
    assertThat(pendingOnly.totalElements()).isEqualTo(1);
  }

  @Test
  @DisplayName("18.1-SVC-011 P0 two completions on one machine both succeed (null hierarchy key, no collision)")
  void twoCompletionsOnSameMachineBothSucceed() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machine();
    taxonomyRefs(); // ensures the ELECTRIC category the completion path requires exists

    var first = sparepartService.createForCompletion(admin, machine.getId(), "MC-COMP-A");
    var second = sparepartService.createForCompletion(admin, machine.getId(), "MC-COMP-B");

    assertThat(first.reviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
    assertThat(second.reviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
    assertThat(first.hierarchyIdentityKey()).isNull();
    assertThat(second.hierarchyIdentityKey()).isNull();
    assertThat(first.bomSerial()).isEqualTo("000");
    assertThat(second.bomSerial()).isEqualTo("001");
    assertThat(first.bomCode()).isNotEqualTo(second.bomCode());
  }

  @Test
  @DisplayName("18.1-SVC-012 P0 MANAGER_MAINTENANCE outside the sparepart plant cannot approve (masked 404)")
  void managerOutsidePlantCannotApprove() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var outsider = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "outsider-approve@syncro.dev");
    var created = sparepartService.create(admin, command("IGNORED-1812", "Wecon LX5 PLC", machine(), taxonomyRefs()));

    assertThatThrownBy(() -> sparepartService.approve(outsider, created.id()))
        .isInstanceOf(SparepartNotFoundException.class);
    assertThat(spareparts.findById(created.id()).orElseThrow().getReviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
  }

  @Test
  @DisplayName("18.1-SVC-013 P0 sequential double approve: second is INVALID_REVIEW_TRANSITION (row lock)")
  void sequentialDoubleApproveRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sparepartService.create(admin, command("IGNORED-1813", "Wecon LX5 PLC", taxonomyRefs()));

    var approved = sparepartService.approve(admin, created.id());
    assertThat(approved.reviewStatus()).isEqualTo(BomReviewStatus.ACTIVE);

    assertThatThrownBy(() -> sparepartService.approve(admin, created.id()))
        .isInstanceOf(SparepartReviewTransitionException.class);
  }

  @Test
  @DisplayName("18.1-SVC-014 P1 update that changes the prefix re-queues a reviewed sparepart and clears the reason")
  void prefixChangeRequeuesReviewedSparepartAndClearsReason() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machine();
    var created = sparepartService.create(admin, command("IGNORED-1814A", "Wecon LX5 PLC", machine, taxonomyRefs()));
    // Reject first so the re-queue must clear a non-null reason (a fresh create would make the
    // null assertion vacuous).
    var rejected = sparepartService.reject(admin, created.id(), "obsolete");
    assertThat(rejected.rejectionReason()).isEqualTo("obsolete");

    var omronBrand = taxonomyRefs("ELECTRIC", "Electric", "OMRON", "Omron", "PLC", "PLC", "LX5", "LX5");
    var updated = sparepartService.update(admin, created.id(), command("IGNORED-1814B", "Omron LX5 PLC", machine, omronBrand));

    assertThat(updated.reviewStatus()).isEqualTo(BomReviewStatus.PENDING_REVIEW);
    assertThat(updated.bomCodeVersion()).isEqualTo(2);
    assertThat(updated.rejectionReason()).isNull();
  }

  @Test
  @DisplayName("18.1-SVC-015 P1 legacy NULL-BOM row back-fills serial from code on stable-prefix update, version stays 1")
  void legacyRowBackFillsSerialWithoutVersionBump() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machine();
    var created = sparepartService.create(admin, command("IGNORED-1815", "Wecon LX5 PLC", machine, taxonomyRefs()));
    // Simulate a pre-18-1 seed row: BOM columns cleared, review status ACTIVE (pilot-seed idiom).
    jdbcTemplate.update("update spareparts set hierarchy_identity_key = null, bom_serial = null, "
        + "bom_code = null, bom_code_version = null, review_status = 'ACTIVE' where id = ?", created.id());
    entityManager.clear(); // drop the stale managed entity so update() reloads the seeded shape

    var updated = sparepartService.update(admin, created.id(),
        new SparepartCommand(machine.getId(), created.category().id(), created.brand().id(), created.kind().id(), created.type().id()));

    assertThat(updated.bomSerial()).isEqualTo("000");
    assertThat(updated.bomCode()).isEqualTo(created.code());
    assertThat(updated.bomCodeVersion()).isEqualTo(1);
    assertThat(updated.reviewStatus()).isEqualTo(BomReviewStatus.ACTIVE);
    assertThat(updated.hierarchyIdentityKey()).isNotNull();
  }

  @Test
  @DisplayName("18.1-SVC-016 P0 bom_code collision surfaces as DUPLICATE_SPAREPART via save() constraint mapping")
  void bomCodeCollisionMapsToDuplicateSparepart() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var target = machine();
    var landmineMachine = machineWithCode("MCH-9");
    // Landmine: a sparepart on another machine whose bom_code equals what a fresh MCH-1 create
    // will derive (code series empty → 000). Its own code differs, so only the BOM index collides.
    var landmine = new com.syncro.sparepart.infrastructure.SparepartEntity(
        UUID.randomUUID(), "OTHER-900000", "Landmine", landmineMachine,
        refs.category(), refs.brand(), refs.kind(), refs.type(),
        Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"));
    landmine.updateBomIdentity(null, null, "MCH-1PLANT-1ELEPLCWEC000", 1, Instant.parse("2026-05-28T00:00:00Z"));
    spareparts.saveAndFlush(landmine);

    assertThatThrownBy(() -> sparepartService.create(admin, command("IGNORED-1816", "Wecon LX5 PLC", target, refs)))
        .isInstanceOf(DuplicateSparepartException.class);
  }

  @Test
  @DisplayName("18.1-SVC-017 P0 hierarchy_identity_key collision surfaces as DUPLICATE_SPAREPART via save()")
  void hierarchyKeyCollisionMapsToDuplicateSparepart() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var refs = taxonomyRefs();
    var target = machine();
    var landmineMachine = machineWithCode("MCH-8");
    var collidingKey = SparepartDerivation.hierarchyIdentityKey("MCH-1", "PLANT-1",
        refs.category().getCode(), refs.kind().getCode(), refs.brand().getCode(), refs.type().getCode());
    var landmine = new com.syncro.sparepart.infrastructure.SparepartEntity(
        UUID.randomUUID(), "OTHER-800000", "Landmine", landmineMachine,
        refs.category(), refs.brand(), refs.kind(), refs.type(),
        Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"));
    landmine.updateBomIdentity(collidingKey, null, "OTHER-800000", 1, Instant.parse("2026-05-28T00:00:00Z"));
    spareparts.saveAndFlush(landmine);

    assertThatThrownBy(() -> sparepartService.create(admin, command("IGNORED-1817", "Wecon LX5 PLC", target, refs)))
        .isInstanceOf(DuplicateSparepartException.class);
  }

  @Test
  @DisplayName("18.1-SVC-018 P1 over-long hierarchy identity key is rejected as validation, not a DB error")
  void overLongHierarchyKeyRejectedAsValidation() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machine();
    // Four 60-char taxonomy codes + machine/plant codes push the joined key past VARCHAR(255).
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.CATEGORY, longCode("C"), "Category " + longCode("C"), now, now));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.BRAND, longCode("B"), "Brand " + longCode("B"), category, now, now));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.KIND, longCode("K"), "Kind " + longCode("K"), category, now, now));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.TYPE, longCode("T"), "Type " + longCode("T"), category, now, now));

    assertThatThrownBy(() -> sparepartService.create(admin, new SparepartCommand(
        machine.getId(), category.getId(), brand.getId(), kind.getId(), type.getId())))
        .isInstanceOf(SparepartValidationException.class);
  }

  private static String longCode(String marker) {
    return marker + "X".repeat(59) + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
  }
}
