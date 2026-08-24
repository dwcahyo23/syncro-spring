package com.syncro.setup.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.setup.api.SetupCompletenessDtos.Step;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class SetupCompletenessServiceIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired
  private SetupCompletenessService setupCompleteness;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private SparepartRepository spareparts;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

  @Autowired
  private MachineSparepartInstallationRepository installations;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("2.8-SVC-001 P1 full pilot chain reports every step COMPLETE and eligible machines")
  void fullPilotChainIsComplete() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var machine = machine(plant, group, "BF-08410", "JBF19");
    var sparepart = sparepart(plant, machine, "Electric PLC Wecon LX5");
    installation(machine, sparepart);
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    responsibility(machine, persistedUser(ApplicationRole.VIEWER, "responsibility-user@syncro.dev"));

    var response = setupCompleteness.get(admin);

    assertThat(response.scope().mode()).isEqualTo("UNRESTRICTED");
    assertThat(response.overallStatus()).isEqualTo("COMPLETE");
    assertThat(response.machineCount()).isEqualTo(1);
    assertThat(response.machinesEligibleCount()).isEqualTo(1);
    assertThat(response.steps()).extracting(step -> step.status()).containsOnly("COMPLETE");
  }

  @Test
  @DisplayName("2.8-SVC-002 P1 partial setup reports INCOMPLETE with next actions")
  void partialSetupIsIncompleteWithNextActions() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    machine(plant, group, "BF-08410", "JBF19");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var response = setupCompleteness.get(admin);

    assertThat(response.overallStatus()).isEqualTo("INCOMPLETE");
    assertThat(response.machineCount()).isEqualTo(1);
    assertThat(response.machinesEligibleCount()).isEqualTo(0);
    var steps = response.steps();
    assertThat(step(steps, "PLANT").status()).isEqualTo("COMPLETE");
    assertThat(step(steps, "MACHINE_GROUP").status()).isEqualTo("COMPLETE");
    assertThat(step(steps, "MACHINE").status()).isEqualTo("COMPLETE");
    assertThat(step(steps, "SPAREPART").status()).isEqualTo("INCOMPLETE");
    assertThat(step(steps, "SPAREPART").nextAction()).isEqualTo("Create a sparepart");
    assertThat(step(steps, "SPAREPART").href()).isEqualTo("/master-data/spareparts");
    assertThat(step(steps, "INSTALLATION").status()).isEqualTo("BLOCKED");
    assertThat(step(steps, "INSTALLATION").nextAction()).isEqualTo("Create a sparepart first");
    assertThat(step(steps, "RESPONSIBILITY").status()).isEqualTo("INCOMPLETE");
    assertThat(step(steps, "RESPONSIBILITY").nextAction()).isEqualTo("Assign a user to a machine");
    assertThat(step(steps, "RESPONSIBILITY").href()).isEqualTo("/master-data/responsibilities");
  }

  @Test
  @DisplayName("2.8-SVC-003 P1 blocked chain flows downstream when no machine group exists")
  void blockedChainFlowsDownstreamWhenNoGroups() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var response = setupCompleteness.get(admin);

    assertThat(response.overallStatus()).isEqualTo("INCOMPLETE");
    var steps = response.steps();
    assertThat(step(steps, "PLANT").status()).isEqualTo("COMPLETE");
    assertThat(step(steps, "MACHINE_GROUP").status()).isEqualTo("INCOMPLETE");
    assertThat(step(steps, "MACHINE_GROUP").nextAction()).isEqualTo("Create a machine group");
    assertThat(step(steps, "MACHINE").status()).isEqualTo("BLOCKED");
    assertThat(step(steps, "MACHINE").nextAction()).isEqualTo("Create a machine group first");
    assertThat(step(steps, "SPAREPART").status()).isEqualTo("BLOCKED");
    assertThat(step(steps, "INSTALLATION").status()).isEqualTo("BLOCKED");
    assertThat(step(steps, "RESPONSIBILITY").status()).isEqualTo("BLOCKED");
  }

  @Test
  @DisplayName("2.8-SVC-004 P1 EMPTY scope blocks the plant step and zeroes counts")
  void emptyScopeBlocksPlantStep() {
    var manage = persistedUser(ApplicationRole.MANAGE, "empty-scope-manage@syncro.dev");

    var response = setupCompleteness.get(manage);

    assertThat(response.scope().mode()).isEqualTo("EMPTY");
    assertThat(response.scope().emptyReason()).isEqualTo("NO_PLANTS_ASSIGNED");
    assertThat(response.scope().plantIds()).isEmpty();
    assertThat(response.machineCount()).isZero();
    assertThat(response.machinesEligibleCount()).isZero();
    assertThat(response.steps()).extracting(step -> step.status()).containsOnly("BLOCKED");
    assertThat(step(response.steps(), "PLANT").nextAction()).isEqualTo("Assign a plant to your scope");
  }

  @Test
  @DisplayName("2.8-SVC-005 P1 scoped user sees only assigned plant data")
  void scopedUserSeesOnlyAssignedPlantData() {
    var assignedPlant = plant("GM1", "Plant GM1");
    var otherPlant = plant("GM2", "Plant GM2");
    var assignedGroup = group(assignedPlant, "Forming");
    var otherGroup = group(otherPlant, "Packing");
    var assignedMachine = machine(assignedPlant, assignedGroup, "BF-08410", "JBF19");
    var otherMachine = machine(otherPlant, otherGroup, "PK-001", "Packer");
    machine(assignedPlant, assignedGroup, "AA-0002", "Second");

    var manage = persistedUser(ApplicationRole.MANAGE, "scoped-manage@syncro.dev");
    assign(manage, assignedPlant);

    var response = setupCompleteness.get(manage);

    assertThat(response.scope().mode()).isEqualTo("ASSIGNED");
    assertThat(response.scope().plantIds()).containsExactly(assignedPlant.getId());
    assertThat(response.machineCount()).isEqualTo(2);
    assertThat(response.machinesEligibleCount()).isZero();
    assertThat(otherMachine.getId()).isNotNull();
  }

  @Test
  @DisplayName("2.8-SVC-006 P1 eligibility requires both installation and responsibility per machine")
  void eligibilityRequiresInstallationAndResponsibility() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var machineA = machine(plant, group, "BF-08410", "JBF19");
    var machineB = machine(plant, group, "PK-001", "Packer");
    var sparepart = sparepart(plant, machineA, "Electric PLC Wecon LX5");
    installation(machineA, sparepart);
    responsibility(machineA, persistedUser(ApplicationRole.VIEWER, "eligible-responsibility@syncro.dev"));
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var response = setupCompleteness.get(admin);

    assertThat(response.machineCount()).isEqualTo(2);
    assertThat(response.machinesEligibleCount()).isEqualTo(1);
  }

  private Step step(java.util.List<Step> steps, String key) {
    return steps.stream().filter(step -> step.key().equals(key)).findFirst().orElseThrow();
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private MachineGroupEntity group(PlantEntity plant, String name) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, name, now, now));
  }

  private MachineEntity machine(PlantEntity plant, MachineGroupEntity group, String code, String name) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, code, name, MachineStatus.ACTIVE,
        "Juki", LocalDate.parse("2026-05-28"), null, List.of(), now, now));
  }

  private SparepartEntity sparepart(PlantEntity plant, MachineEntity machine, String name) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var suffix = "-" + plant.getCode() + "-" + UUID.randomUUID().toString().substring(0, 6);
    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "ELEC" + suffix, "Electric" + suffix, now, now));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.BRAND, "WECON" + suffix, "Wecon" + suffix, category, now, now));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.KIND, "PLC" + suffix, "PLC" + suffix, category, now, now));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX5" + suffix, "LX5" + suffix, category, now, now));
    return spareparts.saveAndFlush(new SparepartEntity(UUID.randomUUID(), "SP-" + plant.getCode() + "-LX5" + suffix, name, machine,
        category, brand, kind, type, now, now));
  }

  private void installation(MachineEntity machine, SparepartEntity sparepart) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    installations.saveAndFlush(new MachineSparepartInstallationEntity(UUID.randomUUID(), machine, sparepart,
        "Primary", 1_000_000L, 1_200L, 90, now, now, now));
  }

  private void responsibility(MachineEntity machine, AuthenticatedUser user) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(UUID.randomUUID(), machine.getId(),
        UUID.fromString(user.id()), ResponsibilityLevel.TECHNICIAN, now, now));
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
