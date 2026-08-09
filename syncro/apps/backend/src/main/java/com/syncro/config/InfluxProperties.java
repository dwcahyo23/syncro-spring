package com.syncro.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "syncro.influxdb")
public record InfluxProperties(
    @NotBlank String url,
    String username,
    String password,
    @NotBlank String token,
    @NotBlank String org,
    @NotBlank String bucket) {
}
