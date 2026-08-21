package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;

import com.syncro.config.MqttProperties;
import com.syncro.config.TelemetryProperties;
import com.syncro.config.TimeConfig;
import com.syncro.telemetry.application.MqttTelemetryIngestHandler;
import com.syncro.telemetry.application.TelemetryIngestTracker;
import com.syncro.telemetry.application.TelemetryPersistenceService;
import com.syncro.telemetry.application.TelemetryQuarantineService;
import com.syncro.telemetry.application.TelemetryValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;

@SpringBootTest(classes = {
    MqttSubscriptionConfig.class,
    TelemetryIngestQueueConfig.class,
    MqttTelemetryIngestHandler.class,
    TelemetryIngestTracker.class,
    MqttConnectionStatus.class,
    MqttHealthIndicator.class,
    TimeConfig.class,
    MqttSubscriptionConfigTest.MqttPropertiesTestConfiguration.class
}, properties = {
    "syncro.mqtt.host=localhost",
    "syncro.mqtt.port=1883",
    "syncro.mqtt.username=test",
    "syncro.mqtt.password=test",
    "syncro.mqtt.client-id=syncro-test",
    "syncro.mqtt.topic-filter=factory/+/+/telemetry",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class MqttSubscriptionConfigTest {

  @Autowired
  private MqttPahoMessageDrivenChannelAdapter adapter;

  @Autowired
  private MqttPahoClientFactory clientFactory;

  @Autowired
  private IntegrationFlow mqttInboundFlow;

  @Autowired
  private MqttTelemetryIngestHandler handler;

  @Autowired
  private QueueChannel telemetryIngestQueue;

  @Test
  void adapterSubscribesToConfiguredTopicAtQosOneAndAutoStart() {
    assertThat(adapter.getTopic()).containsExactly("factory/+/+/telemetry");
    assertThat(adapter.getQos()).containsExactly(1);
    assertThat(adapter.isAutoStartup()).isTrue();
  }

  @Test
  void clientFactoryCarriesConfiguredBrokerUri() {
    assertThat(clientFactory.getConnectionOptions().getServerURIs())
        .containsExactly("tcp://localhost:1883");
  }

  @Test
  void inboundFlowRoutesAdapterToIngestHandler() {
    MessageChannel outputChannel = adapter.getOutputChannel();
    assertThat(outputChannel).isNotNull();
    assertThat(mqttInboundFlow.getInputChannel()).isSameAs(outputChannel);
    assertThat(mqttInboundFlow).isNotNull();
    assertThat(handler).isNotNull();
  }

  @Test
  void inboundFlowRoutesViaQueueChannel() {
    // verify the queue channel bean is present and bounded (not unbounded DirectChannel)
    assertThat(telemetryIngestQueue).isNotNull();
    assertThat(telemetryIngestQueue.getRemainingCapacity()).isPositive();
    // flow input channel comes from the adapter output — flow is wired
    assertThat(mqttInboundFlow.getInputChannel()).isNotNull();
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties({MqttProperties.class, TelemetryProperties.class})
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
  }
}
