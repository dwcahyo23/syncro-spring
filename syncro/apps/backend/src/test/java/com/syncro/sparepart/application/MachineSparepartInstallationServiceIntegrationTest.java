package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
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
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationCommand;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationDataIntegrityException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationFilters;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationMutationForbiddenException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationUpdateCommand;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationValidationException;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
class MachineSparepartInstallationServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private MachineSparepartInstallationService service;
  @Autowired private MachineSparepartInstallationRepository installations;
  @Autowired private MachineRepository machines;
  @Autowired private SparepartRepository spareparts;
  @Autowired private SparepartTaxonomyRepository taxonomy;
  @Autowired private MachineGroupRepository machineGroups;
  @Autowired private PlantRepository plants;
  @Autowired private AuthUserRepository users;
  @Autowired private AuthUserPlantAssignmentRepository assignments;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("2.6-SVC-001 P0 create stores default threshold and nullable telemetry evidence")
  void createStoresDefaultThresholdAndNullableTelemetryEvidence() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var user = persistedUser(ApplicationRole.MANAGE, "manage-install@syncro.dev");
    assign(user, plant);

    var created = service.create(user, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    assertThat(created.thresholdPercentage()).isEqualTo(90);
    assertThat(created.calculationBasis()).isEqualTo("COUNTER_BASED");
    assertThat(created.currentCount()).isNull();
    assertThat(created.consumedProductionCount()).isNull();
    assertThat(created.consumedPercentage()).isNull();
    assertThat(installations.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.6-SVC-002 P0 threshold override and update preserve machine and sparepart links")
  void updatePreservesLinks() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, 85));

    var updated = service.update(admin, created.id(), new InstallationUpdateCommand(2_000_000L, 1_300L, 95));

    assertThat(updated.id()).isEqualTo(created.id());
    assertThat(updated.machineId()).isEqualTo(machine.getId());
    assertThat(updated.sparepartId()).isEqualTo(sparepart.getId());
    assertThat(updated.expectedProductionCount()).isEqualTo(2_000_000L);
    assertThat(updated.baselineCounter()).isEqualTo(1_300L);
    assertThat(updated.thresholdPercentage()).isEqualTo(95);
  }

  @Test
  @DisplayName("2.6-SVC-003 P0 invalid numeric values are rejected")
  void invalidNumericValuesRejected() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 0L, 0L, 90)))
        .isInstanceOf(InstallationValidationException.class);
    assertThatThrownBy(() -> service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1L, -1L, 90)))
        .isInstanceOf(InstallationValidationException.class);
    assertThatThrownBy(() -> service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1L, 0L, 101)))
        .isInstanceOf(InstallationValidationException.class);
  }

  @Test
  @DisplayName("2.6-SVC-004 P0 VIEWER can read assigned plant but cannot mutate")
  void viewerCanReadAssignedPlantButCannotMutate() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-install@syncro.dev");
    assign(viewer, plant);
    var created = service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    assertThat(service.list(viewer, new InstallationFilters(null, null, null, null, 100))).extracting("id").containsExactly(created.id());
    assertThat(service.get(viewer, created.id()).id()).isEqualTo(created.id());
    assertThatThrownBy(() -> service.delete(viewer, created.id())).isInstanceOf(InstallationMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.6-SVC-005 P0 MANAGE cannot access out-of-scope machine installation")
  void manageCannotAccessOutOfScopeMachineInstallation() {
    var plant = plant("GM1");
    var other = plant("GM2");
    var machine = machine(other, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var manage = persistedUser(ApplicationRole.MANAGE, "manage-out-install@syncro.dev");
    assign(manage, plant);
    var created = service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    assertThatThrownBy(() -> service.get(manage, created.id())).isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> service.list(manage, new InstallationFilters(machine.getId(), null, null, null, 100)))
        .isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> service.create(manage, new InstallationCommand(machine.getId(), sparepart.getId(), 1L, 0L, null)))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.6-SVC-006 P0 delete removes installation without dependents")
  void deleteRemovesInstallationWithoutDependents() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    service.delete(admin, created.id());

    assertThat(installations.findById(created.id())).isEmpty();
  }

  @Test
  @DisplayName("2.6-SVC-007 P0 machine delete conflict uses real installation FK")
  void machineDeleteConflictUsesRealInstallationFk() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    assertThatThrownBy(() -> jdbc.update("DELETE FROM machines WHERE id = ?", machine.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.6-SVC-008 P0 sparepart delete conflict uses real installation FK")
  void sparepartDeleteConflictUsesRealInstallationFk() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    assertThatThrownBy(() -> jdbc.update("DELETE FROM spareparts WHERE id = ?", sparepart.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.6-SVC-009 P0 database constraints reject invalid persisted values")
  void databaseConstraintsRejectInvalidValues() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var now = Instant.parse("2026-05-28T00:00:00Z");

    assertThatThrownBy(() -> saveInvalidInstallation(machine, sparepart, 0, 0, 90, now))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> saveInvalidInstallation(machine, sparepart, 100, -1, 90, now))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> saveInvalidInstallation(machine, sparepart, 100, 0, 101, now))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.6-SVC-010 P0 duplicate machine sparepart baseline is rejected")
  void duplicateMachineSparepartBaselineRejected() {
    var plant = plant("GM1");
    var machine = machine(plant, "BF-08410");
    var sparepart = sparepart("PLC-WECON-LX5");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 1_000_000L, 1_200L, null));

    assertThatThrownBy(() -> service.create(admin, new InstallationCommand(machine.getId(), sparepart.getId(), 2_000_000L, 2_400L, 80)))
        .isInstanceOf(InstallationDataIntegrityException.class);
  }

  @Test
  @DisplayName("2.6-SVC-011 P1 list filters and orders installation evidence predictably")
  void listFiltersAndOrdersInstallationEvidencePredictably() {
    var plantA = plant("GM1");
    var plantB = plant("GM2");
    var machineB = machine(plantB, "BF-08410");
    var machineA = machine(plantA, "AA-0001");
    var sparepartB = sparepart("PLC-WECON-LX5");
    var sparepartA = sparepart("PLC-ABB-001");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var second = service.create(admin, new InstallationCommand(machineB.getId(), sparepartB.getId(), 1_000_000L, 1_200L, null));
    var first = service.create(admin, new InstallationCommand(machineA.getId(), sparepartA.getId(), 1_000_000L, 1_200L, null));

    assertThat(service.list(admin, new InstallationFilters(null, null, null, null, 100))).extracting("id").containsExactly(first.id(), second.id());
    assertThat(service.list(admin, new InstallationFilters(machineA.getId(), null, null, null, 100))).extracting("id").containsExactly(first.id());
    assertThat(service.list(admin, new InstallationFilters(null, sparepartB.getId(), null, null, 100))).extracting("id").containsExactly(second.id());
    assertThat(service.list(admin, new InstallationFilters(null, null, plantB.getId(), null, 100))).extracting("id").containsExactly(second.id());
    assertThat(service.list(admin, new InstallationFilters(null, null, null, machineA.getMachineGroup().getId(), 100))).extracting("id").containsExactly(first.id());
  }

  @Test
  @DisplayName("2.6-SVC-012 P1 invalid list limit is rejected")
  void invalidListLimitRejected() {
    assertThatThrownBy(() -> new InstallationFilters(null, null, null, null, 0))
        .isInstanceOf(InstallationValidationException.class);
    assertThatThrownBy(() -> new InstallationFilters(null, null, null, null, 201))
        .isInstanceOf(InstallationValidationException.class);
  }

  private void saveInvalidInstallation(MachineEntity machine, SparepartEntity sparepart, long expectedProductionCount,
      long baselineCounter, int thresholdPercentage, Instant now) {
    var transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.executeWithoutResult(status -> installations.saveAndFlush(new com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity(
        UUID.randomUUID(), machine, sparepart, expectedProductionCount, baselineCounter, thresholdPercentage, now, now, now)));
  }

  private PlantEntity plant(String code) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, "Plant " + code, now, now));
  }

  private MachineEntity machine(PlantEntity plant, String code) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));
    return machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, code, "JBF19", MachineStatus.ACTIVE,
        "Juki", LocalDate.parse("2026-05-28"), null, now, now));
  }

  private SparepartEntity sparepart(String code) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var suffix = code.replaceAll("[^A-Z0-9]", "");
    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "ELEC" + suffix, "Electric " + suffix, now, now));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.BRAND, "WECON" + suffix, "Wecon " + suffix, now, now));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.KIND, "PLC" + suffix, "PLC " + suffix, now, now));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX5" + suffix, "LX5 " + suffix, now, now));
    return spareparts.saveAndFlush(new SparepartEntity(UUID.randomUUID(), code, "Electric PLC Wecon LX5 " + suffix, category, brand, kind, type, now, now));
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
}
