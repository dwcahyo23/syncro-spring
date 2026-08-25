package com.syncro.org.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.org.application.TeamService;
import com.syncro.org.application.TeamService.CreateTeamCommand;
import com.syncro.org.application.TeamService.DuplicateTeamNameException;
import com.syncro.org.application.TeamService.MachineNotFoundException;
import com.syncro.org.application.TeamService.TeamDataIntegrityException;
import com.syncro.org.application.TeamService.TeamDetailView;
import com.syncro.org.application.TeamService.TeamExpiryInPastException;
import com.syncro.org.application.TeamService.TeamListView;
import com.syncro.org.application.TeamService.TeamMachineView;
import com.syncro.org.application.TeamService.TeamMemberView;
import com.syncro.org.application.TeamService.TeamMutationForbiddenException;
import com.syncro.org.application.TeamService.TeamNotFoundException;
import com.syncro.org.application.TeamService.TeamView;
import com.syncro.org.application.TeamService.UpdateTeamCommand;
import com.syncro.org.application.TeamService.UserNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TeamController.class)
@Import({SecurityConfig.class, TeamExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class TeamControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TeamService teams;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

  @Test
  @DisplayName("9.2-API-001 P1 create team returns 201")
  void createTeamReturnsCreated() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();
    when(teams.create(eq(user), any(CreateTeamCommand.class)))
        .thenReturn(new TeamView(teamId, "Cross Repair", NOW.plusSeconds(86400), true, 0, 0, NOW, NOW));

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Cross Repair\",\"expiresAt\":\"2026-09-30T00:00:00Z\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(teamId.toString()))
        .andExpect(jsonPath("$.name").value("Cross Repair"))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  @DisplayName("9.2-API-002 P1 list teams returns 200")
  void listTeamsReturnsOk() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(teams.list(user)).thenReturn(new TeamListView(List.of(
        new TeamView(UUID.randomUUID(), "Cross Repair", NOW.plusSeconds(86400), true, 2, 1, NOW, NOW))));

    mockMvc.perform(get("/api/v1/teams").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("Cross Repair"))
        .andExpect(jsonPath("$.items[0].memberCount").value(2));
  }

  @Test
  @DisplayName("9.2-API-003 P1 get team detail returns 200 with members and machines")
  void getTeamReturnsDetail() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var teamId = UUID.randomUUID();
    var memberId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    when(teams.get(user, teamId)).thenReturn(new TeamDetailView(teamId, "Cross Repair", NOW.plusSeconds(86400), true,
        List.of(new TeamMemberView(memberId, "technician@syncro.dev")),
        List.of(new TeamMachineView(machineId, "BF-08410", "JBF19", UUID.randomUUID(), "SM2",
            UUID.randomUUID(), "Packaging")),
        NOW, NOW));

    mockMvc.perform(get("/api/v1/teams/{teamId}", teamId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.members[0].loginIdentifier").value("technician@syncro.dev"))
        .andExpect(jsonPath("$.machines[0].code").value("BF-08410"));
  }

  @Test
  @DisplayName("9.2-API-004 P1 update team returns 200")
  void updateTeamReturnsOk() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();
    when(teams.update(eq(user), eq(teamId), any(UpdateTeamCommand.class)))
        .thenReturn(new TeamView(teamId, "Extended Repair", NOW.plusSeconds(172800), true, 0, 0, NOW, NOW));

    mockMvc.perform(put("/api/v1/teams/{teamId}", teamId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Extended Repair\",\"expiresAt\":\"2026-10-01T00:00:00Z\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Extended Repair"));
  }

  @Test
  @DisplayName("9.2-API-005 P1 delete team returns 204")
  void deleteTeamReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();

    mockMvc.perform(delete("/api/v1/teams/{teamId}", teamId).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("9.2-API-006 P1 add member returns 204")
  void addMemberReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/teams/{teamId}/members", teamId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("9.2-API-007 P1 remove member returns 204")
  void removeMemberReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();

    mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{userId}", teamId, UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("9.2-API-008 P1 link machine returns 204")
  void linkMachineReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/teams/{teamId}/machines", teamId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"machineId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("9.2-API-009 P1 unlink machine returns 204")
  void unlinkMachineReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();

    mockMvc.perform(delete("/api/v1/teams/{teamId}/machines/{machineId}", teamId, UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("9.2-API-010 P0 AUDITOR create is forbidden with FORBIDDEN")
  void viewerCreateForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new TeamMutationForbiddenException()).when(teams).create(eq(user), any());

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Cross Repair\",\"expiresAt\":\"2026-09-30T00:00:00Z\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("9.2-API-010b P0 AUDITOR list is forbidden (reads gated, review decision)")
  void viewerReadsForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new TeamMutationForbiddenException()).when(teams).list(user);

    mockMvc.perform(get("/api/v1/teams").with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("9.2-API-011 P1 unknown team maps to 404 TEAM_NOT_FOUND")
  void unknownTeamNotFound() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var teamId = UUID.randomUUID();
    doThrow(new TeamNotFoundException()).when(teams).get(user, teamId);

    mockMvc.perform(get("/api/v1/teams/{teamId}", teamId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
  }

  @Test
  @DisplayName("9.2-API-012 P1 duplicate name maps to 400 DUPLICATE_TEAM_NAME")
  void duplicateNameMapsToBadRequest() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new DuplicateTeamNameException()).when(teams).create(eq(user), any());

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Cross Repair\",\"expiresAt\":\"2026-09-30T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_TEAM_NAME"));
  }

  @Test
  @DisplayName("9.2-API-013 P1 past expiry maps to 400 TEAM_EXPIRY_IN_PAST")
  void pastExpiryMapsToBadRequest() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new TeamExpiryInPastException()).when(teams).create(eq(user), any());

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Cross Repair\",\"expiresAt\":\"2026-01-01T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEAM_EXPIRY_IN_PAST"));
  }

  @Test
  @DisplayName("9.2-API-014 P1 unknown user maps to 404 USER_NOT_FOUND")
  void unknownUserMapsToNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();
    doThrow(new UserNotFoundException()).when(teams).addMember(eq(user), eq(teamId), any(UUID.class));

    mockMvc.perform(post("/api/v1/teams/{teamId}/members", teamId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  @DisplayName("9.2-API-015 P1 unknown machine maps to 404 MACHINE_NOT_FOUND")
  void unknownMachineMapsToNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var teamId = UUID.randomUUID();
    doThrow(new MachineNotFoundException()).when(teams).linkMachine(eq(user), eq(teamId), any(UUID.class));

    mockMvc.perform(post("/api/v1/teams/{teamId}/machines", teamId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"machineId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("9.2-API-016 P1 data integrity maps to 400 TEAM_DATA_INTEGRITY")
  void dataIntegrityMapsToBadRequest() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new TeamDataIntegrityException()).when(teams).create(eq(user), any());

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Cross Repair\",\"expiresAt\":\"2026-09-30T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEAM_DATA_INTEGRITY"));
  }

  @Test
  @DisplayName("9.2-API-017 P1 invalid request returns VALIDATION_ERROR")
  void invalidRequestReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"\",\"expiresAt\":\"2026-09-30T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  @Test
  @DisplayName("9.2-API-018 P1 malformed JSON returns MALFORMED_JSON")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(post("/api/v1/teams")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
  }

  @Test
  @DisplayName("9.2-API-019 unauthenticated team requests are rejected")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/teams"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("9.2-API-020 invalid path value maps to INVALID_PATH_VALUE")
  void invalidPathValueMapsToBadRequest() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/teams/not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}
