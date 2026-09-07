package com.syncro.compliance.api;

import static org.mockito.ArgumentMatchers.any;
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
import com.syncro.compliance.application.CalibrationService;
import com.syncro.compliance.application.CalibrationService.CalibrationInstrumentNotFoundException;
import com.syncro.compliance.application.CalibrationService.CalibrationRecordNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.domain.CalibrationStatus;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
import java.time.LocalDate;
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
 * Story 21-2 calibration API contract tests (21-1 NonConformanceControllerTest
 * pattern): DTO shape, the stable error envelope (401/403/400/404/409 codes),
 * and the 201 create/recalibrate responses. Services are mocked — gates and
 * derivation are proven in the service/integration tests; this locks the
 * serialization + status-code contract.
 */
@WebMvcTest(CalibrationController.class)
@Import({SecurityConfig.class, ComplianceExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class CalibrationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private CalibrationService calibration;

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

  private static CalibrationService.InstrumentView view(UUID id) {
    return new CalibrationService.InstrumentView(id, "CAL-1", "Digital caliper", null, null,
        null, 180, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 8, 28), "B4T Lab",
        CalibrationStatus.EXPIRED, null, Instant.parse("2026-09-06T00:00:00Z"),
        Instant.parse("2026-09-06T00:00:00Z"), 0L);
  }

  private static CalibrationService.RecordView recordView(UUID id, UUID instrumentId) {
    return new CalibrationService.RecordView(id, instrumentId, LocalDate.of(2026, 9, 6),
        LocalDate.of(2027, 3, 5), "B4T Lab", "CERT-1", "garage://cal/cert.pdf", "PASS", null,
        UUID.randomUUID(), Instant.parse("2026-09-06T00:00:00Z"));
  }

  @Test
  @DisplayName("21.2-API-001 P0 POST create returns 201 + serialized instrument view (status uppercase)")
  void createReturns201() throws Exception {
    var id = UUID.randomUUID();
    when(calibration.create(any(), any())).thenReturn(view(id));

    mockMvc.perform(post("/api/v1/calibration-instruments")
            .contentType("application/json")
            .content("""
                {"instrumentCode":"CAL-1","name":"Digital caliper",
                 "calibrationFrequencyDays":180,"nextCalibrationDate":"2026-08-28"}""")
            .with(auth(staff())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.instrumentCode").value("CAL-1"))
        .andExpect(jsonPath("$.status").value("EXPIRED"))
        .andExpect(jsonPath("$.nextCalibrationDate").value("2026-08-28"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-06T00:00:00Z"));
  }

  @Test
  @DisplayName("21.2-API-002 P0 blank code/name or missing dates → 400 VALIDATION_ERROR")
  void blankFieldsValidation() throws Exception {
    mockMvc.perform(post("/api/v1/calibration-instruments")
            .contentType("application/json")
            .content("{\"instrumentCode\":\"\",\"name\":\"  \"}")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.instrumentCode").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.nextCalibrationDate").isNotEmpty());
  }

  @Test
  @DisplayName("21.2-API-003 P0 non-positive frequency → 400 VALIDATION_ERROR")
  void nonPositiveFrequency() throws Exception {
    mockMvc.perform(post("/api/v1/calibration-instruments")
            .contentType("application/json")
            .content("""
                {"instrumentCode":"CAL-1","name":"Caliper","calibrationFrequencyDays":0,
                 "nextCalibrationDate":"2026-12-01"}""")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.calibrationFrequencyDays").isNotEmpty());
  }

  @Test
  @DisplayName("21.2-API-004 P0 duplicate code → 409 DUPLICATE_IDENTIFIER envelope")
  void duplicateIdentifier() throws Exception {
    when(calibration.create(any(), any())).thenThrow(new DuplicateIdentifierException());

    mockMvc.perform(post("/api/v1/calibration-instruments")
            .contentType("application/json")
            .content("""
                {"instrumentCode":"CAL-1","name":"Caliper","calibrationFrequencyDays":180,
                 "nextCalibrationDate":"2026-12-01"}""")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_IDENTIFIER"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("21.2-API-005 P0 unknown/out-of-scope instrument → 404 INSTRUMENT_NOT_FOUND")
  void instrumentNotFound() throws Exception {
    when(calibration.get(any(), any())).thenThrow(new CalibrationInstrumentNotFoundException());

    mockMvc.perform(get("/api/v1/calibration-instruments/{id}", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INSTRUMENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-API-006 P0 unknown record → 404 CALIBRATION_RECORD_NOT_FOUND")
  void recordNotFound() throws Exception {
    when(calibration.getRecord(any(), any(), any()))
        .thenThrow(new CalibrationRecordNotFoundException());

    mockMvc.perform(get("/api/v1/calibration-instruments/{id}/records/{recordId}",
            UUID.randomUUID(), UUID.randomUUID()).with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CALIBRATION_RECORD_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-API-007 P0 unknown plant reference → 404 PLANT_NOT_FOUND")
  void referenceNotFound() throws Exception {
    when(calibration.create(any(), any())).thenThrow(
        new ComplianceReferenceNotFoundException("PLANT_NOT_FOUND",
            "Referenced plant was not found."));

    mockMvc.perform(post("/api/v1/calibration-instruments")
            .contentType("application/json")
            .content("""
                {"instrumentCode":"CAL-1","name":"Caliper","calibrationFrequencyDays":180,
                 "nextCalibrationDate":"2026-12-01"}""")
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-API-008 P0 role gate denial → 403 FORBIDDEN envelope")
  void forbidden() throws Exception {
    when(calibration.list(any(), any())).thenThrow(new ComplianceForbiddenException());

    mockMvc.perform(get("/api/v1/calibration-instruments").with(auth(staff())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("21.2-API-009 P0 recalibrate returns 201 + record view")
  void recalibrateReturns201() throws Exception {
    var instrumentId = UUID.randomUUID();
    when(calibration.recalibrate(any(), any(), any()))
        .thenReturn(recordView(UUID.randomUUID(), instrumentId));

    mockMvc.perform(post("/api/v1/calibration-instruments/{id}/recalibrate", instrumentId)
            .contentType("application/json")
            .content("""
                {"calibrationDate":"2026-09-06","nextCalibrationDate":"2027-03-05",
                 "certificateNumber":"CERT-1"}""")
            .with(auth(staff())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.instrumentId").value(instrumentId.toString()))
        .andExpect(jsonPath("$.certificateNumber").value("CERT-1"));
  }

  @Test
  @DisplayName("21.2-API-010 P0 concurrent update → 409 VERSION_CONFLICT (never 500)")
  void optimisticLockConflict() throws Exception {
    when(calibration.update(any(), any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(
            "CalibrationInstrumentEntity", UUID.randomUUID()));

    mockMvc.perform(patch("/api/v1/calibration-instruments/{id}", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"name\":\"Renamed\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("21.2-API-011 P0 unauthenticated request → 401 AUTHENTICATION_REQUIRED body code")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/calibration-instruments"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("21.2-API-012 P1 invalid ?status= enum → 400 VALIDATION_ERROR envelope")
  void invalidStatusFilter() throws Exception {
    mockMvc.perform(get("/api/v1/calibration-instruments").param("status", "NOT_A_STATUS")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
