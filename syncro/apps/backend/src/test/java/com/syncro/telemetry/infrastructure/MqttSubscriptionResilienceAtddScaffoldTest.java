package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;

import com.syncro.config.MqttProperties;
import com.syncro.config.TimeConfig;
import com.syncro.telemetry.application.MqttTelemetryIngestHandler;
import com.syncro.telemetry.application.TelemetryDataQualityTracker;
import com.syncro.telemetry.application.TelemetryIngestTracker;
import com.syncro.telemetry.application.TelemetryPersistenceService;
import com.syncro.telemetry.application.TelemetryQuarantineService;
import com.syncro.telemetry.application.TelemetryValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;

// Story 3.1 ATDD RED-phase scaffold (R-001 broker-down resilience, R-004
// credentials, R-010 health observability).
//
// The existing MqttSubscriptionConfigTest locks adapter wiring (topic/QoS/
// autoStartup, server URI). These scaffolds add the uncovered acceptance locks:
// (1) the full context still loads and exposes health DOWN while the broker is
// unreachable; (2) connection options carry the configured username/password
// and reconnect settings; (3) a MqttHealthIndicator bean is registered.
// Activated (DW-8, bundle 12): all three pass against the shipped implementation.
//
// Run targeted:
//   $env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
//   mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttSubscriptionResilienceAtddScaffoldTest"
@SpringBootTest(classes = {
    MqttSubscriptionConfig.class,
    MqttTelemetryIngestHandler.class,
    MqttConnectionStatus.class,
    MqttHealthIndicator.class,
    TimeConfig.class,
    com.syncro.telemetry.infrastructure.TelemetryIngestQueueConfig.class,
    MqttSubscriptionResilienceAtddScaffoldTest.MqttPropertiesTestConfiguration.class
}, properties = {
    "syncro.mqtt.host=localhost",
    "syncro.mqtt.port=1883",
    "syncro.mqtt.username=test-user",
    "syncro.mqtt.password=s3cret",
    "syncro.mqtt.client-id=syncro-test",
    "syncro.mqtt.topic-filter=factory/+/+/telemetry",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class MqttSubscriptionResilienceAtddScaffoldTest {

  @Autowired
  private MqttPahoMessageDrivenChannelAdapter adapter;

  @Autowired
  private MqttPahoClientFactory clientFactory;

  @Autowired
  private MqttHealthIndicator healthIndicator;

  @Test
  void contextLoadsAndHealthIsDownWhileBrokerUnreachable() {
    // R-001: with no broker on localhost:1883 the adapter must publish
    // MqttConnectionFailedEvent instead of throwing, so the context still loads
    // and the mqtt health component reports DOWN (UNKNOWN/FAILED -> DOWN).
    assertThat(adapter).isNotNull();
    Health health = healthIndicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void connectionOptionsCarryCredentialsAndReconnectSettings() {
    // R-004/R-002: username/password come from MqttProperties (env-driven, no
    // hardcoded URL); password is a char[]; automaticReconnect on and
    // cleanSession off (DW-93).
    var options = clientFactory.getConnectionOptions();
    assertThat(options.getServerURIs()).containsExactly("tcp://localhost:1883");
    assertThat(options.getUserName()).isEqualTo("test-user");
    assertThat(new String(options.getPassword())).isEqualTo("s3cret");
    assertThat(options.isAutomaticReconnect()).isTrue();
    // DW-93 (bundle 5): cleanSession(false) so in-flight QoS-1 messages are redelivered
    // on reconnect; the Redis SETNX dedupe gate absorbs duplicates.
    assertThat(options.isCleanSession()).isFalse();
  }

  @Test
  void healthIndicatorBeanIsRegistered() {
    // R-010: a MqttHealthIndicator is present so /actuator/health exposes mqtt.
    assertThat(healthIndicator).isNotNull();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties({MqttProperties.class, com.syncro.config.TelemetryProperties.class})
  static class MqttPropertiesTestConfiguration {

    @Bean
    TelemetryValidationService telemetryValidationService() {
      return mock(TelemetryValidationService.class);
    }

    @Bean
    TelemetryPersistenceService telemetryPersistenceService() {
      return mock(TelemetryPersistenceService.class);
    }

    @Bean
    TelemetryQuarantineService telemetryQuarantineService() {
      return mock(TelemetryQuarantineService.class);
    }

    @Bean
    TelemetryIngestTracker telemetryIngestTracker() {
      return mock(TelemetryIngestTracker.class);
    }

    @Bean
    TelemetryDataQualityTracker telemetryDataQualityTracker() {
      return mock(TelemetryDataQualityTracker.class);
    }
  }
}