package com.syncro.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j wiring for the WAHA circuit breaker.
 *
 * <p>Provides the {@link CircuitBreakerRegistry} bean consumed by {@code WahaClient}. The
 * per-instance {@code CircuitBreakerConfig} is built programmatically inside {@code WahaClient}
 * from {@link WahaResilienceProperties} (single source of truth), so only the registry needs to
 * be exposed here.
 */
@Configuration
public class ResilienceConfig {

  @Bean
  CircuitBreakerRegistry circuitBreakerRegistry() {
    return CircuitBreakerRegistry.ofDefaults();
  }
}
