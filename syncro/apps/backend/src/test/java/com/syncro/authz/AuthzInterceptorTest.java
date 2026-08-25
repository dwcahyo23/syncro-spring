package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.authz.application.DecisionContext;
import com.syncro.authz.application.DecisionLogService;
import com.syncro.authz.application.OpaInput;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.authz.infrastructure.AuthzInterceptor;
import com.syncro.config.AuthzProperties;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interceptor mechanism tests (standalone MockMvc): deny writes 403 before the controller,
 * allow stashes the decision id into request attributes, degraded allowlist survives an
 * OPA outage. enforced-paths here are test-local; production ships them empty until 9.5.
 */
@ExtendWith(MockitoExtension.class)
class AuthzInterceptorTest {

  private final AuthzProperties props = new AuthzProperties(
      List.of("/api/v1/secure/**", "/api/v1/health/**"),
      List.of("/api/v1/health/**"), 30, null);

  @Mock
  private com.syncro.authz.infrastructure.OpaClient opaClient;

  @Mock
  private OperationalScopeService operationalScopes;

  @Mock
  private DecisionLogService decisionLogs;

  private PolicyDecisionPoint pdp;
  private MockMvc mockMvc;
  private final ProbeController probe = new ProbeController();

  @BeforeEach
  void setUp() {
    pdp = new PolicyDecisionPoint(opaClient, props, operationalScopes, decisionLogs);
    Mockito.lenient().when(operationalScopes.derive(any(AuthenticatedUser.class)))
        .thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));
    var interceptor = new AuthzInterceptor(pdp, props, new ObjectMapper(), Clock.systemUTC());
    probe.reached.set(false);
    mockMvc = MockMvcBuilders.standaloneSetup(probe)
        .addInterceptors(interceptor)
        .build();
  }

  @Test
  @DisplayName("9.3-INT-001 P0 denied decision returns 403 FORBIDDEN before the controller runs")
  void deniedWrites403BeforeController() throws Exception {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new com.syncro.authz.infrastructure.OpaClient.Result(
            true, 200, "{\"decision_id\":\"d0\",\"result\":false}", "d0", false));

    mockMvc.perform(request("/api/v1/secure/probe"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("You do not have permission to access this resource."))
        .andExpect(jsonPath("$.fieldErrors").exists())
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());

    assertThat(probe.reached.get()).isFalse();
  }

  @Test
  @DisplayName("9.3-INT-002 P0 allowed decision reaches controller and stashes decision id")
  void allowedStashesDecisionId() throws Exception {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new com.syncro.authz.infrastructure.OpaClient.Result(
            true, 200, "{\"decision_id\":\"d1\",\"result\":true}", "d1", true));

    mockMvc.perform(request("/api/v1/secure/probe"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decisionId").value("d1"));

    assertThat(probe.reached.get()).isTrue();
  }

  @Test
  @DisplayName("9.3-INT-003 P1 sidecar down on a degraded-allowlisted enforced path still serves 200")
  void sidecarDownOnAllowlistedPathServes200() throws Exception {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new com.syncro.authz.infrastructure.OpaClient.Result(
            false, 0, com.syncro.authz.infrastructure.OpaClient.CIRCUIT_OPEN_DETAIL, null, false));

    mockMvc.perform(request("/api/v1/health/status"))
        .andExpect(status().isOk());

    assertThat(probe.reached.get()).isTrue();
  }

  @Test
  @DisplayName("9.3-INT-004 P1 action string uses METHOD plus Spring's best-matching handler pattern")
  void actionUsesMatchedPattern() throws Exception {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new com.syncro.authz.infrastructure.OpaClient.Result(
            true, 200, "{\"decision_id\":\"d2\",\"result\":true}", "d2", true));

    mockMvc.perform(request("/api/v1/secure/probe"));

    verify(opaClient).post(eq("allow"), argThat(input -> {
      var captured = (OpaInput) input;
      // Order-independent and maximally specific: Spring's own resolved pattern.
      return "GET /api/v1/secure/probe".equals(captured.action())
          && "endpoint".equals(captured.resource().type());
    }));
  }

  @Test
  @DisplayName("9.5-INT-005 P1 interceptor persists decision via PDP after an allowed evaluate")
  void interceptorPersistsDecisionOnAllow() throws Exception {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new com.syncro.authz.infrastructure.OpaClient.Result(
            true, 200, "{\"decision_id\":\"d5\",\"result\":true,\"revision\":\"r1\"}", "d5", true, "r1"));

    mockMvc.perform(request("/api/v1/secure/probe"))
        .andExpect(status().isOk());

    verify(decisionLogs).record(eq("d5"), eq("r1"), eq(true), eq(false),
        any(UUID.class), eq("GET /api/v1/secure/probe"), eq("endpoint"));
  }

  private MockHttpServletRequestBuilder request(String uri) {
    return get(uri)
        .accept(MediaType.APPLICATION_JSON)
        .principal(auth(user()));
  }

  private static AuthenticatedUser user() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "tech@syncro.dev", ApplicationRole.MANAGER_MAINTENANCE);
  }

  private static UsernamePasswordAuthenticationToken auth(AuthenticatedUser user) {
    return new UsernamePasswordAuthenticationToken(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
  }

  @RestController
  static class ProbeController {

    final AtomicBoolean reached = new AtomicBoolean(false);

    @GetMapping("/api/v1/secure/probe")
    public Map<String, Object> secureProbe(HttpServletRequest request) {
      reached.set(true);
      return Map.of("decisionId",
          String.valueOf(request.getAttribute(DecisionContext.DECISION_ID_ATTRIBUTE)));
    }

    @GetMapping("/api/v1/health/status")
    public Map<String, Object> healthProbe(HttpServletRequest request) {
      reached.set(true);
      return Map.of("decisionId",
          String.valueOf(request.getAttribute(DecisionContext.DECISION_ID_ATTRIBUTE)));
    }
  }
}
