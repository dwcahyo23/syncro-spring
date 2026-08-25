package com.syncro.authz;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.authz.api.AuthzController;
import com.syncro.authz.api.AuthzDtos.AuthzDecisionView;
import com.syncro.authz.api.AuthzDtos.AuthzDecisionsPageView;
import com.syncro.authz.application.DecisionLogService;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
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

@WebMvcTest(AuthzController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class AuthzControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PolicyDecisionPoint policyDecisionPoint;

  @MockitoBean
  private DecisionLogService decisionLogs;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("9.3-API-001 P1 allowed-actions returns OPA-computed action set")
  void allowedActionsReturnsActionSet() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(policyDecisionPoint.resolvedActions(user))
        .thenReturn(new PolicyDecisionPoint.AllowedActions(List.of("health.read", "workorder.create"), false));

    mockMvc.perform(get("/api/v1/authz/allowed-actions").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actions.length()").value(2))
        .andExpect(jsonPath("$.actions[0]").value("health.read"))
        .andExpect(jsonPath("$.degraded").value(false));
  }

  @Test
  @DisplayName("9.3-API-002 P1 OPA failure degrades to empty set with degraded flag")
  void opaFailureDegradesToEmptySet() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(policyDecisionPoint.resolvedActions(user))
        .thenReturn(new PolicyDecisionPoint.AllowedActions(List.of(), true));

    mockMvc.perform(get("/api/v1/authz/allowed-actions").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actions").isEmpty())
        .andExpect(jsonPath("$.degraded").value(true));
  }

  @Test
  @DisplayName("9.3-API-003 P0 unauthenticated request is rejected with AUTHENTICATION_REQUIRED")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/authz/allowed-actions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("9.5-API-001 P1 SUPER_ADMIN can list persisted decisions")
  void superAdminListsDecisions() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var view = new AuthzDecisionView(UUID.randomUUID(), UUID.randomUUID(), "sha256:abc",
        true, false, UUID.randomUUID(), "GET /api/v1/machines", "endpoint",
        Instant.parse("2026-08-26T00:00:00Z"));
    when(decisionLogs.list(0, 50)).thenReturn(
        new AuthzDecisionsPageView(List.of(view), 1, 0, 50));

    mockMvc.perform(get("/api/v1/authz/decisions").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].allowed").value(true))
        .andExpect(jsonPath("$.items[0].policyRevision").value("sha256:abc"))
        .andExpect(jsonPath("$.items[0].action").value("GET /api/v1/machines"));
  }

  @Test
  @DisplayName("9.5-API-002 P1 AUDITOR can list persisted decisions")
  void auditorListsDecisions() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(decisionLogs.list(0, 50)).thenReturn(
        new AuthzDecisionsPageView(List.of(), 0, 0, 50));

    mockMvc.perform(get("/api/v1/authz/decisions").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  @DisplayName("9.5-API-003 P0 MANAGER_MAINTENANCE is forbidden from the decision log")
  void nonAuditorForbiddenFromDecisions() throws Exception {
    mockMvc.perform(get("/api/v1/authz/decisions").with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("9.5-API-004 P0 unauthenticated decision-log request is rejected")
  void unauthenticatedDecisionsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/authz/decisions"))
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
