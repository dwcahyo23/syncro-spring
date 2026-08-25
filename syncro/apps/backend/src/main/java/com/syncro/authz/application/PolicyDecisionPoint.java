package com.syncro.authz.application;

import com.syncro.authz.infrastructure.OpaClient;
import com.syncro.config.AuthzProperties;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Single-sourced OPA input assembly and decision evaluation (AD-1): every authorization
 * question flows through here — the interceptor, application services, and the
 * allowed-actions endpoint all share it.
 *
 * <p>Fail posture is default-deny: any client failure denies, EXCEPT when the current
 * request path matches {@code syncro.authz.degraded-allowlist}, which yields a
 * degraded allow so health/read endpoints survive an OPA outage. Methods never throw.
 *
 * <p>Every enforcement decision is persisted to the decision log (FR-164) via
 * {@link DecisionLogService}. A persistence failure never breaks the enforcement path
 * (fail-open on logging, fail-deny on authz).
 */
@Service
public class PolicyDecisionPoint {

  private static final Logger log = LoggerFactory.getLogger(PolicyDecisionPoint.class);
  private static final String DEFAULT_REVISION_FALLBACK_PATH = "classpath:authz-policy.rego";
  private static final String UNKNOWN_REVISION = "unknown";
  static final String ALLOW_RULE = "allow";
  static final String ACTIONS_RULE = "actions";

  private final OpaClient opa;
  private final AuthzProperties authzProperties;
  private final OperationalScopeService operationalScopes;
  private final DecisionLogService decisionLogs;
  private final ResourceLoader resourceLoader;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public PolicyDecisionPoint(OpaClient opa, AuthzProperties authzProperties,
      OperationalScopeService operationalScopes, DecisionLogService decisionLogs) {
    this.opa = opa;
    this.authzProperties = authzProperties;
    this.operationalScopes = operationalScopes;
    this.decisionLogs = decisionLogs;
    this.resourceLoader = new DefaultResourceLoader();
  }

  /** Evaluates the {@code allow} rule using the current request path for degraded matching. */
  public Decision evaluate(AuthenticatedUser user, OpaResource resource, String action) {
    return evaluate(user, resource, action, currentRequestPath());
  }

  /**
   * Evaluates the {@code allow} rule against an explicit request path (used by the
   * interceptor; the parameterless overload reads the current request instead).
   */
  public Decision evaluate(AuthenticatedUser user, OpaResource resource, String action,
      String requestPath) {
    Decision decision;
    String revision = null;
    try {
      var result = opa.post(ALLOW_RULE, buildInput(user, resource, action));
      if (result.success() && result.httpStatus() == 200) {
        var decisionId = result.decisionId() != null ? result.decisionId()
            : UUID.randomUUID().toString();
        decision = new Decision(result.allowed(), false, decisionId);
        revision = result.revision();
      } else {
        log.warn("[AUTHZ] OPA unavailable — default-deny posture detail={}", result.body());
        decision = degradedOrDeny(requestPath);
      }
    } catch (Exception failure) {
      log.warn("[AUTHZ] Evaluation failed before OPA call — default-deny posture", failure);
      decision = degradedOrDeny(requestPath);
    }
    persistDecision(decision, revision, user, action, resource, requestPath);
    return decision;
  }

  public AllowedActions resolvedActions(AuthenticatedUser user) {
    try {
      var self = new OpaResource("self", null, null, null, null);
      var result = opa.post(ACTIONS_RULE, buildInput(user, self, ACTIONS_RULE));
      if (result.success() && result.httpStatus() == 200) {
        return new AllowedActions(parseActions(result.body()), false);
      }
      log.warn("[AUTHZ] OPA unavailable for actions — degrading to empty set detail={}", result.body());
    } catch (Exception failure) {
      log.warn("[AUTHZ] Actions resolution failed — degrading to empty set", failure);
    }
    return new AllowedActions(List.of(), true);
  }

  /**
   * Actions allowed for the caller: derived from the OPA {@code actions} rule.
   *
   * @param actions  allowed action names (empty when OPA could not be reached)
   * @param degraded true when the set is empty because OPA was unreachable
   */
  public record AllowedActions(List<String> actions, boolean degraded) {

    public AllowedActions {
      actions = actions == null ? List.of() : List.copyOf(actions);
    }
  }

  // -------------------------------------------------------------------------
  // Internal
  // -------------------------------------------------------------------------

  private void persistDecision(Decision decision, String opaRevision, AuthenticatedUser user,
      String action, OpaResource resource, String requestPath) {
    try {
      var revision = computePolicyRevision(opaRevision);
      var userId = user != null ? java.util.UUID.fromString(user.id()) : null;
      var resourceType = resource != null ? resource.type() : "endpoint";
      decisionLogs.record(decision.decisionId(), revision, decision.allowed(), decision.degraded(),
          userId, action, resourceType);
    } catch (Exception failure) {
      // Fail-open on logging: a broken decision log must never break the enforcement path.
      log.warn("[AUTHZ] Failed to persist decision log — enforcement outcome unchanged", failure);
    }
  }

  private Decision degradedOrDeny(String requestPath) {
    if (requestPath != null && matchesAllowlist(requestPath)) {
      return new Decision(true, true, null);
    }
    return new Decision(false, false, null);
  }

  boolean matchesAllowlist(String path) {
    return authzProperties.degradedAllowlist().stream()
        .anyMatch(pattern -> pathMatcher.match(pattern, path));
  }

  OpaInput buildInput(AuthenticatedUser user, OpaResource resource, String action) {
    var scope = user != null
        ? operationalScopes.derive(user)
        : new OperationalScope(null, Set.of(), Set.of());
    var roles = user != null ? List.of(user.applicationRole().name()) : List.<String>of();
    var subject = new OpaSubject(
        user != null ? user.id() : null,
        roles,
        toScopeList(scope.plantIds()),
        toScopeList(scope.machineGroupIds()),
        toScopeList(scope.activeTeamIds()));
    return new OpaInput(subject, resource, action, new OpaContext(traceId()));
  }

  private static <T> List<T> toScopeList(Set<T> ids) {
    return ids == null ? List.of() : List.copyOf(ids);
  }

  private List<String> parseActions(String body) {
    try {
      var actions = objectMapper.readTree(body).path("result");
      if (!actions.isArray()) {
        return List.of();
      }
      var names = new ArrayList<String>();
      actions.forEach(action -> {
        if (action.isTextual()) {
          names.add(action.asText());
        }
      });
      return List.copyOf(names);
    } catch (JsonProcessingException e) {
      log.warn("[AUTHZ] Malformed actions payload treated as empty");
      return List.of();
    }
  }

  private static String traceId() {
    var traceId = MDC.get("traceId");
    return traceId != null ? traceId : UUID.randomUUID().toString();
  }

  /** The correlation id source shared with the interceptor's 403 envelope. */
  public static String currentTraceId() {
    return traceId();
  }

  private static String currentRequestPath() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      return attributes.getRequest().getRequestURI();
    }
    return null;
  }

  /**
   * Returns the OPA envelope revision when present; otherwise computes a sha256 hex of the
   * deployed policy file so the decision log always carries a revision (FR-164). Tries, in
   * order: the configured {@code syncro.authz.policy-revision-fallback-path} (classpath: or
   * file:), a classpath copy, the in-repo file from the workspace root, then the backend
   * working dir. Falls back to {@code "unknown"} only when no policy file is reachable.
   */
  String computePolicyRevision(String opaRevision) {
    if (opaRevision != null && !opaRevision.isBlank()) {
      return opaRevision;
    }
    for (String candidate : revisionCandidates()) {
      var hash = tryPolicyHash(candidate);
      if (hash != null) {
        return hash;
      }
    }
    return UNKNOWN_REVISION;
  }

  private List<String> revisionCandidates() {
    var candidates = new ArrayList<String>();
    var configured = authzProperties.policyRevisionFallbackPath();
    if (configured != null && !configured.isBlank()) {
      candidates.add(configured);
    }
    candidates.add(DEFAULT_REVISION_FALLBACK_PATH);
    candidates.add("file:authz/policy/authz.rego");
    candidates.add("file:syncro/authz/policy/authz.rego");
    return candidates;
  }

  private String tryPolicyHash(String location) {
    try (InputStream is = resourceLoader.getResource(location).getInputStream()) {
      var digest = MessageDigest.getInstance("SHA-256");
      var buffer = new byte[8192];
      int read;
      while ((read = is.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception failure) {
      log.debug("[AUTHZ] Policy revision fallback unavailable at {} ({})", location,
          failure.getMessage());
      return null;
    }
  }
}