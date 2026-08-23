package com.syncro.storage.infrastructure;

import com.syncro.config.GarageProperties;
import com.syncro.health.DependencyHealthSupport;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Actuator health indicator for Garage connectivity ({@code components.garage}).
 *
 * <p>Performs a bounded {@code HeadBucket} call against the configured bucket — this proves
 * reachability AND credentials without listing objects. Any failure is mapped to DOWN with a
 * sanitized reason code ({@link DependencyHealthSupport#reasonCode}); raw SDK messages never
 * reach the public unauthenticated {@code /actuator/health} endpoint.
 */
@Component("garage")
public class GarageHealthIndicator implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(GarageHealthIndicator.class);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

  private final S3Client s3Client;
  private final GarageProperties properties;
  private final Clock clock;

  public GarageHealthIndicator(S3Client s3Client, GarageProperties properties, Clock clock) {
    this.s3Client = s3Client;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try {
      HeadBucketRequest request =
          HeadBucketRequest.builder()
              .bucket(properties.bucket())
              .overrideConfiguration(configuration -> configuration.apiCallTimeout(PROBE_TIMEOUT))
              .build();
      s3Client.headBucket(request);
      return DependencyHealthSupport.enrich(Health.up().build(), clock);
    } catch (AwsServiceException exception) {
      // Garage answers HTTP-level auth failures as 401/403 with "Access Denied" bodies that
      // keyword classification cannot recognize, so map by status code first.
      log.warn("Garage health check failed", exception);
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, serviceReason(exception), null);
    } catch (Exception exception) {
      log.warn("Garage health check failed", exception);
      return DependencyHealthSupport.enrich(
          Health.down().build(), clock, DependencyHealthSupport.reasonCode(exception), null);
    }
  }

  private static String serviceReason(AwsServiceException exception) {
    return switch (exception.statusCode()) {
      case 401, 403 -> "UNAUTHORIZED";
      // headBucket 404 means the configured bucket does not exist — a config error, not a
      // malformed request; give ops the specific code.
      case 404 -> "BUCKET_MISSING";
      default -> DependencyHealthSupport.reasonCode(exception);
    };
  }
}
