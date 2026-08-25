package com.syncro.setup.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.setup.api.SetupCompletenessDtos.ScopeInfo;
import com.syncro.setup.api.SetupCompletenessDtos.SetupCompletenessResponse;
import com.syncro.setup.api.SetupCompletenessDtos.Step;
import com.syncro.setup.application.SetupCompletenessService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(SetupCompletenessController.class)
@Import({SecurityConfig.class, SetupCompletenessExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class SetupCompletenessControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SetupCompletenessService setupCompleteness;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.8-API-001 P1 setup completeness returns the full checklist shape")
  void returnsChecklistShape() throws Exception {
    var plantId = UUID.randomUUID();
    var response = new SetupCompletenessResponse(
        new ScopeInfo("UNRESTRICTED", List.of(plantId), null),
        "INCOMPLETE",
        1,
        1,
        List.of(
            new Step("PLANT", "Plant", "COMPLETE", null, null),
            new Step("MACHINE_GROUP", "Machine Group", "COMPLETE", null, null),
            new Step("MACHINE", "Machine", "COMPLETE", null, null),
            new Step("SPAREPART", "Sparepart", "COMPLETE", null, null),
            new Step("INSTALLATION", "Installation", "INCOMPLETE", "Install a sparepart on a machine", "/master-data/installations"),
            new Step("RESPONSIBILITY", "Responsibility", "INCOMPLETE", "Assign a user to a machine", "/master-data/responsibilities")
        ));
    when(setupCompleteness.get(any())).thenReturn(response);

    mockMvc.perform(get("/api/v1/setup-completeness")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope.mode").value("UNRESTRICTED"))
        .andExpect(jsonPath("$.scope.plantIds[0]").value(plantId.toString()))
        .andExpect(jsonPath("$.overallStatus").value("INCOMPLETE"))
        .andExpect(jsonPath("$.machineCount").value(1))
        .andExpect(jsonPath("$.machinesEligibleCount").value(1))
        .andExpect(jsonPath("$.steps[4].key").value("INSTALLATION"))
        .andExpect(jsonPath("$.steps[4].status").value("INCOMPLETE"))
        .andExpect(jsonPath("$.steps[4].nextAction").value("Install a sparepart on a machine"))
        .andExpect(jsonPath("$.steps[4].href").value("/master-data/installations"));
  }

  @Test
  @DisplayName("2.8-API-002 P1 EMPTY scope reports blocked plant step")
  void emptyScopeReportsBlockedPlantStep() throws Exception {
    var response = new SetupCompletenessResponse(
        new ScopeInfo("EMPTY", List.of(), "NO_PLANTS_ASSIGNED"),
        "INCOMPLETE",
        0,
        0,
        List.of(
            new Step("PLANT", "Plant", "BLOCKED", "Assign a plant to your scope", "/master-data/plants"),
            new Step("MACHINE_GROUP", "Machine Group", "BLOCKED", "Create a plant first", "/master-data/plants"),
            new Step("MACHINE", "Machine", "BLOCKED", "Create a plant first", "/master-data/plants"),
            new Step("SPAREPART", "Sparepart", "BLOCKED", "Create a plant first", "/master-data/plants"),
            new Step("INSTALLATION", "Installation", "BLOCKED", "Create a plant first", "/master-data/plants"),
            new Step("RESPONSIBILITY", "Responsibility", "BLOCKED", "Create a plant first", "/master-data/plants")
        ));
    when(setupCompleteness.get(any())).thenReturn(response);

    mockMvc.perform(get("/api/v1/setup-completeness")
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope.mode").value("EMPTY"))
        .andExpect(jsonPath("$.scope.emptyReason").value("NO_PLANTS_ASSIGNED"))
        .andExpect(jsonPath("$.machineCount").value(0))
        .andExpect(jsonPath("$.steps[0].status").value("BLOCKED"))
        .andExpect(jsonPath("$.steps[0].nextAction").value("Assign a plant to your scope"));
  }

  @Test
  @DisplayName("2.8-API-003 P1 complete setup returns COMPLETE overall status")
  void completeSetupReturnsComplete() throws Exception {
    var response = new SetupCompletenessResponse(
        new ScopeInfo("ASSIGNED", List.of(UUID.randomUUID()), null),
        "COMPLETE",
        2,
        2,
        List.of(
            new Step("PLANT", "Plant", "COMPLETE", null, null),
            new Step("MACHINE_GROUP", "Machine Group", "COMPLETE", null, null),
            new Step("MACHINE", "Machine", "COMPLETE", null, null),
            new Step("SPAREPART", "Sparepart", "COMPLETE", null, null),
            new Step("INSTALLATION", "Installation", "COMPLETE", null, null),
            new Step("RESPONSIBILITY", "Responsibility", "COMPLETE", null, null)
        ));
    when(setupCompleteness.get(any())).thenReturn(response);

    mockMvc.perform(get("/api/v1/setup-completeness")
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overallStatus").value("COMPLETE"))
        .andExpect(jsonPath("$.steps[5].status").value("COMPLETE"));
  }

  @Test
  @DisplayName("2.8-API-004 P1 forbidden scope error returns safe 403 shape")
  void forbiddenScopeReturnsSafeForbiddenError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new PlantAccessDeniedException()).when(setupCompleteness).get(user);

    mockMvc.perform(get("/api/v1/setup-completeness")
        .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.8-API-005 P0 unauthenticated users cannot read setup completeness")
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/setup-completeness"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
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
