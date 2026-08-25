package com.syncro.authz.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Request-scoped stash for the OPA decision id so audit rows written anywhere inside the
 * request can be correlated (FR-164). Outside a request (jobs, ingest, outbox workers)
 * {@link #currentDecisionId()} returns null and audit rows stay uncorrelated.
 */
public final class DecisionContext {

  /** Request-attribute key under which the interceptor stashes the decision id. */
  public static final String DECISION_ID_ATTRIBUTE = "syncro.authz.decisionId";

  private DecisionContext() {
  }

  public static void stash(HttpServletRequest request, String decisionId) {
    request.setAttribute(DECISION_ID_ATTRIBUTE, decisionId);
  }

  public static String currentDecisionId() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
      return (String) attributes.getRequest().getAttribute(DECISION_ID_ATTRIBUTE);
    }
    return null;
  }
}
