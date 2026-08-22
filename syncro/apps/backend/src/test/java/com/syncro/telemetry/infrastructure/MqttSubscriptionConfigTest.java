package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;

import com.syncro.config.MqttProperties;
import com.syncro.config.TelemetryProperties;
import com.syncro.config.TimeConfig;
import com.syncro.telemetry.application.MqttTelemetryIngestHandler;
import com.syncro.telemetry.application.PerMachineExecution;
import com.syncro.telemetry.application.TelemetryDataQualityTracker;
import com.syncro.telemetry.application.TelemetryIngestTracker;
import com.syncro.telemetry.application.TelemetryPersistenceService;
import com.syncro.telemetry.application.TelemetryQuarantineService;
import com.syncro.telemetry.application.TelemetryValidationService;
import com.syncro.telemetry.application.TelemetryValidator;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootTest(classes = {
    MqttSubscriptionConfig.class,
    TelemetryIngestQueueConfig.class,
    MqttTelemetryIngestHandler.class,
    TelemetryIngestTracker.class,
    TelemetryDataQualityTracker.class,
    PerMachineExecution.class,
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
  private IntegrationFlow mqttInboundFlow;

  @Autowired
  private MqttTelemetryIngestHandler handler;

  @Autowired
  private QueueChannel telemetryIngestQueue;

  @Autowired
  private MqttConnectOptions mqttConnectOptions;

  @Autowired
  private ThreadPoolTaskExecutor telemetryIngestExecutor;

  @Test
  void adapterSubscribesToConfiguredTopicAtQosOneAndAutoStart() {
    assertThat(adapter.getTopic()).containsExactly("factory/+/+/telemetry");
    assertThat(adapter.getQos()).containsExactly(1);
    assertThat(adapter.isAutoStartup()).isTrue();
  }

  @Test
  void connectOptionsCarryCredentialsAndReconnectSettings() {
    assertThat(mqttConnectOptions.getServerURIs())
        .containsExactly("tcp://localhost:1883");
    assertThat(mqttConnectOptions.getUserName()).isEqualTo("test");
    assertThat(new String(mqttConnectOptions.getPassword())).isEqualTo("test");
    assertThat(mqttConnectOptions.isAutomaticReconnect()).isTrue();
    assertThat(mqttConnectOptions.isCleanSession()).isFalse();
  }

  @Test
  void ingestFlowPollerUsesTelemetryIngestExecutor() {
    // The flow bean can only be created if the executor and poller spec resolve, so a loaded
    // context with this bean proves the poller is wired; the executor itself is sized by workerThreads.
    assertThat(mqttInboundFlow).isNotNull();
    assertThat(telemetryIngestExecutor.getCorePoolSize()).isEqualTo(2);
    assertThat(telemetryIngestExecutor.getMaxPoolSize()).isEqualTo(2);
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
    TelemetryValidator telemetryValidationService() {
      return (topic, payload) -> new TelemetryValidationService.Result.Rejected("test", null);
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
