package com.syncro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "syncro.mqtt")
public record MqttProperties(
    String host,
    int port,
    String username,
    String password,
    String clientId,
    String topicFilter) {
}
