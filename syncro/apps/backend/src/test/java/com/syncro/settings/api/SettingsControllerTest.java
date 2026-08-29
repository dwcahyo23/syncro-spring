package com.syncro.settings.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.HttpMethod;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.settings.application.LogoService;
import com.syncro.settings.application.LogoService.LogoCommand;
import com.syncro.settings.application.LogoService.LogoValidationException;
import com.syncro.settings.application.LogoService.LogoView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(SettingsController.class)
@Import({SecurityConfig.class, SettingsExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class SettingsControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private LogoService logos;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }

  @Test
  @DisplayName("14.3-LOGO-001 P0 GET logo returns the presigned URL when configured")
  void getLogoOk() throws Exception {
    when(logos.get()).thenReturn(new LogoView("settings/logo/abc.png", "https://garage/presigned"));

    mockMvc.perform(get("/api/v1/settings/logo")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectKey").value("settings/logo/abc.png"))
        .andExpect(jsonPath("$.presignedUrl").value("https://garage/presigned"));
  }

  @Test
  @DisplayName("14.3-LOGO-002 P0 GET logo returns null fields when no logo is configured")
  void getLogoAbsent() throws Exception {
    when(logos.get()).thenReturn(new LogoView(null, null));

    mockMvc.perform(get("/api/v1/settings/logo")
        .with(auth(user(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectKey").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.presignedUrl").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  @DisplayName("14.3-LOGO-003 P0 SUPER_ADMIN uploads a logo and receives 200 with the view")
  void uploadLogoOk() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(logos.replace(any(LogoCommand.class)))
        .thenReturn(new LogoView("settings/logo/new.png", "https://garage/presigned"));

    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/settings/logo")
            .file(new MockMultipartFile("data", "logo.png", "image/png", new byte[] {1}))
            .param("filename", "logo.png")
            .param("contentType", "image/png")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectKey").value("settings/logo/new.png"));
  }

  @Test
  @DisplayName("14.3-LOGO-004 P0 a non-SUPER_ADMIN upload is forbidden")
  void uploadLogoForbidden() throws Exception {
    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/settings/logo")
            .file(new MockMultipartFile("data", "logo.png", "image/png", new byte[] {1}))
            .param("filename", "logo.png")
            .param("contentType", "image/png")
            .with(auth(user(ApplicationRole.MAINTENANCE_LEADER))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("14.3-LOGO-005 P0 validation error maps to 400 VALIDATION_ERROR")
  void uploadLogoValidationError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(logos.replace(any(LogoCommand.class)))
        .thenThrow(new LogoValidationException(Map.of("contentType", "Content type must be one of image/jpeg, image/png, or image/webp.")));

    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/settings/logo")
            .file(new MockMultipartFile("data", "logo.txt", "text/plain", new byte[] {1}))
            .param("filename", "logo.txt")
            .param("contentType", "text/plain")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("14.3-LOGO-006 P0 a missing multipart parameter maps to 400 VALIDATION_ERROR")
  void uploadLogoMissingParam() throws Exception {
    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/settings/logo")
            .file(new MockMultipartFile("data", "logo.png", "image/png", new byte[] {1}))
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.filename").value("This value is required."));
  }
}
