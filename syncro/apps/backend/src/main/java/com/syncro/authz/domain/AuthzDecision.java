package com.syncro.authz.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted OPA enforcement decision (FR-164 / NFR-P2-7). Masks by construction: the
 * OPA input schema carries only subject + scope + action, so a decision row stores only
 * structural fields — never request bodies, WAHA secrets, or phone numbers.
 *
 * @param decisionId    OPA envelope {@code decision_id} (or the generated UUID fallback)
 * @param policyRevision OPA envelope {@code revision} when present, else sha256(authz.rego)
 * @param allowed       policy outcome (degraded allows also carry {@code true})
 * @param degraded      true when the outcome came from the degraded-mode allowlist
 * @param userId        authenticated subject id (null for anonymous subjects)
 * @param action        the evaluated action string ({@code "<METHOD> <path>"})
 * @param resourceType  resource type evaluated ({@code endpoint} for interceptor decisions)
 * @param decidedAt     decision timestamp (server-side UTC)
 */
public record AuthzDecision(
    UUID decisionId,
    String policyRevision,
    boolean allowed,
    boolean degraded,
    UUID userId,
    String action,
    String resourceType,
    Instant decidedAt) {

  public AuthzDecision {
    if (action == null || action.isBlank()) {
      throw new IllegalArgumentException("action must not be blank");
    }
  }
}
