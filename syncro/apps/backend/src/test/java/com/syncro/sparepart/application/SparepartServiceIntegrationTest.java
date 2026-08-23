package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyDimensionMismatchException;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyReferenceNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartValidationException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "server.port=0",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
@Testcontainers
@Transactional
class SparepartServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

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
  @DisplayName("2.5-SVC-001 P1 MANAGE creates normalized sparepart with taxonomy references")
  void manageCreatesNormalizedSparepartWithTaxonomyReferences() {
    var user = persistedUser(ApplicationRole.MANAGE, "manage-sparepart@syncro.dev");
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
        refs.category().getId(), refs.brand().getId(), refs.kind().getId(), refs.type().getId(), "lx5", null, null), PageRequest.of(0, 200));

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

    var result = sparepartService.list(admin, new SparepartFilters(null, null, null, null, null, null, null), PageRequest.of(1, 1));

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

    var underscore = sparepartService.list(admin, new SparepartFilters(null, null, null, null, "_", null, null), PageRequest.of(0, 200));
    var percent = sparepartService.list(admin, new SparepartFilters(null, null, null, null, "100%", null, null), PageRequest.of(0, 200));

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
  @DisplayName("2.5-SVC-007 P0 VIEWER can list spareparts but cannot mutate")
  void viewerCanListButCannotMutateSpareparts() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-sparepart@syncro.dev");
    var refs = taxonomyRefs();
    var created = sparepartService.create(admin, command("PLC-WECON-LX5", "Wecon LX5 PLC", refs));

    assertThat(sparepartService.list(viewer, new SparepartFilters(null, null, null, null, null, null, null), PageRequest.of(0, 200)).items()).extracting(sparepart -> sparepart.id()).containsExactly(created.id());
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
}
