package com.syncro.health;

import com.syncro.config.InfluxProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Actuator health indicator for InfluxDB v3 connectivity ({@code components.influxdb}).
 *
 * <p>Performs a bounded HTTP {@code GET <url>/ping} with a 2-second connect/read timeout.
 * Any failure (unreachable, timeout, non-2xx) is mapped to DOWN without throwing.
 *
 * <p>InfluxDB v3 Java client (influxdb3-java:1.10.0) does not expose a {@code ping()} method
 * on the {@code InfluxDBClient} interface, so this indicator uses the HTTP fallback via
 * {@code RestClient} with an explicit 2-second timeout as specified in the story.
 */
@Component("influxdb")
public class InfluxDbHealthIndicator implements HealthIndicator {

  private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

  private final RestClient.Builder restClientBuilder;
  private final Clock clock;

  public InfluxDbHealthIndicator(InfluxProperties properties, Clock clock) {
    this.clock = clock;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(PING_TIMEOUT);
    requestFactory.setReadTimeout(PING_TIMEOUT);
    this.restClientBuilder = RestClient.builder()
        .baseUrl(properties.url())
        .requestFactory(requestFactory);
  }

  @Override
  public Health health() {
    try {
      restClientBuilder.build().get().uri("/ping").retrieve().toBodilessEntity();
      return DependencyHealthSupport.enrich(Health.up().build(), clock);
    } catch (Exception exception) {
      return DependencyHealthSupport.enrich(
          Health.down(exception).build(), clock, exception.getMessage(), null);
    }
  }

  /**
   * Exposed for testability — allows {@code MockRestServiceServer} to bind to the underlying
   * {@code RestClient.Builder} before the health check builds the client.
   */
  RestClient.Builder restClientBuilder() {
    return restClientBuilder;
  }
}