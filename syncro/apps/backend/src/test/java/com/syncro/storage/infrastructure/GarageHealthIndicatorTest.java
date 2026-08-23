package com.syncro.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.syncro.config.GarageProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class GarageHealthIndicatorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-23T08:00:00Z"), ZoneOffset.UTC);
  private static final String FIXED_TIMESTAMP = "2026-08-23T08:00:00Z";

  private static final GarageProperties PROPERTIES =
      new GarageProperties(
          "http://localhost:3900", "access-key", "secret-key", "syncro-spareparts", "garage", 300L);

  @Mock
  private S3Client s3Client;

  private GarageHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    indicator = new GarageHealthIndicator(s3Client, PROPERTIES, FIXED_CLOCK);
  }

  @Test
  void health_whenBucketReachable_returnsUpWithContractFields() {
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Up")
        .containsEntry("statusSeverity", "SUCCESS")
        .containsEntry("timestamp", FIXED_TIMESTAMP);

    ArgumentCaptor<HeadBucketRequest> captor =
        ArgumentCaptor.forClass(HeadBucketRequest.class);
    verify(s3Client).headBucket(captor.capture());
    assertThat(captor.getValue().bucket()).isEqualTo("syncro-spareparts");
    // Probe is time-bounded so an unresponsive Garage cannot hang the health endpoint.
    assertThat(captor.getValue().overrideConfiguration())
        .hasValueSatisfying(
            override -> assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(2)));
  }

  @Test
  void health_whenConnectionRefused_returnsDownWithSanitizedReason() {
    doThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:3900"))
        .when(s3Client)
        .headBucket(any(HeadBucketRequest.class));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("statusLabel", "Down")
        .containsEntry("statusSeverity", "CRITICAL")
        .containsEntry("timestamp", FIXED_TIMESTAMP)
        .containsEntry("statusReason", "CONNECTION_REFUSED");
  }

  @Test
  void health_whenUnauthorized_returnsDownWithoutLeakingSdkMessage() {
    doThrow(
            (RuntimeException)
                S3Exception.builder()
                    .statusCode(403)
                    .message(
                        "Access Denied (Service: Amazon S3; Status Code: 403; "
                            + "secret-key material must never appear here)")
                    .awsErrorDetails(
                        AwsErrorDetails.builder().errorMessage("Access Denied").build())
                    .build())
        .when(s3Client)
        .headBucket(any(HeadBucketRequest.class));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("statusReason", "UNAUTHORIZED");
    assertThat(health.getDetails().toString())
        .doesNotContain("secret-key material must never appear here");
  }
}
