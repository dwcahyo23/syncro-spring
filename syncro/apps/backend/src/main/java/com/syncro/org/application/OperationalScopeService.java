package com.syncro.org.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-sourced scope derivation (AD-2): one service produces the identical
 * scope set consumed by the maintenance query layer (and, from 9.3 onward, OPA
 * input). plantIds come from the auth module's public {@link PlantScopeService};
 * machineGroupIds from the {@link SectionLeaderMachineGroupReader} port;
 * activeTeamIds stay empty until story 9.2 (cross-plant teams).
 *
 * Contract: for SUPER_ADMIN, plantIds is null — consumers keep their own
 * unscoped read path and never call derive for the plant dimension. machineGroupIds
 * is always the derived set (empty for users with no LEADER+ responsibility).
 */
@Service
public class OperationalScopeService {

  private final PlantScopeService plantScopes;
  private final SectionLeaderMachineGroupReader leaderReader;

  public OperationalScopeService(PlantScopeService plantScopes, SectionLeaderMachineGroupReader leaderReader) {
    this.plantScopes = plantScopes;
    this.leaderReader = leaderReader;
  }

  @Transactional(readOnly = true)
  public OperationalScope derive(AuthenticatedUser user) {
    var scope = plantScopes.effectiveScope(user);
    Set<UUID> plantIds = "UNRESTRICTED".equals(scope.mode())
        ? null
        : scope.availablePlants().stream()
            .map(plant -> UUID.fromString(plant.id()))
            .collect(Collectors.toUnmodifiableSet());
    var machineGroupIds = leaderReader.findMachineGroupIdsWhereUserIsLeader(UUID.fromString(user.id()));
    return new OperationalScope(plantIds, machineGroupIds, Set.of());
  }
}
