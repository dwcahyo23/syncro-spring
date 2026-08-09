package com.syncro.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.InfluxDBClientOptions;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfluxDbConfig {

  @Bean(destroyMethod = "close")
  InfluxDBClient influxDbClient(InfluxProperties properties) {
    OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .writeTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .build();
    var options = InfluxDBClientOptions.builder()
        .url(properties.url())
        .authenticateToken(properties.token().toCharArray())
        .org(properties.org())
        .bucket(properties.bucket())
        .okHttpClient(http.newBuilder())
        .build();
    return InfluxDBClientFactory.create(options);
  }
}
