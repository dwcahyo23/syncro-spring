package com.syncro.org.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.org.infrastructure.TeamRepository;
import java.time.Clock;
import java.time.Instant;
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
 * activeTeamIds from the {@link TeamMachineGroupReader} port via active-team
 * machine-group resolution (AD-13).
 *
 * Contract: for SUPER_ADMIN, plantIds is null — consumers keep their own
 * unscoped read path and never call derive for the plant dimension. machineGroupIds
 * is always the derived set (empty for users with no LEADER+ responsibility).
 * activeTeamIds is empty for users with no active team membership.
 */
@Service
public class OperationalScopeService {

  private final PlantScopeService plantScopes;
  private final SectionLeaderMachineGroupReader leaderReader;
  private final TeamRepository teams;
  private final TeamMachineGroupReader teamMachineGroupReader;
  private final Clock clock;

  public OperationalScopeService(PlantScopeService plantScopes, SectionLeaderMachineGroupReader leaderReader,
      TeamRepository teams, TeamMachineGroupReader teamMachineGroupReader, Clock clock) {
    this.plantScopes = plantScopes;
    this.leaderReader = leaderReader;
    this.teams = teams;
    this.teamMachineGroupReader = teamMachineGroupReader;
    this.clock = clock;
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
    var activeTeamMachineIds = teams.findActiveTeamMachineIdsByUserId(
        UUID.fromString(user.id()), Instant.now(clock));
    var activeTeamIds = teamMachineGroupReader.resolveMachineGroupIds(activeTeamMachineIds);
    return new OperationalScope(plantIds, machineGroupIds, activeTeamIds);
  }
}
