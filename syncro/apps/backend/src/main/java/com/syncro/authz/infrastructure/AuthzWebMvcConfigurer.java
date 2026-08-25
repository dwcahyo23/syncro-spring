package com.syncro.authz.infrastructure;

import com.syncro.config.AuthzProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AuthzInterceptor} on the configured enforced paths.
 *
 * <p>With empty enforced-paths (the 9.3 default) nothing is registered — zero behavior
 * change for existing routes. Registration must also be skipped when the pattern list is
 * empty because an interceptor registered without patterns would match ALL paths.
 *
 * <p>An empty list logs a WARN so a production deployment that forgets
 * {@code SYNCRO_AUTHZ_ENFORCED_PATHS} is never silently running without OPA enforcement.
 */
public class AuthzWebMvcConfigurer implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(AuthzWebMvcConfigurer.class);

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
      log.warn("[AUTHZ] enforced-paths is EMPTY — OPA enforcement is DISABLED for every "
          + "endpoint. Set SYNCRO_AUTHZ_ENFORCED_PATHS to go live.");
      return;
    }
    registry.addInterceptor(authzInterceptor)
        .addPathPatterns(authzProperties.enforcedPaths().toArray(String[]::new))
        .excludePathPatterns(ALLOWED_ACTIONS_PATH);
  }
}
