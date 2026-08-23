package com.syncro.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Unit tests for the Garage S3 client/presigner wiring (Story 8-1). Presigning is a purely
 * offline operation (SigV4 over the configured endpoint), so the generated URL can be asserted
 * without a running Garage.
 */
class GarageS3ConfigTest {

  private final GarageS3Config config = new GarageS3Config();
  private final GarageProperties properties =
      new GarageProperties(
          "http://localhost:3900", "access-key", "secret-key", "syncro-spareparts", "garage", 300L);

  @Test
  void s3ClientBuilder_producesClientWithoutNetworkCall() {
    try (S3Client client = config.s3ClientBuilder(properties).build()) {
      assertThat(client).isNotNull();
    }
  }

  @Test
  void presignerBuilder_producesPresignerWithoutNetworkCall() {
    try (S3Presigner presigner = config.s3PresignerBuilder(properties).build()) {
      assertThat(presigner).isNotNull();
    }
  }

  @Test
  void presignedUrl_targetsConfiguredEndpointWithBucketRelativePathAndTtl() {
    try (S3Presigner presigner = config.s3PresignerBuilder(properties).build()) {
      String url =
          presigner
              .presignGetObject(
                  GetObjectPresignRequest.builder()
                      .signatureDuration(properties.presignTtl())
                      .getObjectRequest(spec ->
                          spec.bucket(properties.bucket()).key("img/a.png"))
                      .build())
              .url()
              .toString();

      URI uri = URI.create(url);
      assertThat(uri.getHost()).isEqualTo("localhost");
      assertThat(uri.getPort()).isEqualTo(3900);
      assertThat(uri.getPath()).isEqualTo("/" + properties.bucket() + "/img/a.png");
      assertThat(url)
          .contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
          .contains("X-Amz-Credential=access-key%2F")
          .contains("X-Amz-SignedHeaders=host")
          .contains("X-Amz-Expires=" + properties.presignTtlSeconds());
    }
  }

  @Test
  void properties_exposeFiveMinuteTtlFromSeconds() {
    assertThat(properties.presignTtl()).hasMinutes(5);
  }

  @Test
  void properties_rejectsTtlBeyondSigV4SevenDayCap_andBlankRegion() {
    assertThatThrownBy(() -> new GarageProperties(
        "http://localhost:3900", "a", "s", "bucket", "garage", 604_801L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("presign-ttl-seconds");
    assertThatThrownBy(() -> new GarageProperties(
        "http://localhost:3900", "a", "s", "bucket", " ", 300L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("region");
  }
}
