package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
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
import com.syncro.masterdata.application.MachineGroupService;
import com.syncro.masterdata.application.MachineGroupService.CreateMachineGroupCommand;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.application.SectionService.CreateSectionCommand;
import com.syncro.org.application.SectionService.DuplicateSectionCodeException;
import com.syncro.org.application.SectionService.DuplicateSectionNameException;
import com.syncro.org.application.SectionService.SectionHasActiveMachineGroupsException;
import com.syncro.org.application.SectionService.SectionMutationForbiddenException;
import com.syncro.org.application.SectionService.UpdateSectionCommand;
import com.syncro.org.infrastructure.SectionEntity;
import com.syncro.org.infrastructure.SectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class SectionServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private SectionService sectionService;

  @Autowired
  private SectionRepository sections;

  @Autowired
  private MachineGroupService machineGroupService;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

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
  @DisplayName("9.1-SVC-001 P0 AUDITOR cannot create sections")
  void viewerCannotCreateSection() {
    var plant = plant("GM1", "Plant GM1");
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-section@syncro.dev");
    assign(viewer, plant);

    assertThatThrownBy(() -> sectionService.create(viewer,
        new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery")))
        .isInstanceOf(SectionMutationForbiddenException.class);
  }

  @Test
  @DisplayName("9.1-SVC-002 P0 MANAGER_MAINTENANCE without plant access cannot create sections")
  void manageWithoutPlantAccessCannotCreateSection() {
    var plant = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-section-out-of-scope@syncro.dev");

    assertThatThrownBy(() -> sectionService.create(user,
        new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery")))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("9.1-SVC-003 P1 MANAGER_MAINTENANCE creates a section under its assigned plant")
  void manageCreatesSectionUnderAssignedPlant() {
    var plant = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-section@syncro.dev");
    assign(user, plant);

    var created = sectionService.create(user,
        new CreateSectionCommand(plant.getId(), "MACHINERY", " Machinery "));

    assertThat(created.plantId()).isEqualTo(plant.getId());
    assertThat(created.plantCode()).isEqualTo("GM1");
    assertThat(created.code()).isEqualTo("MACHINERY");
    assertThat(created.name()).isEqualTo("Machinery");
    assertThat(created.active()).isTrue();
    assertThat(sections.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("9.1-SVC-004 P1 duplicate code in the same plant is rejected")
  void duplicateCodeSamePlantRejected() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    sectionService.create(admin, new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));

    assertThatThrownBy(() -> sectionService.create(admin,
        new CreateSectionCommand(plant.getId(), "MACHINERY", "Machine Line")))
        .isInstanceOf(DuplicateSectionCodeException.class);
  }

  @Test
  @DisplayName("9.1-SVC-005 P1 same code is allowed in a different plant")
  void sameCodeAllowedAcrossPlants() {
    var firstPlant = plant("GM1", "Plant GM1");
    var secondPlant = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    sectionService.create(admin, new CreateSectionCommand(firstPlant.getId(), "MACHINERY", "Machinery"));
    var second = sectionService.create(admin, new CreateSectionCommand(secondPlant.getId(), "MACHINERY", "Machinery"));

    assertThat(sections.findAll()).hasSize(2);
    assertThat(second.plantId()).isEqualTo(secondPlant.getId());
  }

  @Test
  @DisplayName("9.1-SVC-006 P1 duplicate name in the same plant is rejected")
  void duplicateNameSamePlantRejected() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    sectionService.create(admin, new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));

    assertThatThrownBy(() -> sectionService.create(admin,
        new CreateSectionCommand(plant.getId(), "UTILITY", "machinery")))
        .isInstanceOf(DuplicateSectionNameException.class);
  }

  @Test
  @DisplayName("9.1-SVC-007 P1 update changes the section name and audits UPDATE")
  void updateSectionNameAndAudits() {
    var plant = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-section-update@syncro.dev");
    assign(user, plant);
    var created = sectionService.create(user,
        new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));

    var updated = sectionService.update(user, created.id(), new UpdateSectionCommand("Machine Section", true));

    assertThat(updated.name()).isEqualTo("Machine Section");
    assertThat(updated.active()).isTrue();
    entityManager.flush();
    assertThat(auditCount("SECTION", "UPDATE", user.id(), plant.getId())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.1-SVC-008 P0 deactivation with an active-machine group is rejected")
  void deactivateWithActiveMachineGroupRejected() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var section = sectionService.create(admin, new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    machineGroupService.assignSection(admin, group.id(), section.id());
    machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plantEntity(plant.getId()), machineGroupEntity(group.id()),
        "BF-08410", "JBF19", MachineStatus.ACTIVE, "Juki", null, null, java.util.List.of(),
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z")));

    assertThatThrownBy(() -> sectionService.update(admin, section.id(), new UpdateSectionCommand("Machinery", false)))
        .isInstanceOf(SectionHasActiveMachineGroupsException.class);
    assertThat(sections.findById(section.id())).get().extracting(SectionEntity::isActive).isEqualTo(true);
  }

  @Test
  @DisplayName("9.1-SVC-009 P1 deactivation of an empty section succeeds and audits UPDATE")
  void deactivateEmptySectionSucceeds() {
    var plant = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-section-deactivate@syncro.dev");
    assign(user, plant);
    var created = sectionService.create(user,
        new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));

    var updated = sectionService.update(user, created.id(), new UpdateSectionCommand("Machinery", false));

    assertThat(updated.active()).isFalse();
    entityManager.flush();
    assertThat(auditCount("SECTION", "UPDATE", user.id(), plant.getId())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.1-SVC-010 P1 reactivation of a deactivated section is allowed")
  void reactivateSectionAllowed() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = sectionService.create(admin, new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));
    sectionService.update(admin, created.id(), new UpdateSectionCommand("Machinery", false));

    var reactivated = sectionService.update(admin, created.id(), new UpdateSectionCommand("Machinery", true));

    assertThat(reactivated.active()).isTrue();
  }

  @Test
  @DisplayName("9.1-SVC-011 P1 create audits a SECTION CREATE row with actor and plantId")
  void createAuditsSectionCreateWithActorAndPlant() {
    var plant = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-section-audit@syncro.dev");
    assign(user, plant);

    var created = sectionService.create(user,
        new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));

    entityManager.flush();
    assertThat(auditCount("SECTION", "CREATE", user.id(), plant.getId())).isEqualTo(1L);
    assertThat(sections.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("9.1-SVC-012 P1 list returns active-only by default and includes inactive on request")
  void listFiltersInactiveSections() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var active = sectionService.create(admin, new CreateSectionCommand(plant.getId(), "MACHINERY", "Machinery"));
    var inactive = sectionService.create(admin, new CreateSectionCommand(plant.getId(), "WORKSHOP", "Workshop"));
    sectionService.update(admin, inactive.id(), new UpdateSectionCommand("Workshop", false));

    var activeOnly = sectionService.list(admin, plant.getId(), false);
    assertThat(activeOnly.items()).extracting(s -> s.id()).containsExactly(active.id());

    var includingInactive = sectionService.list(admin, plant.getId(), true);
    assertThat(includingInactive.items()).extracting(s -> s.id())
        .containsExactlyInAnyOrder(active.id(), inactive.id());
  }

  private Long auditCount(String entityType, String action, String actorId, UUID plantId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = ? AND action = ? AND actor_id = ?::uuid AND plant_id = ?::uuid",
        Long.class, entityType, action, actorId, plantId.toString());
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private PlantEntity plantEntity(UUID id) {
    return plants.findById(id).orElseThrow();
  }

  private MachineGroupEntity machineGroupEntity(UUID id) {
    return machineGroups.findById(id).orElseThrow();
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        role,
        true,
        now,
        now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-27T00:00:00Z")));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
