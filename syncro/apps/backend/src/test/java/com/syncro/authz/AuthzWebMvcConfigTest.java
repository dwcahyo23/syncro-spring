package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.authz.infrastructure.AuthzInterceptor;
import com.syncro.authz.infrastructure.AuthzWebMvcConfigurer;
import com.syncro.config.AuthzProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * The "zero behavior change in 9.3" invariant rests on the empty enforced-paths guard,
 * and the degrade contract rests on the allowed-actions exclusion — both pinned here.
 */
class AuthzWebMvcConfigTest {

  private final AuthzInterceptor interceptor = mock(AuthzInterceptor.class);
  private final InterceptorRegistry registry = mock(InterceptorRegistry.class);
  private final InterceptorRegistration registration = mock(InterceptorRegistration.class);

  @Test
  @DisplayName("9.3-CFG-001 empty enforced-paths registers nothing")
  void emptyPathsRegisterNothing() {
    new AuthzWebMvcConfigurer(interceptor, new AuthzProperties()).addInterceptors(registry);

    verify(registry, never()).addInterceptor(any());
  }

  @Test
  @DisplayName("9.3-CFG-002 populated paths register the interceptor and exclude allowed-actions")
  void populatedPathsExcludeAllowedActions() {
    when(registry.addInterceptor(interceptor)).thenReturn(registration);
    when(registration.addPathPatterns(any(String[].class))).thenReturn(registration);

    new AuthzWebMvcConfigurer(interceptor, new AuthzProperties(List.of("/api/v1/**"), null))
        .addInterceptors(registry);

    verify(registry).addInterceptor(interceptor);
    verify(registration).addPathPatterns("/api/v1/**");
    verify(registration).excludePathPatterns("/api/v1/authz/allowed-actions");
    assertThat(AuthzWebMvcConfigurer.ALLOWED_ACTIONS_PATH).isEqualTo("/api/v1/authz/allowed-actions");
  }

  @Test
  @DisplayName("9.3-CFG-003 explicit empty allowlist is respected (strict fail-deny)")
  void explicitEmptyAllowlistRespected() {
    var props = new AuthzProperties(List.of(), List.of());

    assertThat(props.degradedAllowlist()).isEmpty();
  }

  @Test
  @DisplayName("9.3-CFG-004 unset allowlist falls back to health defaults")
  void unsetAllowlistDefaults() {
    var props = new AuthzProperties();

    assertThat(props.degradedAllowlist()).containsExactly("/api/v1/health", "/actuator/**");
  }
}
