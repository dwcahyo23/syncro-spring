package com.syncro.authz.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.authz.api.AuthzDtos.ErrorResponse;
import com.syncro.authz.application.DecisionContext;
import com.syncro.authz.application.OpaResource;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.config.AuthzProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Coarse authorization gate (FR-160): runs before controllers on every configured
 * enforced path, asks the {@link PolicyDecisionPoint} for a decision, and answers 403
 * inline before any business logic when denied. Allowed requests carry the decision id
 * into request attributes so audit rows can correlate (FR-164).
 *
 * <p>Deliberately not a {@code @Component}: MVC test slices include HandlerInterceptor
 * beans, so instantiation is owned exclusively by {@link AuthzWebMvcConfig}.
 */
public class AuthzInterceptor implements HandlerInterceptor {

  private final PolicyDecisionPoint policyDecisionPoint;
  private final AuthzProperties authzProperties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public AuthzInterceptor(PolicyDecisionPoint policyDecisionPoint,
      AuthzProperties authzProperties, ObjectMapper objectMapper, Clock clock) {
    this.policyDecisionPoint = policyDecisionPoint;
    this.authzProperties = authzProperties;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) throws IOException {
    // CORS preflights carry no credentials to evaluate, and ERROR dispatches must not
    // mask the original failure with a 403.
    if ("OPTIONS".equals(request.getMethod()) || request.getDispatcherType() != DispatcherType.REQUEST) {
      return true;
    }
    var lookupPath = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication().value();
    var action = request.getMethod() + " " + matchedPattern(lookupPath, request);
    var decision = policyDecisionPoint.evaluate(
        authenticatedUser(request), endpointResource(), action, lookupPath);

    if (!decision.allowed()) {
      writeForbidden(response);
      return false;
    }
    DecisionContext.stash(request, decision.decisionId());
    return true;
  }

  private OpaResource endpointResource() {
    return new OpaResource("endpoint", null, null, null, Map.of());
  }

  private AuthenticatedUser authenticatedUser(HttpServletRequest request) {
    if (request.getUserPrincipal() instanceof Authentication authentication
        && authentication.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    return null;
  }

  /**
   * Prefers the pattern Spring's handler mapping already resolved (best match, not
   * config order); falls back to the configured list for unmatched probes.
   */
  private String matchedPattern(String path, HttpServletRequest request) {
    if (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String best) {
      return best;
    }
    for (var pattern : authzProperties.enforcedPaths()) {
      if (pathMatcher.match(pattern, path)) {
        return pattern;
      }
    }
    return path;
  }

  /** House error shape (code/message/fieldErrors/timestamp/traceId), written before any controller runs. */
  private void writeForbidden(HttpServletResponse response) throws IOException {
    response.setStatus(403);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), new ErrorResponse(
        "FORBIDDEN",
        "You do not have permission to access this resource.",
        Map.of(),
        Instant.now(clock).toString(),
        PolicyDecisionPoint.currentTraceId()));
  }
}
