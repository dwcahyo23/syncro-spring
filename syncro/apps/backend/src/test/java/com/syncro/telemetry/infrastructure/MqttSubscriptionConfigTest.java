package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.config.MqttProperties;
import com.syncro.config.TimeConfig;
import com.syncro.telemetry.application.MqttTelemetryIngestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;

@SpringBootTest(classes = {
    MqttSubscriptionConfig.class,
    MqttTelemetryIngestHandler.class,
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

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MqttProperties.class)
  static class MqttPropertiesTestConfiguration {
  }
}
