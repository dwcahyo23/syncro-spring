package com.syncro.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds for workorder evidence/technical-drawing uploads (Story 10-5). Bound from
 * {@code syncro.workorder.evidence.*} in {@code application.yml}. The servlet-level
 * multipart limit (10 MB) matches the default so the domain limit is the enforcement
 * point. The 100 MB ceiling guards against a misconfigured value silently disabling
 * the size guard.
 */
@Validated
@ConfigurationProperties(prefix = "syncro.workorder.evidence")
public record WorkorderEvidenceProperties(
    @NotNull @Positive Long maxBytes) {

  public static final long DEFAULT_MAX_BYTES = 10L * 1024L * 1024L;

  public WorkorderEvidenceProperties {
    if (maxBytes == null || maxBytes < 1 || maxBytes > 100L * 1024L * 1024L) {
      throw new IllegalArgumentException(
          "syncro.workorder.evidence.max-bytes must be between 1 and 104857600");
    }
  }
}
