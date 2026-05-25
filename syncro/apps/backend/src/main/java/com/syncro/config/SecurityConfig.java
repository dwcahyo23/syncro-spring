package com.syncro.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/**"))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/health", "/actuator/health").permitAll()
            .anyRequest().authenticated())
        .build();
  }
}
