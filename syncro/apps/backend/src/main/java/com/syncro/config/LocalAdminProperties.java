package com.syncro.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "syncro.auth.local-admin")
public record LocalAdminProperties(
    boolean enabled,
    @NotBlank String loginIdentifier,
    @NotBlank String password) {
}
