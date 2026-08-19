package com.syncro.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

  @Value("${syncro.cache.telemetry.ttl-minutes:5}")
  private long ttlMinutes;

  @Value("${syncro.cache.telemetry.max-size:1000}")
  private long maxSize;

  @Bean
  public CaffeineCacheManager caffeineCacheManager() {
    var manager = new CaffeineCacheManager();
    manager.setCacheNames(List.of("telemetry-plants", "telemetry-machines"));
    manager.setCaffeine(
        Caffeine.newBuilder()
            .expireAfterWrite(Math.max(1, ttlMinutes), TimeUnit.MINUTES)
            .maximumSize(Math.max(1, maxSize)));
    return manager;
  }
}
