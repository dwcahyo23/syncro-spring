package com.syncro;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestJsonConfig {
  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
