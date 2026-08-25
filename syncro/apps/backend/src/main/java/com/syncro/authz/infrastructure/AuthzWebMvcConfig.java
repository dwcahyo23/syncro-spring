package com.syncro.authz.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.config.AuthzProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the authz interceptor registration into full-application MVC contexts. Kept as a
 * plain {@code @Configuration} (not a WebMvcConfigurer bean itself) so @WebMvcTest slices
 * — which include WebMvcConfigurer/HandlerInterceptor beans but not this config — boot
 * unchanged while the mechanism stays fully testable standalone.
 */
@Configuration
public class AuthzWebMvcConfig {

  @Bean
  WebMvcConfigurer authzWebMvcConfigurer(PolicyDecisionPoint policyDecisionPoint,
      AuthzProperties authzProperties, Clock clock) {
    // Inline Jackson 2 mapper mirrors SecurityConfig's error-writing posture; Boot 4
    // auto-configures Jackson 3, so no com.fasterxml ObjectMapper bean exists.
    return new AuthzWebMvcConfigurer(
        new AuthzInterceptor(policyDecisionPoint, authzProperties, new ObjectMapper(), clock),
        authzProperties);
  }
}
