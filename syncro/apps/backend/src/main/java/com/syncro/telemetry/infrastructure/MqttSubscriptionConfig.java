package com.syncro.telemetry.infrastructure;

import com.syncro.config.MqttProperties;
import com.syncro.telemetry.application.MqttTelemetryIngestHandler;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;

@Configuration
public class MqttSubscriptionConfig {

  private static final Logger log = LoggerFactory.getLogger(MqttSubscriptionConfig.class);

  @Bean
  MqttConnectOptions mqttConnectOptions(MqttProperties properties) {
    MqttConnectOptions options = new MqttConnectOptions();
    options.setServerURIs(new String[]{"tcp://" + properties.host() + ":" + properties.port()});
    options.setUserName(properties.username());
    options.setPassword(properties.password() == null ? null : properties.password().toCharArray());
    options.setAutomaticReconnect(true);
    // TODO: cleanSession(true) discards in-flight messages on reconnect, which defeats QoS-1
    // at-least-once redelivery semantics. The dedupe SETNX gate in TelemetryPersistenceService
    // only guards against duplicates that actually arrive; messages lost during reconnect are
    // silently dropped. Switch to cleanSession(false) with a stable clientId if at-least-once
    // delivery becomes a hard requirement (DW-23).
    options.setCleanSession(true);
    return options;
  }

  @Bean
  MqttPahoClientFactory mqttPahoClientFactory(MqttConnectOptions options) {
    DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
    factory.setConnectionOptions(options);
    return factory;
  }

  @Bean
  MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter(MqttPahoClientFactory factory, MqttProperties properties) {
    var adapter = new MqttPahoMessageDrivenChannelAdapter(properties.clientId(), factory, properties.topicFilter());
    adapter.setQos(1);
    adapter.setAutoStartup(true);
    log.info("mqtt_subscription_request topic={} qos={}", properties.topicFilter(), 1);
    return adapter;
  }

  @Bean
  IntegrationFlow mqttInboundFlow(MqttPahoMessageDrivenChannelAdapter adapter,
      QueueChannel telemetryIngestQueue,
      MqttTelemetryIngestHandler handler) {
    return IntegrationFlow.from(adapter)
        .channel(telemetryIngestQueue)
        .handle(handler)
        .get();
  }
}
