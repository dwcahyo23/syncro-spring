package com.syncro.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  OpenAPI syncroOpenApi() {
    return new OpenAPI()
        .info(new Info()
            .title("Syncro API")
            .version("v1")
            .description("Syncro backend REST API."));
  }
}
