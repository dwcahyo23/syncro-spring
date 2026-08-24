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
import com.syncro.masterdata.application.PlantService.CreatePlantCommand;
import com.syncro.masterdata.application.PlantService.DuplicatePlantCodeException;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class PlantServiceIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired
  private PlantService plantService;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("2.1-SVC-001 P1 MANAGE creates normalized plant and receives assignment")
  void manageCreatesNormalizedPlant() {
    var user = persistedUser(ApplicationRole.MANAGE, "manage-create@syncro.dev");

    var created = plantService.create(user, new CreatePlantCommand(" gm1 ", " Plant GM1 "));

    assertThat(created.code()).isEqualTo("GM1");
    assertThat(created.name()).isEqualTo("Plant GM1");
    assertThat(plants.findById(created.id())).isPresent();
    assertThat(assignments.findByAuthUserId(UUID.fromString(user.id())))
        .extracting("plantId")
        .contains(created.id());
  }

  @Test
  @DisplayName("2.1-SVC-002 P1 duplicate plant code is rejected case-insensitively")
  void duplicatePlantCodeIsRejectedCaseInsensitively() {
    var user = persistedUser(ApplicationRole.MANAGE, "manage-duplicate@syncro.dev");
    plantService.create(user, new CreatePlantCommand("GM1", "Plant GM1"));

    assertThatThrownBy(() -> plantService.create(user, new CreatePlantCommand("gm1", "Other")))
        .isInstanceOf(DuplicatePlantCodeException.class);
  }

  @Test
  @DisplayName("2.1-SVC-003 P1 VIEWER lists assigned plants only")
  void viewerCanListAssignedPlantsOnly() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var viewerId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        viewerId,
        "viewer-plant@syncro.dev",
        passwordEncoder.encode("syncro-viewer-dev"),
        ApplicationRole.VIEWER,
        true,
        now,
        now));
    var assigned = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var other = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM2", "Plant GM2", now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(viewerId, assigned.getId(), now));

    var result = plantService.list(new AuthenticatedUser(viewerId.toString(), "viewer-plant@syncro.dev", ApplicationRole.VIEWER));

    assertThat(result).extracting("id").containsExactly(assigned.getId());
    assertThat(result).extracting("id").doesNotContain(other.getId());
  }

  @Test
  @DisplayName("2.1-SVC-004 P0 MANAGE cannot update out-of-scope plant")
  void manageCannotUpdateOutOfScopePlant() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var manageId = UUID.randomUUID();
    users.saveAndFlush(new AuthUserEntity(
        manageId,
        "manage-plant@syncro.dev",
        passwordEncoder.encode("syncro-manage-dev"),
        ApplicationRole.MANAGE,
        true,
        now,
        now));
    var target = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));

    assertThatThrownBy(() -> plantService.update(
        new AuthenticatedUser(manageId.toString(), "manage-plant@syncro.dev", ApplicationRole.MANAGE),
        target.getId(),
        new CreatePlantCommand("GM1", "Updated")))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.1-SVC-005 P1 deleting plant cascades existing assignments")
  void deleteCascadesExistingPlantAssignments() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        "assigned-user@syncro.dev",
        passwordEncoder.encode("syncro-viewer-dev"),
        ApplicationRole.VIEWER,
        true,
        now,
        now));
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(user.getId(), plant.getId(), now));

    plantService.delete(admin, plant.getId());

    assertThat(plants.findById(plant.getId())).isEmpty();
    assertThat(assignments.findByAuthUserId(user.getId())).isEmpty();
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

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
