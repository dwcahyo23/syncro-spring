package com.syncro.auth.application;

import com.syncro.auth.api.AuthDtos.PlantScopeResponse;
import com.syncro.auth.api.AuthDtos.PlantScopeView;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlantScopeService {
  private static final String ALL_PLANTS = "all";
  private static final String NO_PLANTS_ASSIGNED = "NO_PLANTS_ASSIGNED";

  private final PlantRepository plants;
  private final AuthUserPlantAssignmentRepository assignments;

  public PlantScopeService(PlantRepository plants, AuthUserPlantAssignmentRepository assignments) {
    this.plants = plants;
    this.assignments = assignments;
  }

  @Transactional(readOnly = true)
  public PlantScopeResponse effectiveScope(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return new PlantScopeResponse("UNRESTRICTED", plants.findAll().stream().map(this::toView).toList(), ALL_PLANTS, null);
    }

    var assignedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(assignment -> assignment.getPlantId())
        .toList();
    if (assignedPlantIds.isEmpty()) {
      return new PlantScopeResponse("EMPTY", List.of(), null, NO_PLANTS_ASSIGNED);
    }

    var availablePlants = plants.findAllById(assignedPlantIds).stream()
        .map(this::toView)
        .toList();
    if (availablePlants.isEmpty()) {
      return new PlantScopeResponse("EMPTY", List.of(), null, NO_PLANTS_ASSIGNED);
    }
    return new PlantScopeResponse("ASSIGNED", availablePlants, availablePlants.getFirst().id(), null);
  }

  @Transactional(readOnly = true)
  public boolean canAccessPlant(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return plants.existsById(plantId);
    }
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .anyMatch(assignment -> assignment.getPlantId().equals(plantId));
  }

  /**
   * Whether the given user id (not the acting principal) has access to a plant.
   * Used for cross-checking leader assignments (SPV/MG must be in the same plant).
   */
  @Transactional(readOnly = true)
  public boolean canAccessPlant(UUID userId, UUID plantId) {
    if (!plants.existsById(plantId)) {
      return false;
    }
    return assignments.findByAuthUserId(userId).stream()
        .anyMatch(assignment -> assignment.getPlantId().equals(plantId));
  }

  @Transactional(readOnly = true)
  public void requirePlantAccess(AuthenticatedUser user, UUID plantId) {
    if (!canAccessPlant(user, plantId)) {
      throw new PlantAccessDeniedException();
    }
  }

  private PlantScopeView toView(PlantEntity plant) {
    return new PlantScopeView(plant.getId().toString(), plant.getCode(), plant.getName());
  }

  public static class PlantAccessDeniedException extends RuntimeException {
  }
}
