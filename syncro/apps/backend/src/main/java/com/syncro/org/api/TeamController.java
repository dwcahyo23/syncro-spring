package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.api.TeamDtos.AddMemberRequest;
import com.syncro.org.api.TeamDtos.CreateTeamRequest;
import com.syncro.org.api.TeamDtos.LinkMachineRequest;
import com.syncro.org.api.TeamDtos.TeamDetailResponse;
import com.syncro.org.api.TeamDtos.TeamListResponse;
import com.syncro.org.api.TeamDtos.TeamMachineView;
import com.syncro.org.api.TeamDtos.TeamMemberView;
import com.syncro.org.api.TeamDtos.TeamView;
import com.syncro.org.api.TeamDtos.UpdateTeamRequest;
import com.syncro.org.application.TeamService;
import com.syncro.org.application.TeamService.CreateTeamCommand;
import com.syncro.org.application.TeamService.UpdateTeamCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

  private final TeamService teams;

  public TeamController(TeamService teams) {
    this.teams = teams;
  }

  @Operation(operationId = "listTeams", summary = "List all cross-plant teams")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Teams returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @GetMapping
  public TeamListResponse list(@AuthenticationPrincipal AuthenticatedUser user) {
    return new TeamListResponse(teams.list(user).items().stream().map(this::toView).toList());
  }

  @Operation(operationId = "getTeam", summary = "Get a team with members and machines")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Team returned"),
      @ApiResponse(responseCode = "400", description = "Invalid team id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team not found")
  })
  @GetMapping("/{teamId}")
  public TeamDetailResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId) {
    return toDetail(teams.get(user, teamId));
  }

  @Operation(operationId = "createTeam", summary = "Create a cross-plant team")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Team created", content = @Content(schema = @Schema(implementation = TeamView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, duplicate name, or expiry in the past"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @PostMapping
  public ResponseEntity<TeamView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateTeamRequest request) {
    var created = toView(teams.create(user, new CreateTeamCommand(request.name(), request.expiresAt())));
    return ResponseEntity.created(URI.create("/api/v1/teams/" + created.id())).body(created);
  }

  @Operation(operationId = "updateTeam", summary = "Update a cross-plant team")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Team updated"),
      @ApiResponse(responseCode = "400", description = "Validation, duplicate name, or expiry in the past"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team not found")
  })
  @PutMapping("/{teamId}")
  public TeamView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId,
      @Valid @RequestBody UpdateTeamRequest request) {
    return toView(teams.update(user, teamId, new UpdateTeamCommand(request.name(), request.expiresAt())));
  }

  @Operation(operationId = "deleteTeam", summary = "Delete a cross-plant team (cascades members and machines)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Team deleted"),
      @ApiResponse(responseCode = "400", description = "Invalid team id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team not found")
  })
  @DeleteMapping("/{teamId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId) {
    teams.delete(user, teamId);
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "addTeamMember", summary = "Add a member to a team (idempotent)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Member added"),
      @ApiResponse(responseCode = "400", description = "Invalid team id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team or user not found")
  })
  @PostMapping("/{teamId}/members")
  public ResponseEntity<Void> addMember(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId,
      @Valid @RequestBody AddMemberRequest request) {
    teams.addMember(user, teamId, request.userId());
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "removeTeamMember", summary = "Remove a member from a team (idempotent)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Member removed"),
      @ApiResponse(responseCode = "400", description = "Invalid team or user id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team or user not found")
  })
  @DeleteMapping("/{teamId}/members/{userId}")
  public ResponseEntity<Void> removeMember(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId,
      @PathVariable UUID userId) {
    teams.removeMember(user, teamId, userId);
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "linkTeamMachine", summary = "Link a target machine to a team (idempotent)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Machine linked"),
      @ApiResponse(responseCode = "400", description = "Invalid team id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team or machine not found")
  })
  @PostMapping("/{teamId}/machines")
  public ResponseEntity<Void> linkMachine(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId,
      @Valid @RequestBody LinkMachineRequest request) {
    teams.linkMachine(user, teamId, request.machineId());
    return ResponseEntity.noContent().build();
  }

  @Operation(operationId = "unlinkTeamMachine", summary = "Unlink a target machine from a team (idempotent)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Machine unlinked"),
      @ApiResponse(responseCode = "400", description = "Invalid team or machine id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Team or machine not found")
  })
  @DeleteMapping("/{teamId}/machines/{machineId}")
  public ResponseEntity<Void> unlinkMachine(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID teamId,
      @PathVariable UUID machineId) {
    teams.unlinkMachine(user, teamId, machineId);
    return ResponseEntity.noContent().build();
  }

  private TeamView toView(TeamService.TeamView team) {
    return new TeamView(
        team.id(), team.name(), team.expiresAt(), team.active(), team.memberCount(), team.machineCount(),
        team.createdAt(), team.updatedAt());
  }

  private TeamDetailResponse toDetail(TeamService.TeamDetailView team) {
    return new TeamDetailResponse(
        team.id(), team.name(), team.expiresAt(), team.active(),
        team.members().stream().map(m -> new TeamMemberView(m.userId(), m.loginIdentifier())).toList(),
        team.machines().stream().map(this::toMachineView).toList(),
        team.createdAt(), team.updatedAt());
  }

  private TeamMachineView toMachineView(TeamService.TeamMachineView machine) {
    return new TeamMachineView(
        machine.machineId(), machine.code(), machine.name(), machine.plantId(), machine.plantCode(),
        machine.machineGroupId(), machine.machineGroupName());
  }
}
