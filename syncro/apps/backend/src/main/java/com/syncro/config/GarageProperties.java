package com.syncro.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Connection and bucket configuration for Garage, the S3-compatible object storage service
 * (Story 8-1). Bound from {@code syncro.garage.*} in {@code application.yml}; every value comes
 * from configuration or environment — the {@code local} profile only supplies dev-only fallback
 * credentials so the backend can boot without a populated env file.
 *
 * <p>{@code presignTtlSeconds} controls how long a generated GET URL stays valid; it is
 * intentionally short because object storage serves private objects and the URL is the only
 * thing a client holds. SigV4 rejects signature durations above seven days, so values beyond
 * that cap fail fast here rather than on every presign call.
 */
@Validated
@ConfigurationProperties(prefix = "syncro.garage")
public record GarageProperties(
    @NotBlank String url,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @NotBlank String bucket,
    @NotBlank String region,
    @NotNull @Positive Long presignTtlSeconds) {

  /** Maximum SigV4 signature duration: seven days. */
  public static final long MAX_PRESIGN_TTL_SECONDS = 604_800L;

  public GarageProperties {
    if (presignTtlSeconds == null || presignTtlSeconds < 1
        || presignTtlSeconds > MAX_PRESIGN_TTL_SECONDS) {
      throw new IllegalArgumentException(
          "syncro.garage.presign-ttl-seconds must be between 1 and "
              + MAX_PRESIGN_TTL_SECONDS);
    }
    if (region == null || region.isBlank()) {
      throw new IllegalArgumentException("syncro.garage.region must not be blank");
    }
  }

  public Duration presignTtl() {
    return Duration.ofSeconds(presignTtlSeconds);
  }
}
