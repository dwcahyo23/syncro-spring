package com.syncro.authz.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuthzDtos {
  private AuthzDtos() {
  }

  public record AllowedActionsView(List<String> actions, boolean degraded) {
  }

  /** One persisted OPA decision (FR-164) — masked structural fields only. */
  public record AuthzDecisionView(
      UUID id,
      UUID decisionId,
      String policyRevision,
      boolean allowed,
      boolean degraded,
      UUID userId,
      String action,
      String resourceType,
      Instant decidedAt) {
  }

  public record AuthzDecisionsPageView(List<AuthzDecisionView> items, long totalElements,
      int page, int size) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }
}
