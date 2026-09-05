package com.syncro.compliance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.compliance.application.EightDReportService;
import com.syncro.compliance.application.NonConformanceService;
import com.syncro.compliance.application.EightDReportService.EightDReportNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.application.NonConformanceService.NcView;
import com.syncro.compliance.application.NonConformanceService.NonConformanceNotFoundException;
import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Story 21-1 API contract tests (SparepartAlertControllerTest pattern): DTO shape,
 * the stable error envelope (401/403/400/404/409 codes), and the 201 create responses.
 * Services are mocked — gates/transitions are proven in the service and integration
 * tests; this locks the serialization + status-code contract.
 */
@WebMvcTest(NonConformanceController.class)
@Import({SecurityConfig.class, ComplianceExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class NonConformanceControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private NonConformanceService nonConformances;

  @MockitoBean
  private EightDReportService eightDReports;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    var principal = new UsernamePasswordAuthenticationToken(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
    return SecurityMockMvcRequestPostProcessors.authentication(principal);
  }

  private static AuthenticatedUser staff() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "staff@syncro.test",
        ApplicationRole.STAFF_MAINTENANCE);
  }

  private static NcView view(UUID id) {
    return new NcView(id, null, "WO-1", null, "NC-1", "Dimensional drift", null, null, null,
        NcStatus.OPEN, NcSeverity.MAJOR, null, null,
        Instant.parse("2026-09-04T00:00:00Z"), Instant.parse("2026-09-04T00:00:00Z"));
  }

  @Test
  @DisplayName("21.1-API-001 P0 POST create returns 201 + serialized NC view (severity/status uppercase)")
  void createReturns201() throws Exception {
    var id = UUID.randomUUID();
    when(nonConformances.create(any(), any())).thenReturn(view(id));

    mockMvc.perform(post("/api/v1/non-conformances")
            .contentType("application/json")
            .content("""
                {"ncNumber":"NC-1","description":"Dimensional drift","severity":"MAJOR",
                 "workOrderId":"WO-1"}""")
            .with(auth(staff())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.ncNumber").value("NC-1"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.severity").value("MAJOR"))
        .andExpect(jsonPath("$.workOrderId").value("WO-1"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-04T00:00:00Z"));
  }

  @Test
  @DisplayName("21.1-API-002 P0 blank ncNumber/description → 400 VALIDATION_ERROR with fieldErrors")
  void blankFieldsValidation() throws Exception {
    mockMvc.perform(post("/api/v1/non-conformances")
            .contentType("application/json")
            .content("{\"ncNumber\":\"\",\"description\":\"  \"}")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.ncNumber").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.description").isNotEmpty());
  }

  @Test
  @DisplayName("21.1-API-003 P0 duplicate number → 409 DUPLICATE_IDENTIFIER envelope")
  void duplicateIdentifier() throws Exception {
    when(nonConformances.create(any(), any())).thenThrow(new DuplicateIdentifierException());

    mockMvc.perform(post("/api/v1/non-conformances")
            .contentType("application/json")
            .content("{\"ncNumber\":\"NC-1\",\"description\":\"Drift\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_IDENTIFIER"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("21.1-API-004 P0 illegal transition → 409 INVALID_STATE_TRANSITION")
  void invalidTransition() throws Exception {
    when(nonConformances.update(any(), any(), any())).thenThrow(new InvalidStateTransitionException());

    mockMvc.perform(patch("/api/v1/non-conformances/{id}", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"status\":\"VERIFIED\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("21.1-API-005 P0 role gate denial → 403 FORBIDDEN envelope")
  void forbidden() throws Exception {
    when(nonConformances.list(any(), eq(null))).thenThrow(new ComplianceForbiddenException());

    mockMvc.perform(get("/api/v1/non-conformances").with(auth(staff())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("21.1-API-006 P0 unknown/out-of-scope NC → 404 NON_CONFORMANCE_NOT_FOUND")
  void notFound() throws Exception {
    when(nonConformances.get(any(), any())).thenThrow(new NonConformanceNotFoundException());

    mockMvc.perform(get("/api/v1/non-conformances/{id}", UUID.randomUUID()).with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NON_CONFORMANCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.1-API-007 P0 unauthenticated request → 401 AUTHENTICATION_REQUIRED")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/non-conformances"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("21.1-API-008 P1 second 8D for one NC → 409 EIGHT_D_CONFLICT")
  void eightDConflict() throws Exception {
    when(eightDReports.create(any(), any(), any()))
        .thenThrow(new EightDReportService.EightDConflictException());

    mockMvc.perform(post("/api/v1/non-conformances/{id}/eight-d", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"reportNumber\":\"8D-2\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EIGHT_D_CONFLICT"));
  }

  @Test
  @DisplayName("21.1-API-009 P1 concurrent update → 409 VERSION_CONFLICT (never 500)")
  void optimisticLockConflict() throws Exception {
    when(nonConformances.update(any(), any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(
            "NonConformanceEntity", UUID.randomUUID()));

    mockMvc.perform(patch("/api/v1/non-conformances/{id}", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"description\":\"Race\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("21.1-API-009b P1 unknown reference → 404 with the stable code")
  void referenceNotFound() throws Exception {
    when(nonConformances.create(any(), any())).thenThrow(
        new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
            "Referenced machine was not found."));

    mockMvc.perform(post("/api/v1/non-conformances")
            .contentType("application/json")
            .content("{\"ncNumber\":\"NC-1\",\"description\":\"Drift\"}")
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.1-API-009c P1 missing 8D report → 404 EIGHT_D_REPORT_NOT_FOUND")
  void eightDReportNotFound() throws Exception {
    when(eightDReports.getForNc(any(), any())).thenThrow(new EightDReportNotFoundException());

    mockMvc.perform(get("/api/v1/non-conformances/{id}/eight-d", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EIGHT_D_REPORT_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.1-API-010 P1 verify-effectiveness verdict missing → 400 VALIDATION_ERROR")
  void verifyRequiresVerdict() throws Exception {
    mockMvc.perform(post("/api/v1/non-conformances/{id}/eight-d/verify-effectiveness",
            UUID.randomUUID())
            .contentType("application/json")
            .content("{}")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.verdict").isNotEmpty());
  }
}
