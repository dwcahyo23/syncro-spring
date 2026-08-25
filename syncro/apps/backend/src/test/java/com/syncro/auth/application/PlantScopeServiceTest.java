package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlantScopeServiceTest {

  @Mock
  private PlantRepository plants;

  @Mock
  private AuthUserPlantAssignmentRepository assignments;

  @InjectMocks
  private PlantScopeService plantScopes;

  @Test
  void superAdminIsUnrestrictedAndDefaultsToAllPlants() {
    var now = Instant.parse("2026-05-26T00:00:00Z");
    var plantId = UUID.randomUUID();
    when(plants.findAll()).thenReturn(List.of(new PlantEntity(plantId, "PLANT-1", "Plant One", now, now)));

    var scope = plantScopes.effectiveScope(new AuthenticatedUser(
        UUID.randomUUID().toString(),
        "admin@syncro.dev",
        ApplicationRole.SUPER_ADMIN));

    assertThat(scope.mode()).isEqualTo("UNRESTRICTED");
    assertThat(scope.defaultPlantId()).isEqualTo("all");
    assertThat(scope.availablePlants()).extracting("id").containsExactly(plantId.toString());
  }

  @Test
  void superAdminStaysUnrestrictedWhenNoPlantsExist() {
    when(plants.findAll()).thenReturn(List.of());

    var scope = plantScopes.effectiveScope(new AuthenticatedUser(
        UUID.randomUUID().toString(),
        "admin@syncro.dev",
        ApplicationRole.SUPER_ADMIN));

    assertThat(scope.mode()).isEqualTo("UNRESTRICTED");
    assertThat(scope.defaultPlantId()).isEqualTo("all");
    assertThat(scope.availablePlants()).isEmpty();
  }

  @Test
  void nonSuperAdminReceivesOnlyAssignedPlants() {
    var now = Instant.parse("2026-05-26T00:00:00Z");
    var userId = UUID.randomUUID();
    var assignedPlantId = UUID.randomUUID();
    var otherPlantId = UUID.randomUUID();
    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, assignedPlantId, now)));
    when(plants.findAllById(List.of(assignedPlantId)))
        .thenReturn(List.of(new PlantEntity(assignedPlantId, "PLANT-2", "Plant Two", now, now)));

    var scope = plantScopes.effectiveScope(new AuthenticatedUser(
        userId.toString(),
        "manage@syncro.dev",
        ApplicationRole.MANAGER_MAINTENANCE));

    assertThat(scope.mode()).isEqualTo("ASSIGNED");
    assertThat(scope.defaultPlantId()).isEqualTo(assignedPlantId.toString());
    assertThat(scope.availablePlants()).extracting("id").containsExactly(assignedPlantId.toString());
    assertThat(scope.availablePlants()).extracting("id").doesNotContain(otherPlantId.toString());
  }

  @Test
  void nonSuperAdminWithoutAssignmentsReceivesEmptyScope() {
    var userId = UUID.randomUUID();
    when(assignments.findByAuthUserId(userId)).thenReturn(List.of());

    var scope = plantScopes.effectiveScope(new AuthenticatedUser(
        userId.toString(),
        "viewer@syncro.dev",
        ApplicationRole.AUDITOR));

    assertThat(scope.mode()).isEqualTo("EMPTY");
    assertThat(scope.availablePlants()).isEmpty();
    assertThat(scope.defaultPlantId()).isNull();
    assertThat(scope.emptyReason()).isEqualTo("NO_PLANTS_ASSIGNED");
  }

  @Test
  void nonSuperAdminWithStaleAssignmentsReceivesEmptyScope() {
    var userId = UUID.randomUUID();
    var stalePlantId = UUID.randomUUID();
    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, stalePlantId, Instant.parse("2026-05-26T00:00:00Z"))));
    when(plants.findAllById(List.of(stalePlantId))).thenReturn(List.of());

    var scope = plantScopes.effectiveScope(new AuthenticatedUser(
        userId.toString(),
        "viewer@syncro.dev",
        ApplicationRole.AUDITOR));

    assertThat(scope.mode()).isEqualTo("EMPTY");
    assertThat(scope.availablePlants()).isEmpty();
    assertThat(scope.defaultPlantId()).isNull();
    assertThat(scope.emptyReason()).isEqualTo("NO_PLANTS_ASSIGNED");
  }

  @Test
  void requirePlantAccessRejectsOutOfScopePlantForAssignedUser() {
    var userId = UUID.randomUUID();
    var assignedPlantId = UUID.randomUUID();
    var requestedPlantId = UUID.randomUUID();
    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, assignedPlantId, Instant.parse("2026-05-26T00:00:00Z"))));

    assertThatThrownBy(() -> plantScopes.requirePlantAccess(new AuthenticatedUser(
        userId.toString(),
        "viewer@syncro.dev",
        ApplicationRole.AUDITOR), requestedPlantId))
        .isInstanceOf(PlantScopeService.PlantAccessDeniedException.class);
  }

  @Test
  void requirePlantAccessAllowsSuperAdminForExistingPlant() {
    var plantId = UUID.randomUUID();
    when(plants.existsById(plantId)).thenReturn(true);

    plantScopes.requirePlantAccess(new AuthenticatedUser(
        UUID.randomUUID().toString(),
        "admin@syncro.dev",
        ApplicationRole.SUPER_ADMIN), plantId);
  }

  @Test
  void requirePlantAccessRejectsMissingPlantForSuperAdmin() {
    var plantId = UUID.randomUUID();
    when(plants.existsById(plantId)).thenReturn(false);

    assertThatThrownBy(() -> plantScopes.requirePlantAccess(new AuthenticatedUser(
        UUID.randomUUID().toString(),
        "admin@syncro.dev",
        ApplicationRole.SUPER_ADMIN), plantId))
        .isInstanceOf(PlantScopeService.PlantAccessDeniedException.class);
  }

  @Test
  void canAccessPlantReturnsTrueOnlyForEffectiveScope() {
    var userId = UUID.randomUUID();
    var assignedPlantId = UUID.randomUUID();
    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, assignedPlantId, Instant.parse("2026-05-26T00:00:00Z"))));

    assertThat(plantScopes.canAccessPlant(new AuthenticatedUser(
        userId.toString(),
        "manage@syncro.dev",
        ApplicationRole.MANAGER_MAINTENANCE), assignedPlantId)).isTrue();
    assertThat(plantScopes.canAccessPlant(new AuthenticatedUser(
        userId.toString(),
        "manage@syncro.dev",
        ApplicationRole.MANAGER_MAINTENANCE), UUID.randomUUID())).isFalse();
  }
}
