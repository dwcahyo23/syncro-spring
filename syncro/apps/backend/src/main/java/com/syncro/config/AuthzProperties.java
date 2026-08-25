package com.syncro.config;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Coarse enforcement configuration for the authz layer.
 *
 * <p>{@code enforced-paths} ships EMPTY for story 9.3 — zero behavior change to existing
 * routes; story 9.5 populates it when endpoints are mapped to policy actions.
 * {@code degraded-allowlist} holds Ant patterns that stay allowed when the OPA sidecar
 * is unreachable (fail-deny applies everywhere else). {@code decisionLogRetentionDays}
 * drives the {@code authz_decisions} purge window (default 30 days, NFR-P2-7).
 */
@Validated
@ConfigurationProperties(prefix = "syncro.authz")
public record AuthzProperties(
    @NotNull List<String> enforcedPaths,
    @NotNull List<String> degradedAllowlist,
    int decisionLogRetentionDays,
    String policyRevisionFallbackPath) {

  /** Canonical constructor — null lists normalize onto safe defaults; explicit empty stays authoritative. */
  public AuthzProperties {
    enforcedPaths = enforcedPaths == null ? List.of() : List.copyOf(enforcedPaths);
    // Only an UNSET property falls back to defaults; an explicitly empty allowlist means
    // the operator wants strict fail-deny with no degraded allows.
    if (degradedAllowlist == null) {
      degradedAllowlist = List.of("/api/v1/health", "/actuator/**");
    } else {
      degradedAllowlist = List.copyOf(degradedAllowlist);
    }
    if (decisionLogRetentionDays <= 0) {
      decisionLogRetentionDays = 30;
    }
  }
}
