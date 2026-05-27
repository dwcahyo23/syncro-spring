package com.syncro.machine.application;

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
import com.syncro.machine.application.MachineService.DuplicateMachineCodeException;
import com.syncro.machine.application.MachineService.MachineCommand;
import com.syncro.machine.application.MachineService.MachineDataIntegrityException;
import com.syncro.machine.application.MachineService.MachineGroupPlantMismatchException;
import com.syncro.machine.application.MachineService.MachineMutationForbiddenException;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
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
class MachineServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private MachineService machineService;

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

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("2.3-SVC-001 P1 MANAGE creates normalized active machine under assigned plant")
  void manageCreatesMachineUnderAssignedPlant() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var user = persistedUser(ApplicationRole.MANAGE, "manage-machine@syncro.dev");
    assign(user, plant);

    var created = machineService.create(user, command(plant.getId(), group.getId(), " bf-08410 ", MachineStatus.ACTIVE));

    assertThat(created.plantId()).isEqualTo(plant.getId());
    assertThat(created.machineGroupId()).isEqualTo(group.getId());
    assertThat(created.code()).isEqualTo("BF-08410");
    assertThat(created.status()).isEqualTo(MachineStatus.ACTIVE);
    assertThat(machines.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.3-SVC-002 P1 duplicate same-plant machine code is rejected case-insensitively")
  void duplicateSamePlantCodeIsRejectedCaseInsensitively() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "bf-08410", MachineStatus.INACTIVE)))
        .isInstanceOf(DuplicateMachineCodeException.class);
  }

  @Test
  @DisplayName("2.3-SVC-003 P1 same machine code is allowed across different plants")
  void sameCodeAllowedAcrossDifferentPlants() {
    var firstPlant = plant("GM1", "Plant GM1");
    var secondPlant = plant("GM2", "Plant GM2");
    var firstGroup = group(firstPlant, "Forming");
    var secondGroup = group(secondPlant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var first = machineService.create(admin, command(firstPlant.getId(), firstGroup.getId(), "BF-08410", MachineStatus.ACTIVE));
    var second = machineService.create(admin, command(secondPlant.getId(), secondGroup.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(machines.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("2.3-SVC-004 P0 database rejects case-insensitive duplicate machine codes")
  void databaseRejectsCaseInsensitiveDuplicateCodes() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var now = Instant.parse("2026-05-27T00:00:00Z");
    machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19", MachineStatus.ACTIVE,
        "Juki", LocalDate.parse("2026-05-27"), null, now, now));

    assertThatThrownBy(() -> machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "bf-08410", "JBF20",
        MachineStatus.INACTIVE, null, null, null, now, now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.3-SVC-005 P0 update cannot move machine to another plant")
  void updateCannotMoveMachineToAnotherPlant() {
    var sourcePlant = plant("GM1", "Plant GM1");
    var targetPlant = plant("GM2", "Plant GM2");
    var sourceGroup = group(sourcePlant, "Forming");
    var targetGroup = group(targetPlant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(sourcePlant.getId(), sourceGroup.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThatThrownBy(() -> machineService.update(admin, machine.id(), command(targetPlant.getId(), targetGroup.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(MachineDataIntegrityException.class);

    assertThat(machines.findById(machine.id())).get().extracting(entity -> entity.getPlant().getId()).isEqualTo(sourcePlant.getId());
  }

  @Test
  @DisplayName("2.3-SVC-006 P0 machine group must belong to machine plant")
  void machineGroupMustBelongToMachinePlant() {
    var plant = plant("GM1", "Plant GM1");
    var otherPlant = plant("GM2", "Plant GM2");
    var otherGroup = group(otherPlant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), otherGroup.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(MachineGroupPlantMismatchException.class);
  }

  @Test
  @DisplayName("2.3-SVC-007 P1 VIEWER lists assigned plant machines only")
  void viewerListsAssignedPlantMachinesOnly() {
    var assigned = plant("GM1", "Plant GM1");
    var other = plant("GM2", "Plant GM2");
    var assignedGroup = group(assigned, "Forming");
    var otherGroup = group(other, "Packing");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-machine@syncro.dev");
    assign(viewer, assigned);
    var assignedMachine = machineService.create(admin, command(assigned.getId(), assignedGroup.getId(), "BF-08410", MachineStatus.ACTIVE));
    machineService.create(admin, command(other.getId(), otherGroup.getId(), "PK-001", MachineStatus.ACTIVE));

    var result = machineService.list(viewer, null, null, null);

    assertThat(result).extracting("id").containsExactly(assignedMachine.id());
  }

  @Test
  @DisplayName("2.3-SVC-008 P0 MANAGE cannot list out-of-scope plant machines")
  void manageCannotListOutOfScopePlantMachines() {
    var target = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGE, "manage-out-of-scope-machine-list@syncro.dev");

    assertThatThrownBy(() -> machineService.list(user, target.getId(), null, null))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.3-SVC-009 P0 unassigned MANAGE cannot list with out-of-scope machine group")
  void unassignedManageCannotListWithOutOfScopeMachineGroup() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var user = persistedUser(ApplicationRole.MANAGE, "manage-unassigned-machine-group-list@syncro.dev");

    assertThatThrownBy(() -> machineService.list(user, null, group.getId(), null))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.3-SVC-010 P0 MANAGE cannot use out-of-scope machine group")
  void manageCannotUseOutOfScopeMachineGroup() {
    var assigned = plant("GM1", "Plant GM1");
    var other = plant("GM2", "Plant GM2");
    var otherGroup = group(other, "Packing");
    var user = persistedUser(ApplicationRole.MANAGE, "manage-out-of-scope-machine-group@syncro.dev");
    assign(user, assigned);

    assertThatThrownBy(() -> machineService.create(user, command(assigned.getId(), otherGroup.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> machineService.list(user, assigned.getId(), otherGroup.getId(), null))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.3-SVC-011 P0 VIEWER cannot mutate machines")
  void viewerCannotMutateMachines() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-mutates-machine@syncro.dev");
    assign(viewer, plant);

    assertThatThrownBy(() -> machineService.create(viewer, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(MachineMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.3-SVC-012 P1 delete removes machine without dependents")
  void deleteRemovesMachineWithoutDependents() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));

    machineService.delete(admin, machine.id());

    assertThat(machines.findById(machine.id())).isEmpty();
  }

  @Test
  @DisplayName("2.3-SVC-013 P1 delete dependency conflict returns data integrity exception")
  void deleteDependencyConflictReturnsDataIntegrityException() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));
    jdbc.execute("""
        CREATE TABLE machine_delete_dependencies (
          id UUID PRIMARY KEY,
          machine_id UUID NOT NULL REFERENCES machines(id) ON DELETE RESTRICT
        )
        """);
    jdbc.update("INSERT INTO machine_delete_dependencies (id, machine_id) VALUES (?, ?)", UUID.randomUUID(), machine.id());

    assertThatThrownBy(() -> machineService.delete(admin, machine.id()))
        .isInstanceOf(MachineDataIntegrityException.class);
  }

  private MachineCommand command(UUID plantId, UUID groupId, String code, MachineStatus status) {
    return new MachineCommand(plantId, groupId, code, "JBF19", status, "Juki", LocalDate.parse("2026-05-27"), "Pilot machine");
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private MachineGroupEntity group(PlantEntity plant, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, name, now, now));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-27T00:00:00Z")));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
