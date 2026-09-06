package com.syncro.maintenance.preventive.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportHeaderView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportItemView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportSignatureView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportSignaturesView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PmExecutionReportView;
import com.syncro.maintenance.preventive.application.PmChecklistService;
import com.syncro.maintenance.preventive.application.PmExecutionReportService;
import com.syncro.maintenance.preventive.application.PmExecutionService;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionItemView;
import com.syncro.maintenance.preventive.application.PmExecutionService.ExecutionView;
import com.syncro.maintenance.preventive.application.PmWorkOrderService;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * Story 19-5 MockMvc contract test: DTO shape, status codes, and every new
 * exception→HTTP mapping for the /api/v1/pm-executions surface
 * (PmWorkOrderControllerTest pattern — the service is stubbed to throw and the
 * handler asserts status + code).
 */
@WebMvcTest(PmExecutionController.class)
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PmExecutionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PmExecutionService executions;

  @MockitoBean
  private PmExecutionReportService reports;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID EXEC_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID WO_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID ITEM_ID = UUID.fromString("ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb");
  private static final UUID TECH_ID = UUID.fromString("44444444-3333-2222-1111-000000000000");
  private static final UUID MACHINE_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");
  private static final UUID SIG_ID = UUID.fromString("55555555-6666-7777-8888-999999999999");
  private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

  // -------------------------------------------------------------------------
  // Happy paths
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-API-001 P0 start returns 201 with execution view")
  void start() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.start(eq(user), eq(WO_ID))).thenReturn(view(false, false, false, 0, null));

    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pmWoId\":\"" + WO_ID + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(EXEC_ID.toString()))
        .andExpect(jsonPath("$.pmWoId").value(WO_ID.toString()))
        .andExpect(jsonPath("$.technicianId").value(TECH_ID.toString()))
        .andExpect(jsonPath("$.startedAt").exists())
        .andExpect(jsonPath("$.completedAt").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.hasNgItems").value(false))
        .andExpect(jsonPath("$.ngCount").value(0))
        .andExpect(jsonPath("$.items").isArray());
  }

  @Test
  @DisplayName("19.5-API-002 P0 start missing pmWoId → 400 VALIDATION_ERROR")
  void startMissingBody() throws Exception {
    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(ApplicationRole.TECHNICIAN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.pmWoId").exists());
  }

  @Test
  @DisplayName("19.5-API-003 P0 fill returns 200 with snapshotted item view")
  void fill() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.fill(eq(user), eq(EXEC_ID), eq(ITEM_ID), any()))
        .thenReturn(itemView());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/items/{itemId}/fill", EXEC_ID, ITEM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actualValue\":5.2,\"ng\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ITEM_ID.toString()))
        .andExpect(jsonPath("$.executionId").value(EXEC_ID.toString()))
        .andExpect(jsonPath("$.checklistItemId").value(ITEM_ID.toString()))
        .andExpect(jsonPath("$.parameterText").value("Oil pressure"))
        .andExpect(jsonPath("$.inputType").value("MEASUREMENT"))
        .andExpect(jsonPath("$.actualValue").value(5.2))
        .andExpect(jsonPath("$.isNg").value(false))
        .andExpect(jsonPath("$.filledAt").exists());
  }

  @Test
  @DisplayName("19.5-API-004 P0 complete returns 200 with rollup + finding link")
  void complete() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.complete(eq(user), eq(EXEC_ID)))
        .thenReturn(view(true, false, true, 2, "WO-2609-00007"));

    mockMvc.perform(post("/api/v1/pm-executions/{id}/complete", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completedAt").exists())
        .andExpect(jsonPath("$.hasNgItems").value(true))
        .andExpect(jsonPath("$.ngCount").value(2))
        .andExpect(jsonPath("$.findingWoId").value("WO-2609-00007"));
  }

  @Test
  @DisplayName("19.5-API-005 P0 verify returns 200 with spv stamps; body optional")
  void verify() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(executions.verify(eq(user), eq(EXEC_ID), eq(SIG_ID), any(), any()))
        .thenReturn(view(true, true, false, 0, null));

    mockMvc.perform(post("/api/v1/pm-executions/{id}/verify", EXEC_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"spvSignatureId\":\"" + SIG_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spvVerifierId").exists());
  }

  @Test
  @DisplayName("19.5-API-006 P0 verify without body passes null signature")
  void verifyWithoutBody() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(executions.verify(eq(user), eq(EXEC_ID), isNull(), any(), any()))
        .thenReturn(view(true, true, false, 0, null));

    mockMvc.perform(post("/api/v1/pm-executions/{id}/verify", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("22.3-API-001 P0 verify wires spvSignatureId + first XFF hop + User-Agent into the service (review 22-3 P3)")
  void verifyWiresEnrichmentFields() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(executions.verify(eq(user), eq(EXEC_ID), eq(SIG_ID), any(), any()))
        .thenReturn(view(true, true, false, 0, null));

    mockMvc.perform(post("/api/v1/pm-executions/{id}/verify", EXEC_ID)
            .with(auth(user))
            .header("X-Forwarded-For", "203.0.113.7, 10.0.0.2")
            .header("User-Agent", "JUnit-Verify-Agent")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"spvSignatureId\":\"" + SIG_ID + "\"}"))
        .andExpect(status().isOk());

    org.mockito.Mockito.verify(executions).verify(eq(user), eq(EXEC_ID), eq(SIG_ID),
        org.mockito.ArgumentMatchers.eq("203.0.113.7"),
        org.mockito.ArgumentMatchers.eq("JUnit-Verify-Agent"));
  }

  @Test
  @DisplayName("19.5-API-007 P0 list returns 200 with filters")
  void list() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(executions.list(eq(user), eq(WO_ID), eq(TECH_ID)))
        .thenReturn(List.of(view(false, false, false, 0, null)));

    mockMvc.perform(get("/api/v1/pm-executions")
            .param("pmWoId", WO_ID.toString())
            .param("technicianId", TECH_ID.toString())
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(EXEC_ID.toString()));
  }

  @Test
  @DisplayName("19.5-API-008 P0 get returns 200 with items array")
  void getExecution() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(executions.get(eq(user), eq(EXEC_ID))).thenReturn(view(false, false, false, 0, null));

    mockMvc.perform(get("/api/v1/pm-executions/{id}", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(EXEC_ID.toString()))
        .andExpect(jsonPath("$.items[0].sequence").value(1));
  }

  // -------------------------------------------------------------------------
  // Error paths — every new exception→HTTP mapping (stubbed service throws)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.5-API-009 P0 unknown execution → 404 PM_EXECUTION_NOT_FOUND")
  void getNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(executions.get(eq(user), eq(EXEC_ID)))
        .thenThrow(new PmExecutionService.PmExecutionNotFoundException());

    mockMvc.perform(get("/api/v1/pm-executions/{id}", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_EXECUTION_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.5-API-010 P0 unknown work order on start → 404 PM_WORK_ORDER_NOT_FOUND")
  void startWorkOrderNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.start(eq(user), eq(WO_ID)))
        .thenThrow(new PmWorkOrderService.PmWorkOrderNotFoundException());

    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pmWoId\":\"" + WO_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_WORK_ORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.5-API-011 P0 duplicate start → 409 EXECUTION_ALREADY_EXISTS")
  void startAlreadyExists() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.start(eq(user), eq(WO_ID)))
        .thenThrow(new PmExecutionService.ExecutionAlreadyExistsException());

    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pmWoId\":\"" + WO_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EXECUTION_ALREADY_EXISTS"));
  }

  @Test
  @DisplayName("19.5-API-012 P0 WO not IN_PROGRESS on start → 409 INVALID_EXECUTION_STATE")
  void startInvalidState() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.start(eq(user), eq(WO_ID)))
        .thenThrow(new PmExecutionService.InvalidExecutionStateException());

    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pmWoId\":\"" + WO_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_STATE"));
  }

  @Test
  @DisplayName("19.5-API-013 P0 fill after complete → 409 INVALID_EXECUTION_TRANSITION")
  void fillInvalidTransition() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.fill(eq(user), eq(EXEC_ID), eq(ITEM_ID), any()))
        .thenThrow(new PmExecutionService.InvalidExecutionTransitionException());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/items/{itemId}/fill", EXEC_ID, ITEM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actualValue\":5.2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_TRANSITION"));
  }

  @Test
  @DisplayName("19.5-API-014 P0 fill validation → 400 VALIDATION_ERROR with fieldErrors")
  void fillValidation() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.fill(eq(user), eq(EXEC_ID), eq(ITEM_ID), any()))
        .thenThrow(new PmExecutionService.ExecutionValidationException(
            Map.of("actualValue", "actualValue is required for MEASUREMENT items.")));

    mockMvc.perform(post("/api/v1/pm-executions/{id}/items/{itemId}/fill", EXEC_ID, ITEM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.actualValue").exists());
  }

  @Test
  @DisplayName("19.5-API-015 P0 foreign checklist item on fill → 404 PM_CHECKLIST_ITEM_NOT_FOUND")
  void fillItemNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.fill(eq(user), eq(EXEC_ID), eq(ITEM_ID), any()))
        .thenThrow(new PmChecklistService.PmChecklistItemNotFoundException());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/items/{itemId}/fill", EXEC_ID, ITEM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ok\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKLIST_ITEM_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.5-API-016 P0 wrong technician on complete → 403 FORBIDDEN")
  void completeForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.complete(eq(user), eq(EXEC_ID)))
        .thenThrow(new PmExecutionService.PmExecutionForbiddenException());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/complete", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.5-API-017 P0 double verify → 409 INVALID_EXECUTION_TRANSITION")
  void verifyConflict() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(executions.verify(eq(user), eq(EXEC_ID), any(), any(), any()))
        .thenThrow(new PmExecutionService.InvalidExecutionTransitionException());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/verify", EXEC_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_TRANSITION"));
  }

  @Test
  @DisplayName("19.5-API-020 P0 start by a non-assignee → 403 FORBIDDEN")
  void startForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.start(eq(user), eq(WO_ID)))
        .thenThrow(new PmExecutionService.PmExecutionForbiddenException());

    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pmWoId\":\"" + WO_ID + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.5-API-021 P0 fill by a non-technician → 403 FORBIDDEN")
  void fillForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.fill(eq(user), eq(EXEC_ID), eq(ITEM_ID), any()))
        .thenThrow(new PmExecutionService.PmExecutionForbiddenException());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/items/{itemId}/fill", EXEC_ID, ITEM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"actualValue\":5.2}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.5-API-022 P0 verify by a non-leader (TECHNICIAN) → 403 FORBIDDEN")
  void verifyForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(executions.verify(eq(user), eq(EXEC_ID), any(), any(), any()))
        .thenThrow(new PmExecutionService.PmExecutionForbiddenException());

    mockMvc.perform(post("/api/v1/pm-executions/{id}/verify", EXEC_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.5-API-018 P0 malformed UUID path value → 400 INVALID_PATH_VALUE")
  void getMalformedPathValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-executions/{id}", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  // -------------------------------------------------------------------------
  // Story 19-6: print report endpoint
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.6-API-001 P0 report returns 200 with header/items/signature aggregate shape")
  void report() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(reports.get(eq(user), eq(EXEC_ID))).thenReturn(reportView());

    mockMvc.perform(get("/api/v1/pm-executions/{id}/report", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.header.executionId").value(EXEC_ID.toString()))
        .andExpect(jsonPath("$.header.machineId").value(MACHINE_ID.toString()))
        .andExpect(jsonPath("$.header.machineCode").value("GM1"))
        .andExpect(jsonPath("$.header.plantCode").value("JBF19"))
        .andExpect(jsonPath("$.header.status").value("VERIFIED"))
        .andExpect(jsonPath("$.items[0].sequence").value(1))
        .andExpect(jsonPath("$.items[0].ngPhotoPresignedUrl")
            .value("https://garage/ng.jpg"))
        .andExpect(jsonPath("$.signatures.technician.displayName").value("Tech One"))
        .andExpect(jsonPath("$.signatures.spv")
            .value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  @DisplayName("19.6-API-002 P0 report out-of-scope → 403 FORBIDDEN")
  void reportForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(reports.get(eq(user), eq(EXEC_ID)))
        .thenThrow(new PmExecutionService.PmExecutionForbiddenException());

    mockMvc.perform(get("/api/v1/pm-executions/{id}/report", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.6-API-003 P0 report unknown execution → 404 PM_EXECUTION_NOT_FOUND")
  void reportNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(reports.get(eq(user), eq(EXEC_ID)))
        .thenThrow(new PmExecutionService.PmExecutionNotFoundException());

    mockMvc.perform(get("/api/v1/pm-executions/{id}/report", EXEC_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_EXECUTION_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.6-API-004 P0 report malformed UUID → 400 INVALID_PATH_VALUE")
  void reportMalformedPathValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-executions/{id}/report", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("19.5-API-019 P0 malformed UUID body field → 400 VALIDATION_ERROR")
  void startMalformedBody() throws Exception {
    mockMvc.perform(post("/api/v1/pm-executions/start")
            .with(auth(ApplicationRole.TECHNICIAN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pmWoId\":\"not-a-uuid\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private static ExecutionView view(boolean completed, boolean verified, boolean hasNg,
      int ngCount, String findingWoId) {
    return new ExecutionView(EXEC_ID, WO_ID, null, TECH_ID,
        verified ? SIG_ID : null, null, null, verified ? SIG_ID : null,
        verified ? NOW : null, NOW, completed ? NOW : null, hasNg, ngCount, findingWoId,
        NOW, NOW, List.of(itemView()));
  }

  private static PmExecutionReportView reportView() {
    var header = new PmExecutionReportHeaderView(EXEC_ID, WO_ID, MACHINE_ID, "GM1", "Press A",
        "JBF19", java.time.LocalDate.of(2027, 1, 15), "MONTHLY", "Monthly", 1, NOW, NOW,
        "VERIFIED", true, 1, null);
    var item = new PmExecutionReportItemView(1, "Hydraulics", "Oil pressure", "Gauge",
        "MEASUREMENT", false, "bar", new BigDecimal("3.0"), new BigDecimal("5.0"),
        new BigDecimal("7.0"), new BigDecimal("9.5"), null, true, "Weep at seal",
        "https://garage/ng.jpg", false, null);
    var tech = new PmExecutionReportSignatureView(TECH_ID, "Tech One",
        "https://garage/tech.png", NOW);
    return new PmExecutionReportView(header, List.of(item),
        new PmExecutionReportSignaturesView(tech, null));
  }

  private static ExecutionItemView itemView() {
    return new ExecutionItemView(ITEM_ID, EXEC_ID, ITEM_ID, 1, "Hydraulics", "Oil pressure",
        "Gauge", PmItemInputType.MEASUREMENT, false, "bar", new BigDecimal("3.0"),
        new BigDecimal("5.0"), new BigDecimal("7.0"), new BigDecimal("5.2"), null, false,
        null, null, false, null, null, NOW, NOW, NOW);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(
        new UsernamePasswordAuthenticationToken(user, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }

  private static RequestPostProcessor auth(ApplicationRole role) {
    return auth(user(role));
  }
}
