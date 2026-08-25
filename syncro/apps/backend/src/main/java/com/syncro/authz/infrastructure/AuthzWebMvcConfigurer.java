package com.syncro.authz.infrastructure;

import com.syncro.config.AuthzProperties;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AuthzInterceptor} on the configured enforced paths.
 *
 * <p>With empty enforced-paths (the 9.3 default) nothing is registered — zero behavior
 * change for existing routes. Registration must also be skipped when the pattern list is
 * empty because an interceptor registered without patterns would match ALL paths.
 */
public class AuthzWebMvcConfigurer implements WebMvcConfigurer {

  /** The degrade contract owns this endpoint — it must never gate itself. */
  public static final String ALLOWED_ACTIONS_PATH = "/api/v1/authz/allowed-actions";

  private final AuthzInterceptor authzInterceptor;
  private final AuthzProperties authzProperties;

  public AuthzWebMvcConfigurer(AuthzInterceptor authzInterceptor, AuthzProperties authzProperties) {
    this.authzInterceptor = authzInterceptor;
    this.authzProperties = authzProperties;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    if (authzProperties.enforcedPaths().isEmpty()) {
      return;
    }
    registry.addInterceptor(authzInterceptor)
        .addPathPatterns(authzProperties.enforcedPaths().toArray(String[]::new))
        .excludePathPatterns(ALLOWED_ACTIONS_PATH);
  }
}
