package com.syncro.report.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.report.api.ReportPrintTemplateDtos.UpsertReportPrintTemplateRequest;
import com.syncro.report.application.ReportPrintTemplateService;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateForbiddenException;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateNotFoundException;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateTooLargeException;
import com.syncro.report.domain.ReportPrintTemplate;
import com.syncro.report.domain.ReportPrintTemplateType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

@WebMvcTest(ReportPrintTemplateController.class)
@Import({SecurityConfig.class, ReportPrintTemplateExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class ReportPrintTemplateControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private ReportPrintTemplateService templateService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("RT-API-001 unauthenticated GET returns 401")
  void getRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/report-templates/WORK_ORDER"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("RT-API-002 any authenticated user (STAFF) can GET the template")
  void staffCanGetTemplate() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    var id = UUID.randomUUID();
    var now = Instant.parse("2026-09-15T08:00:00Z");
    when(templateService.findTemplate(ReportPrintTemplateType.WORK_ORDER)).thenReturn(Optional.of(
        new ReportPrintTemplate(id, ReportPrintTemplateType.WORK_ORDER, "<table><tr><td>{{nomor}}</td></tr></table>",
            null, now)));

    mockMvc.perform(get("/api/v1/report-templates/WORK_ORDER").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.reportType").value("WORK_ORDER"))
        .andExpect(jsonPath("$.bodyHtml").value("<table><tr><td>{{nomor}}</td></tr></table>"))
        .andExpect(jsonPath("$.updatedAt").value("2026-09-15T08:00:00Z"));
  }

  @Test
  @DisplayName("RT-API-003 GET with no stored template returns 404 (client falls back silently)")
  void getMissingTemplateReturns404() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(templateService.findTemplate(ReportPrintTemplateType.PREVENTIVE)).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/v1/report-templates/PREVENTIVE").with(auth(user)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("RT-API-004 SUPER_ADMIN PUT valid template returns 200")
  void superAdminCanUpsert() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var id = UUID.randomUUID();
    var now = Instant.parse("2026-09-15T08:00:00Z");
    var html = "<h1>Laporan</h1><p>{{nomor}} — {{kronologis}}</p>";
    when(templateService.upsertTemplate(eq(ReportPrintTemplateType.WORK_ORDER), eq(html), any()))
        .thenReturn(new ReportPrintTemplate(id, ReportPrintTemplateType.WORK_ORDER, html, null, now));

    mockMvc.perform(put("/api/v1/report-templates/WORK_ORDER")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(new UpsertReportPrintTemplateRequest(html))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.bodyHtml").value(html));
  }

  @Test
  @DisplayName("RT-API-005 MANAGER_MAINTENANCE PUT returns 200")
  void managerCanUpsert() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var now = Instant.parse("2026-09-15T08:00:00Z");
    when(templateService.upsertTemplate(eq(ReportPrintTemplateType.PREVENTIVE), eq("<p>PM</p>"), any()))
        .thenReturn(new ReportPrintTemplate(UUID.randomUUID(), ReportPrintTemplateType.PREVENTIVE, "<p>PM</p>",
            null, now));

    mockMvc.perform(put("/api/v1/report-templates/PREVENTIVE")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bodyHtml\":\"<p>PM</p>\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reportType").value("PREVENTIVE"));
  }

  @Test
  @DisplayName("RT-API-006 matrix: STAFF PUT returns 403")
  void staffPutForbidden() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(templateService.upsertTemplate(any(), any(), any())).thenThrow(new ReportPrintTemplateForbiddenException());

    mockMvc.perform(put("/api/v1/report-templates/WORK_ORDER")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bodyHtml\":\"<p>x</p>\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("RT-API-007 matrix: >200k body returns 400 TEMPLATE_TOO_LARGE")
  void oversizedBodyReturns400() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(templateService.upsertTemplate(any(), any(), any())).thenThrow(new ReportPrintTemplateTooLargeException());

    mockMvc.perform(put("/api/v1/report-templates/WORK_ORDER")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bodyHtml\":\"<p>too big</p>\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEMPLATE_TOO_LARGE"))
        .andExpect(jsonPath("$.fieldErrors.bodyHtml").isNotEmpty());
  }

  @Test
  @DisplayName("RT-API-008 missing bodyHtml property returns 400 VALIDATION_ERROR")
  void nullBodyRejectedByValidation() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(put("/api/v1/report-templates/WORK_ORDER")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.bodyHtml").isNotEmpty());
  }

  @Test
  @DisplayName("RT-API-009 unknown report type returns 400")
  void unknownReportTypeRejected() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/report-templates/DAILY").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("RT-API-010 PUT on missing type re-throws 404 defensively")
  void notFoundMappedTo404() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(templateService.upsertTemplate(any(), any(), any())).thenThrow(new ReportPrintTemplateNotFoundException());

    mockMvc.perform(put("/api/v1/report-templates/WORK_ORDER")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bodyHtml\":\"<p>x</p>\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
  }

  @Test
  @DisplayName("RT-API-011 clear flow: PUT empty bodyHtml passes validation, 200, echoed verbatim; GET reads back ''")
  void emptyBodyRoundTripsAtApi() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var id = UUID.randomUUID();
    var now = Instant.parse("2026-09-15T08:00:00Z");
    when(templateService.upsertTemplate(eq(ReportPrintTemplateType.WORK_ORDER), eq(""), any()))
        .thenReturn(new ReportPrintTemplate(id, ReportPrintTemplateType.WORK_ORDER, "", null, now));

    mockMvc.perform(put("/api/v1/report-templates/WORK_ORDER")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"bodyHtml\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bodyHtml").value(""));

    when(templateService.findTemplate(ReportPrintTemplateType.WORK_ORDER)).thenReturn(Optional.of(
        new ReportPrintTemplate(id, ReportPrintTemplateType.WORK_ORDER, "", null, now)));

    mockMvc.perform(get("/api/v1/report-templates/WORK_ORDER").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bodyHtml").value(""));
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
