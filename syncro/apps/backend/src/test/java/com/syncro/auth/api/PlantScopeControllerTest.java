package com.syncro.auth.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.api.AuthDtos.PlantScopeResponse;
import com.syncro.auth.api.AuthDtos.PlantScopeView;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class,
    PlantScopeControllerTest.PlantScopeTestController.class})
class PlantScopeControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuthService authService;

  @MockitoBean
  private PlantScopeService plantScopes;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void plantScopeRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/auth/plant-scope"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void superAdminReceivesUnrestrictedPlantScope() throws Exception {
    var user = new AuthenticatedUser("user-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    when(plantScopes.effectiveScope(user)).thenReturn(new PlantScopeResponse(
        "UNRESTRICTED",
        List.of(new PlantScopeView("plant-1", "PLANT-1", "Plant One")),
        "all",
        null));

    mockMvc.perform(get("/api/v1/auth/plant-scope")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(user))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("UNRESTRICTED"))
        .andExpect(jsonPath("$.availablePlants[0].code").value("PLANT-1"))
        .andExpect(jsonPath("$.defaultPlantId").value("all"))
        .andExpect(jsonPath("$.emptyReason").doesNotExist());
  }

  @Test
  void assignedUserReceivesAssignedPlantScope() throws Exception {
    var user = new AuthenticatedUser("user-2", "manage@syncro.dev", ApplicationRole.MANAGER_MAINTENANCE);
    when(plantScopes.effectiveScope(user)).thenReturn(new PlantScopeResponse(
        "ASSIGNED",
        List.of(new PlantScopeView("plant-2", "PLANT-2", "Plant Two")),
        "plant-2",
        null));

    mockMvc.perform(get("/api/v1/auth/plant-scope")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(user))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("ASSIGNED"))
        .andExpect(jsonPath("$.availablePlants[0].id").value("plant-2"))
        .andExpect(jsonPath("$.defaultPlantId").value("plant-2"));
  }

  @Test
  void unassignedUserReceivesEmptyScopeReason() throws Exception {
    var user = new AuthenticatedUser("user-3", "viewer@syncro.dev", ApplicationRole.AUDITOR);
    when(plantScopes.effectiveScope(user)).thenReturn(new PlantScopeResponse(
        "EMPTY",
        List.of(),
        null,
        "NO_PLANTS_ASSIGNED"));

    mockMvc.perform(get("/api/v1/auth/plant-scope")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(user))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("EMPTY"))
        .andExpect(jsonPath("$.availablePlants").isEmpty())
        .andExpect(jsonPath("$.defaultPlantId").doesNotExist())
        .andExpect(jsonPath("$.emptyReason").value("NO_PLANTS_ASSIGNED"));
  }

  @Test
  void assignedViewerReceivesAssignedPlantScope() throws Exception {
    var user = new AuthenticatedUser("user-4", "viewer@syncro.dev", ApplicationRole.AUDITOR);
    when(plantScopes.effectiveScope(user)).thenReturn(new PlantScopeResponse(
        "ASSIGNED",
        List.of(new PlantScopeView("plant-4", "PLANT-4", "Plant Four")),
        "plant-4",
        null));

    mockMvc.perform(get("/api/v1/auth/plant-scope")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(user))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("ASSIGNED"))
        .andExpect(jsonPath("$.availablePlants[0].id").value("plant-4"))
        .andExpect(jsonPath("$.defaultPlantId").value("plant-4"));
  }

  @Test
  void outOfScopeResourceCheckReturnsSafeForbiddenError() throws Exception {
    var user = new AuthenticatedUser("user-5", "viewer@syncro.dev", ApplicationRole.AUDITOR);
    var plantId = UUID.randomUUID();
    doThrow(new PlantScopeService.PlantAccessDeniedException()).when(plantScopes).requirePlantAccess(user, plantId);

    mockMvc.perform(get("/api/v1/auth/plant-scope-test/resource")
        .param("plantId", plantId.toString())
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(user))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PLANT_ACCESS_DENIED"))
        .andExpect(jsonPath("$.message").value("You don't have access to this plant's data."))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  private static UsernamePasswordAuthenticationToken authenticationFor(AuthenticatedUser user) {
    return new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
  }

  @org.springframework.web.bind.annotation.RestController
  @org.springframework.web.bind.annotation.RequestMapping("/api/v1/auth/plant-scope-test")
  static class PlantScopeTestController {
    private final PlantScopeService plantScopes;

    PlantScopeTestController(PlantScopeService plantScopes) {
      this.plantScopes = plantScopes;
    }

    @org.springframework.web.bind.annotation.GetMapping("/resource")
    Map<String, String> resource(
        @org.springframework.security.core.annotation.AuthenticationPrincipal AuthenticatedUser user,
        @org.springframework.web.bind.annotation.RequestParam UUID plantId) {
      plantScopes.requirePlantAccess(user, plantId);
      return Map.of("status", "OK");
    }
  }
}
