package com.syncro.masterdata.application;

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
import com.syncro.masterdata.application.MachineGroupService.CreateMachineGroupCommand;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupMutationForbiddenException;
import com.syncro.masterdata.application.MachineGroupService.SectionNotFoundForMachineGroupException;
import com.syncro.masterdata.application.MachineGroupService.SectionPlantMismatchException;
import com.syncro.masterdata.application.MachineGroupService.SectionReassignmentRejectedException;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
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

class MachineGroupSectionAssignmentIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private MachineGroupService machineGroupService;

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private SectionRepository sections;

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
  @DisplayName("9.1-ASSIGN-001 P1 assign first assigns an unassigned group and audits MACHINE_GROUP UPDATE")
  void assignFirstAssignsGroupAndAudits() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var section = section(plant, "MACHINERY", "Machinery");

    machineGroupService.assignSection(admin, group.id(), section.getId());

    assertThat(machineGroups.findById(group.id())).get()
        .extracting(MachineGroupEntity::getSectionId).isEqualTo(section.getId());
    entityManager.flush();
    assertThat(machineGroupAuditUpdateCount(group.id())).isEqualTo(1L);
  }

  @Test
  @DisplayName("9.1-ASSIGN-002 P1 assigning the same section is an idempotent no-op")
  void assignSameSectionIsIdempotent() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var section = section(plant, "MACHINERY", "Machinery");
    machineGroupService.assignSection(admin, group.id(), section.getId());

    machineGroupService.assignSection(admin, group.id(), section.getId());

    assertThat(machineGroups.findById(group.id())).get()
        .extracting(MachineGroupEntity::getSectionId).isEqualTo(section.getId());
  }

  @Test
  @DisplayName("9.1-ASSIGN-003 P0 reassigning a group to a different section is rejected")
  void reassignToDifferentSectionRejected() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var first = section(plant, "MACHINERY", "Machinery");
    var second = section(plant, "UTILITY", "Utility");
    machineGroupService.assignSection(admin, group.id(), first.getId());

    assertThatThrownBy(() -> machineGroupService.assignSection(admin, group.id(), second.getId()))
        .isInstanceOf(SectionReassignmentRejectedException.class);
    assertThat(machineGroups.findById(group.id())).get()
        .extracting(MachineGroupEntity::getSectionId).isEqualTo(first.getId());
  }

  @Test
  @DisplayName("9.1-ASSIGN-004 P0 assigning a section from another plant is rejected")
  void assignSectionFromOtherPlantRejected() {
    var plant = plant("GM1", "Plant GM1");
    var otherPlant = plant("GM2", "Plant GM2");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var otherSection = section(otherPlant, "MACHINERY", "Machinery");

    assertThatThrownBy(() -> machineGroupService.assignSection(admin, group.id(), otherSection.getId()))
        .isInstanceOf(SectionPlantMismatchException.class);
    assertThat(machineGroups.findById(group.id())).get()
        .extracting(MachineGroupEntity::getSectionId).isNull();
  }

  @Test
  @DisplayName("9.1-ASSIGN-005 P0 assigning to an unknown section is rejected")
  void assignToUnknownSectionRejected() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));

    assertThatThrownBy(() -> machineGroupService.assignSection(admin, group.id(), UUID.randomUUID()))
        .isInstanceOf(SectionNotFoundForMachineGroupException.class);
  }

  @Test
  @DisplayName("9.1-ASSIGN-006 P1 clear unassigns the group and audits MACHINE_GROUP UPDATE")
  void clearSectionUnassignsAndAudits() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var section = section(plant, "MACHINERY", "Machinery");
    machineGroupService.assignSection(admin, group.id(), section.getId());

    machineGroupService.clearSection(admin, group.id());

    assertThat(machineGroups.findById(group.id())).get()
        .extracting(MachineGroupEntity::getSectionId).isNull();
    entityManager.flush();
    assertThat(machineGroupAuditUpdateCount(group.id())).isEqualTo(2L);
  }

  @Test
  @DisplayName("9.1-ASSIGN-007 P0 AUDITOR cannot assign a section")
  void viewerCannotAssignSection() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-assign-section@syncro.dev");
    assign(viewer, plant);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));
    var section = section(plant, "MACHINERY", "Machinery");

    assertThatThrownBy(() -> machineGroupService.assignSection(viewer, group.id(), section.getId()))
        .isInstanceOf(MachineGroupMutationForbiddenException.class);
  }

  @Test
  @DisplayName("9.1-ASSIGN-008 P0 clear is gated by the mutation role")
  void viewerCannotClearSection() {
    var plant = plant("GM1", "Plant GM1");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-clear-section@syncro.dev");
    assign(viewer, plant);
    var group = machineGroupService.create(admin, new CreateMachineGroupCommand(plant.getId(), "Forming"));

    assertThatThrownBy(() -> machineGroupService.clearSection(viewer, group.id()))
        .isInstanceOf(MachineGroupMutationForbiddenException.class);
  }

  private Long machineGroupAuditUpdateCount(UUID groupId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'MACHINE_GROUP' AND action = 'UPDATE' AND entity_id = ?::uuid",
        Long.class, groupId.toString());
  }

  private SectionEntity section(PlantEntity plant, String code, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return sections.saveAndFlush(new SectionEntity(UUID.randomUUID(), plant, code, name, true, now, now));
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
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
