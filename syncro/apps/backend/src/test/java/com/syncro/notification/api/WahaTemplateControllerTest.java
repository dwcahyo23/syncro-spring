package com.syncro.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.notification.application.WahaTemplateService;
import com.syncro.notification.application.WahaTemplateService.WahaTemplateForbiddenException;
import com.syncro.notification.application.WahaTemplateService.WahaTemplateValidationException;
import com.syncro.notification.domain.WahaTemplate;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(WahaTemplateController.class)
@Import({SecurityConfig.class, WahaTemplateExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class WahaTemplateControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WahaTemplateService templateService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("5.1-API-001 P0 unauthenticated GET returns 401")
  void getTemplateRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/notification/templates"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("5.1-API-002 P0 SUPER_ADMIN GET returns default template")
  void superAdminCanGetTemplate() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var templateId = UUID.randomUUID();
    var now = Instant.parse("2026-08-20T08:00:00Z");
    when(templateService.getActiveTemplate()).thenReturn(
        new WahaTemplate(templateId, WahaTemplate.DEFAULT_KEY,
            "Alert: {machineCode} {sparepartName} at {thresholdPercent}%", now, now));

    mockMvc.perform(get("/api/v1/notification/templates").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(templateId.toString()))
        .andExpect(jsonPath("$.templateKey").value("alert_notification"))
        .andExpect(jsonPath("$.body").value("Alert: {machineCode} {sparepartName} at {thresholdPercent}%"))
        .andExpect(jsonPath("$.updatedAt").value("2026-08-20T08:00:00Z"));
  }

  @Test
  @DisplayName("5.1-API-003 P0 PUT valid template returns 200")
  void superAdminCanUpsertValidTemplate() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var templateId = UUID.randomUUID();
    var now = Instant.parse("2026-08-20T08:00:00Z");
    var body = "Alert: {machineCode} ({machineName}) sparepart {sparepartName} at {thresholdPercent}%";
    when(templateService.upsertTemplate(eq(body), any())).thenReturn(
        new WahaTemplate(templateId, WahaTemplate.DEFAULT_KEY, body, now, now));

    mockMvc.perform(put("/api/v1/notification/templates")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(templateId.toString()))
        .andExpect(jsonPath("$.body").value(body));
  }

  @Test
  @DisplayName("5.1-API-004 P0 PUT with unknown variable returns 400 TEMPLATE_INVALID_VARIABLES")
  void putWithUnknownVariableReturnsBadRequest() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var body = "Alert: {machineCode} has {badVar} issue";
    when(templateService.upsertTemplate(eq(body), any()))
        .thenThrow(new WahaTemplateValidationException(Set.of("{badVar}")));

    mockMvc.perform(put("/api/v1/notification/templates")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEMPLATE_INVALID_VARIABLES"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("5.1-API-005 P0 PUT as AUDITOR returns 403")
  void viewerCannotUpsertTemplate() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(templateService.upsertTemplate(any(), any()))
        .thenThrow(new WahaTemplateForbiddenException());

    mockMvc.perform(put("/api/v1/notification/templates")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"Alert: {machineCode}\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("5.1-API-007 P1 MANAGER_MAINTENANCE role PUT valid template returns 200")
  void manageRoleCanUpsertValidTemplate() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var templateId = UUID.randomUUID();
    var now = Instant.parse("2026-08-20T08:00:00Z");
    var body = "Alert: {machineCode} sparepart {sparepartName} at {thresholdPercent}%";
    when(templateService.upsertTemplate(eq(body), any())).thenReturn(
        new WahaTemplate(templateId, WahaTemplate.DEFAULT_KEY, body, now, now));

    mockMvc.perform(put("/api/v1/notification/templates")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"" + body + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(templateId.toString()))
        .andExpect(jsonPath("$.body").value(body));
  }

  @Test
  @DisplayName("5.1-API-006 P1 PUT with empty body returns 400 VALIDATION_ERROR")
  void putWithEmptyBodyReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(put("/api/v1/notification/templates")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"body\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.body").isNotEmpty());
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}
