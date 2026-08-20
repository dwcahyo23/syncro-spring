package com.syncro.config;

import com.influxdb.v3.client.InfluxDBClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfluxDbConfig {

  @Bean(destroyMethod = "close")
  InfluxDBClient influxDbClient(InfluxProperties properties) throws Exception {
    return InfluxDBClient.getInstance(
        properties.url(),
        properties.token().toCharArray(),
        properties.database());
  }
}
