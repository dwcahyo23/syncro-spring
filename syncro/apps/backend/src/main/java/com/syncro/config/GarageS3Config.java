package com.syncro.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Springs the S3-compatible client and presigner used to talk to Garage (Story 8-1).
 *
 * <p>Garage is addressed path-style (no virtual-hosted buckets), which is the default for
 * non-AWS S3-compatible storage. The builder is exposed package-private so tests can build an
 * isolated client bound to a mock endpoint without starting over the whole application context.
 *
 * <p>Calls are time-bounded: {@code HttpURLConnection} (the {@code UrlConnectionHttpClient}
 * transport) blocks indefinitely by default, and an unbounded {@code putObject} on a wedged
 * Garage node would exhaust the servlet thread pool — so every client carries an explicit
 * attempt and total-call timeout until Story 8.4 justifies configurable resilience.
 */
@Configuration
public class GarageS3Config {

  static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(15);
  static final Duration API_CALL_TOTAL_TIMEOUT = Duration.ofSeconds(30);

  @Bean(destroyMethod = "close")
  S3Client s3Client(GarageProperties properties) {
    return s3ClientBuilder(properties).build();
  }

  @Bean(destroyMethod = "close")
  S3Presigner s3Presigner(GarageProperties properties) {
    return s3PresignerBuilder(properties).build();
  }

  S3ClientBuilder s3ClientBuilder(GarageProperties properties) {
    return S3Client.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(credentials(properties))
        .endpointOverride(URI.create(properties.url()))
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .overrideConfiguration(ClientOverrideConfiguration.builder()
            .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
            .apiCallTimeout(API_CALL_TOTAL_TIMEOUT)
            .build())
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            // Newer SDK v2 defaults to aws-chunked + CRC32 trailer checksums
            // (STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER), which Garage v2.3 rejects as an
            // invalid payload signature. Sending the payload non-chunked keeps Garage compatible.
            .chunkedEncodingEnabled(false)
            .build());
  }

  S3Presigner.Builder s3PresignerBuilder(GarageProperties properties) {
    return S3Presigner.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(credentials(properties))
        .endpointOverride(URI.create(properties.url()))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
  }

  private static StaticCredentialsProvider credentials(GarageProperties properties) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
  }
}
