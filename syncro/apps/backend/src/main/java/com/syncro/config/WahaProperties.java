package com.syncro.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "syncro.waha")
public record WahaProperties(
    @NotBlank String url,
    @NotBlank String apiKey) {
}
