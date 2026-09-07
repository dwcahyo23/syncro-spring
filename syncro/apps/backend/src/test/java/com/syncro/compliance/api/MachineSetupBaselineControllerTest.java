package com.syncro.compliance.api;

import static org.mockito.ArgumentMatchers.any;
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
import com.syncro.compliance.application.MachineSetupBaselineService;
import com.syncro.compliance.application.MachineSetupBaselineService.MachineSetupBaselineNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Story 21-3 baseline API contract tests (21-2 CalibrationControllerTest
 * pattern): DTO shape, the stable error envelope (401/403/400/404/409 codes),
 * and the 201 create/activate responses. Services are mocked — version
 * assignment and supersede are proven in the service/integration tests; this
 * locks the serialization + status-code contract.
 */
@WebMvcTest(MachineSetupBaselineController.class)
@Import({SecurityConfig.class, ComplianceExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class MachineSetupBaselineControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MachineSetupBaselineService baselines;

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

  private static MachineSetupBaselineService.BaselineView view(UUID id, int version,
      boolean active) {
    return new MachineSetupBaselineService.BaselineView(id, UUID.randomUUID(), null, version,
        Map.of("tolerances", Map.of("clampPressureBar", 6.5), "references", List.of("SOP-12")),
        null, null, active, Instant.parse("2026-09-07T00:00:00Z"),
        Instant.parse("2026-09-07T00:00:00Z"), 0L);
  }

  @Test
  @DisplayName("21.3-API-001 P0 POST create returns 201 + serialized baseline view (version/active)")
  void createReturns201() throws Exception {
    var id = UUID.randomUUID();
    when(baselines.create(any(), any())).thenReturn(view(id, 1, true));

    mockMvc.perform(post("/api/v1/machine-setup-baselines")
            .contentType("application/json")
            .content("""
                {"machineId":"7b7c6d5e-1111-2222-3333-444455556666",
                 "parameters":{"tolerances":{"clampPressureBar":6.5}}}""")
            .with(auth(staff())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.parameters.tolerances.clampPressureBar").value(6.5))
        .andExpect(jsonPath("$.createdAt").value("2026-09-07T00:00:00Z"));
  }

  @Test
  @DisplayName("21.3-API-002 P0 missing machineId/parameters → 400 VALIDATION_ERROR")
  void blankFieldsValidation() throws Exception {
    mockMvc.perform(post("/api/v1/machine-setup-baselines")
            .contentType("application/json")
            .content("{}")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.machineId").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.parameters").isNotEmpty());
  }

  @Test
  @DisplayName("21.3-API-003 P0 unknown machine → 404 MACHINE_NOT_FOUND")
  void machineNotFound() throws Exception {
    when(baselines.create(any(), any())).thenThrow(
        new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
            "Referenced machine was not found."));

    mockMvc.perform(post("/api/v1/machine-setup-baselines")
            .contentType("application/json")
            .content("""
                {"machineId":"7b7c6d5e-1111-2222-3333-444455556666","parameters":{"a":1}}""")
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-API-004 P0 unknown/out-of-scope baseline → 404 BASELINE_NOT_FOUND")
  void baselineNotFound() throws Exception {
    when(baselines.get(any(), any())).thenThrow(new MachineSetupBaselineNotFoundException());

    mockMvc.perform(get("/api/v1/machine-setup-baselines/{id}", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("BASELINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-API-005 P0 activate returns 200 + flipped view")
  void activateReturns200() throws Exception {
    var id = UUID.randomUUID();
    when(baselines.activate(any(), any())).thenReturn(view(id, 2, true));

    mockMvc.perform(post("/api/v1/machine-setup-baselines/{id}/activate", id)
            .with(auth(staff())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  @DisplayName("21.3-API-006 P0 role gate denial on a mutation → 403 FORBIDDEN envelope (F4)")
  void forbidden() throws Exception {
    when(baselines.create(any(), any())).thenThrow(new ComplianceForbiddenException());

    mockMvc.perform(post("/api/v1/machine-setup-baselines")
            .contentType("application/json")
            .content("""
                {"machineId":"7b7c6d5e-1111-2222-3333-444455556666","parameters":{"a":1}}""")
            .with(auth(staff())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("21.3-API-006b P0 GET by id returns 200 + version/lockVersion fields (VG-8)")
  void getReturns200WithVersionFields() throws Exception {
    var id = UUID.randomUUID();
    when(baselines.get(any(), any())).thenReturn(view(id, 3, true));

    mockMvc.perform(get("/api/v1/machine-setup-baselines/{id}", id).with(auth(staff())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.lockVersion").value(0));
  }

  @Test
  @DisplayName("21.3-API-007 P0 concurrent update → 409 VERSION_CONFLICT (never 500)")
  void optimisticLockConflict() throws Exception {
    when(baselines.activate(any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(
            "MachineSetupBaselineEntity", UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/machine-setup-baselines/{id}/activate", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("21.3-API-008 P0 unauthenticated request → 401 AUTHENTICATION_REQUIRED body code")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/machine-setup-baselines"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }
}
