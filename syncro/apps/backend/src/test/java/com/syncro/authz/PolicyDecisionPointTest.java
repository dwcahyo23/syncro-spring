package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.authz.application.OpaInput;
import com.syncro.authz.application.OpaResource;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.authz.infrastructure.OpaClient;
import com.syncro.config.AuthzProperties;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit tests for {@link PolicyDecisionPoint}: single-sourced input assembly (exact wire
 * shape with derived scope) plus the fail-deny / degraded-allowlist decision matrix.
 */
@ExtendWith(MockitoExtension.class)
class PolicyDecisionPointTest {

  @Mock
  private OpaClient opaClient;

  @Mock
  private OperationalScopeService operationalScopes;

  private PolicyDecisionPoint pdp;

  private final AuthenticatedUser user =
      new AuthenticatedUser("11111111-1111-1111-1111-111111111111", "tech@syncro.dev", ApplicationRole.MANAGE);
  private final OpaResource resource = new OpaResource("workorder", null, null, null, null);

  @BeforeEach
  void setUp() {
    pdp = new PolicyDecisionPoint(opaClient,
        new AuthzProperties(List.of(), List.of("/api/v1/health", "/actuator/**")),
        operationalScopes);
    // Default empty scope so evaluate() paths that carry an identity never see null scope
    Mockito.lenient().when(operationalScopes.derive(any()))
        .thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));
    var request = new MockHttpServletRequest();
    request.setRequestURI("/api/v1/work-orders");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  @DisplayName("9.3-PDP-001 allow rule returns true with OPA decision id")
  void evaluateAllowsWithOpaDecisionId() {
    stubScope();
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"decision_id\":\"d1\",\"result\":true}", "d1", true));

    var decision = pdp.evaluate(user, resource, "POST /api/v1/work-orders");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.degraded()).isFalse();
    assertThat(decision.decisionId()).isEqualTo("d1");
  }

  @Test
  @DisplayName("9.3-PDP-002 deny rule keeps allowed=false and the OPA decision id")
  void evaluateDeniesOnFalseResult() {
    stubScope();
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"decision_id\":\"d2\",\"result\":false}", "d2", false));

    var decision = pdp.evaluate(user, resource, "POST /api/v1/work-orders");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.degraded()).isFalse();
    assertThat(decision.decisionId()).isEqualTo("d2");
  }

  @Test
  @DisplayName("9.3-PDP-003 missing decision id falls back to a generated UUID")
  void generateDecisionIdWhenOpaOmitsIt() {
    stubScope();
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"result\":true}", null, true));

    var decision = pdp.evaluate(user, resource, "read");

    assertThat(decision.decisionId()).isNotNull();
    UUID.fromString(decision.decisionId());
  }

  @Test
  @DisplayName("9.3-PDP-004 client failure denies non-allowlisted paths")
  void failureDeniesNonAllowlistedPath() {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(false, 0,
            com.syncro.authz.infrastructure.OpaClient.CIRCUIT_OPEN_DETAIL, null, false));

    var decision = pdp.evaluate(user, resource, "POST /api/v1/work-orders");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.degraded()).isFalse();
    assertThat(decision.decisionId()).isNull();
  }

  @Test
  @DisplayName("9.3-PDP-005 circuit-open result behaves like any other client failure")
  void circuitOpenBehavesLikeFailure() {
    when(opaClient.post(eq("allow"), any())).thenReturn(
        new OpaClient.Result(false, 0, OpaClient.CIRCUIT_OPEN_DETAIL, null, false));

    var decision = pdp.evaluate(user, resource, "POST /api/v1/work-orders");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.degraded()).isFalse();
  }

  @Test
  @DisplayName("9.3-PDP-006 client failure on an allowlisted path degrades to allow")
  void failureOnAllowlistedPathDegradesToAllow() {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(false, 0, OpaClient.CIRCUIT_OPEN_DETAIL, null, false));

    var decision = pdp.evaluate(user, resource, "GET /actuator/health", "/actuator/health");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.degraded()).isTrue();
    assertThat(decision.decisionId()).isNull();
  }

  @Test
  @DisplayName("9.3-PDP-007 outside any request context a failure still denies")
  void failureWithoutRequestContextDenies() {
    RequestContextHolder.resetRequestAttributes();
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(false, 0, "connection refused", null, false));

    var decision = pdp.evaluate(user, resource, "GET /actuator/health");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.degraded()).isFalse();
  }

  @Test
  @DisplayName("9.3-PDP-008 captured input carries exact subject/resource/action/context shape")
  void capturedInputCarriesDerivedScopeArrays() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var teamGroupId = UUID.randomUUID();
    when(operationalScopes.derive(user)).thenReturn(
        new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of(teamGroupId)));
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"decision_id\":\"d1\",\"result\":true}", "d1", true));

    pdp.evaluate(user, resource, "POST /api/v1/work-orders");

    var captor = ArgumentCaptor.forClass(Object.class);
    verify(opaClient).post(eq("allow"), captor.capture());
    assertThat(captor.getValue()).isInstanceOf(OpaInput.class);
    var input = (OpaInput) captor.getValue();

    assertThat(input.subject().userId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(input.subject().roles()).containsExactly("MANAGE");
    assertThat(input.subject().plantIds()).containsExactly(plantId);
    assertThat(input.subject().machineGroupIds()).containsExactly(groupId);
    assertThat(input.subject().activeTeamIds()).containsExactly(teamGroupId);
    assertThat(input.resource()).isEqualTo(resource);
    assertThat(input.action()).isEqualTo("POST /api/v1/work-orders");
    assertThat(input.context().traceId()).isNotBlank();
  }

  @Test
  @DisplayName("9.3-PDP-009 SUPER_ADMIN null plantIds serialize as empty list")
  void superAdminNullPlantIdsSerializeAsEmptyList() {
    when(operationalScopes.derive(user)).thenReturn(
        new OperationalScope(null, Set.of(), Set.of()));
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"result\":true}", "d3", true));

    pdp.evaluate(user, resource, "read");

    var captor = ArgumentCaptor.forClass(Object.class);
    verify(opaClient).post(eq("allow"), captor.capture());
    var input = (OpaInput) captor.getValue();
    assertThat(input.subject().plantIds()).isEmpty();
    assertThat(input.subject().machineGroupIds()).isEmpty();
    assertThat(input.subject().activeTeamIds()).isEmpty();
  }

  @Test
  @DisplayName("9.3-PDP-010 actions rule maps to AllowedActions")
  void resolvedActionsParsesArrayResult() {
    stubScope();
    when(opaClient.post(eq("actions"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"result\":[\"health.read\",\"wo.read\"]}", null, true));

    var actions = pdp.resolvedActions(user);

    assertThat(actions.actions()).containsExactly("health.read", "wo.read");
    assertThat(actions.degraded()).isFalse();
  }

  @Test
  @DisplayName("9.3-PDP-011 actions rule failure degrades to empty set with flag")
  void resolvedActionsFailureDegradesToEmpty() {
    when(opaClient.post(eq("actions"), any()))
        .thenReturn(new OpaClient.Result(false, 0, "connection refused", null, false));

    var actions = pdp.resolvedActions(user);

    assertThat(actions.actions()).isEmpty();
    assertThat(actions.degraded()).isTrue();
  }

  @Test
  @DisplayName("9.3-PDP-012 unauthenticated caller skips scope derivation but still asks OPA")
  void anonymousCallerStillProducesTotalInput() {
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200, "{\"result\":false}", "d4", false));

    var decision = pdp.evaluate(null, resource, "GET /api/v1/secure/probe");

    var captor = ArgumentCaptor.forClass(Object.class);
    verify(opaClient).post(eq("allow"), captor.capture());
    var input = (OpaInput) captor.getValue();
    assertThat(input.subject().userId()).isNull();
    assertThat(input.subject().roles()).isEmpty();
    assertThat(input.subject().plantIds()).isEmpty();
    assertThat(decision.allowed()).isFalse();
  }

  private void stubScope() {
    when(operationalScopes.derive(user))
        .thenReturn(new OperationalScope(Set.of(UUID.randomUUID()), Set.of(), Set.of()));
  }
}
