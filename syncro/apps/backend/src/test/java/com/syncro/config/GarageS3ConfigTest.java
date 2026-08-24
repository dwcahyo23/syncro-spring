package com.syncro.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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

  /**
   * Regression: the SDK must never send aws-chunked or trailer checksums, because Garage
   * v2.3 rejects STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER with "Invalid payload signature".
   * We stub a local HTTP server to capture the wire request and assert the absence of these
   * headers / encoded content.
   */
  @Test
  void s3Client_putObject_doesNotSendAwsChunkedOrTrailer() throws IOException {
    var capturedHeaders = new AtomicReference<Map<String, List<String>>>();
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    var port = server.getAddress().getPort();
    server.createContext("/", exchange -> {
      capturedHeaders.set(exchange.getRequestHeaders());
      var body = """
          <?xml version="1.0" encoding="UTF-8"?>
          <PutObjectResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"/>
          """.getBytes();
      exchange.getResponseHeaders().add("ETag", "\"abc123\"");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    server.start();
    try {
      var localProps = new GarageProperties(
          "http://127.0.0.1:" + port, "access-key", "secret-key", "bucket", "garage", 300L);
      try (S3Client client = config.s3ClientBuilder(localProps).build()) {
        client.putObject(
            PutObjectRequest.builder()
                .bucket("bucket")
                .key("test/img.png")
                .contentType("image/png")
                .build(),
            RequestBody.fromBytes("test-data".getBytes()));
      }
      var headers = capturedHeaders.get();
      assertThat(headers).as("captured request headers").isNotNull();
      assertThat(headers.get("Content-Encoding"))
          .as("must not be aws-chunked")
          .isNull();
      assertThat(headers.get("x-amz-trailer"))
          .as("must not send trailer checksum")
          .isNull();
      assertThat(headers.get("x-amz-content-sha256"))
          .as("must use actual payload hash, not streaming placeholder")
          .allMatch(v -> !v.startsWith("STREAMING"));
    } finally {
      server.stop(0);
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
