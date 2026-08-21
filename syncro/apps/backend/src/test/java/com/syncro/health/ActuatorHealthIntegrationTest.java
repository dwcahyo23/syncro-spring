package com.syncro.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.TestJsonConfig;
import com.syncro.config.InfluxProperties;
import com.syncro.config.TimeConfig;
import com.syncro.notification.infrastructure.WahaCircuitBreakerHealthIndicator;
import com.syncro.notification.infrastructure.WahaClient;
import com.syncro.telemetry.infrastructure.MqttConnectionStatus;
import com.syncro.telemetry.infrastructure.MqttHealthIndicator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end Actuator health contract test (AC 1-7).
 *
 * <p>Loads a minimal context with only the five dependency health indicators and their
 * dependencies, then hits {@code GET /actuator/health} and asserts every expected component
 * key carries the operational status contract fields (AC 6) and that dependency failures
 * (influxdb unreachable, mqtt unknown) are reported as DOWN without crashing the endpoint
 * (AC 7).
 */
@SpringBootTest(
    classes = ActuatorHealthIntegrationTest.HealthTestApplication.class,
    properties = {
      "server.port=0",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
          + "org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration,"
          + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
          + "org.springframework.boot.data.redis.repository.configuration.RedisRepositoriesAutoConfiguration",
      "management.health.db.enabled=false",
      "management.health.redis.enabled=false",
      "syncro.influxdb.url=http://localhost:9999",
      "syncro.influxdb.token=test-token",
      "syncro.influxdb.database=test-db"
    })
@AutoConfigureMockMvc
class ActuatorHealthIntegrationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private DataSource dataSource;

  @MockitoBean
  private Connection connection;

  @MockitoBean
  private RedisConnectionFactory redisConnectionFactory;

  @MockitoBean
  private RedisConnection redisConnection;

  @MockitoBean
  private WahaClient wahaClient;

  @Test
  void actuatorHealth_reportsAllFiveDependenciesWithContractFields() throws Exception {
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.isValid(2)).thenReturn(true);
    when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
    when(redisConnection.ping()).thenReturn("PONG");
    CircuitBreaker breaker =
        CircuitBreakerRegistry.ofDefaults().circuitBreaker("waha-integration");
    when(wahaClient.getCircuitBreaker()).thenReturn(breaker);

    // Overall status is DOWN (influxdb unreachable + mqtt UNKNOWN) so Actuator answers
    // 503 SERVICE_UNAVAILABLE with a structured body — a healthy "reported DOWN" outcome,
    // NOT a crash (no 500 / stack trace).
    String body = mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"))
        .andExpect(jsonPath("$.components.db.status").value("UP"))
        .andExpect(jsonPath("$.components.redis.status").value("UP"))
        .andExpect(jsonPath("$.components.mqtt.status").value("DOWN"))
        .andExpect(jsonPath("$.components.influxdb.status").value("DOWN"))
        .andExpect(jsonPath("$.components.wahaCircuitBreaker.status").value("UP"))
        .andReturn().getResponse().getContentAsString();

    JsonNode components = objectMapper.readTree(body).get("components");
    assertThat(components.fieldNames())
        .toIterable()
        .contains("db", "redis", "mqtt", "influxdb", "wahaCircuitBreaker");

    for (String key : new String[] {"db", "redis", "mqtt", "influxdb", "wahaCircuitBreaker"}) {
      JsonNode component = components.get(key);
      assertThat(component.get("status")).isNotNull();
      assertThat(component.get("details").get("statusLabel")).isNotNull();
      assertThat(component.get("details").get("statusSeverity")).isNotNull();
      assertThat(component.get("details").get("timestamp")).isNotNull();
    }
  }

  @Configuration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(InfluxProperties.class)
  @ComponentScan(basePackageClasses = DbHealthIndicator.class)
  @Import({TimeConfig.class, TestJsonConfig.class})
  static class HealthTestApplication {

    @Bean
    MqttConnectionStatus mqttConnectionStatus(Clock clock) {
      return new MqttConnectionStatus(clock);
    }

    @Bean
    MqttHealthIndicator mqttHealthIndicator(MqttConnectionStatus status, Clock clock) {
      return new MqttHealthIndicator(status, clock);
    }

    @Bean
    WahaCircuitBreakerHealthIndicator wahaCircuitBreakerHealthIndicator(
        WahaClient client, Clock clock) {
      return new WahaCircuitBreakerHealthIndicator(client, clock);
    }
  }
}