package com.syncro.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds for sparepart image uploads (Story 8-4). Bound from {@code syncro.sparepart.image.*}
 * in {@code application.yml}. The servlet-level multipart limits (10 MB) must be larger than
 * this value so the domain limit is the enforcement point. The 100 MB ceiling guards against
 * a misconfigured value silently disabling the size guard.
 */
@Validated
@ConfigurationProperties(prefix = "syncro.sparepart.image")
public record SparepartImageProperties(
    @NotNull @Positive Long maxBytes) {

  public static final long DEFAULT_MAX_BYTES = 5L * 1024L * 1024L;

  public SparepartImageProperties {
    if (maxBytes == null || maxBytes < 1 || maxBytes > 100L * 1024L * 1024L) {
      throw new IllegalArgumentException(
          "syncro.sparepart.image.max-bytes must be between 1 and 104857600");
    }
  }
}