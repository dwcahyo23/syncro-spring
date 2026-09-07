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
import com.syncro.compliance.application.EquipmentChangeNoticeService;
import com.syncro.compliance.application.EquipmentChangeNoticeService.EcnView;
import com.syncro.compliance.application.EquipmentChangeNoticeService.EquipmentChangeNoticeNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.domain.EcnStatus;
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
 * Story 21-2 ECN API contract tests (21-1 NonConformanceControllerTest pattern):
 * DTO shape, the stable error envelope (401/403/400/404/409 codes), and the
 * lifecycle endpoints' 200 responses. Services are mocked — gates/transitions
 * are proven in the service/integration tests; this locks the serialization +
 * status-code contract.
 */
@WebMvcTest(EquipmentChangeNoticeController.class)
@Import({SecurityConfig.class, ComplianceExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class EquipmentChangeNoticeControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private EquipmentChangeNoticeService notices;

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

  private static EcnView view(UUID id, EcnStatus status) {
    return new EcnView(id, "ECN-1", UUID.randomUUID(), "Retrofit guard", "Add light curtain",
        "IMPROVEMENT", "Near-miss", status, null, null, null, null, null, null, null, null,
        Instant.parse("2026-09-06T00:00:00Z"), Instant.parse("2026-09-06T00:00:00Z"), 0L);
  }

  @Test
  @DisplayName("21.2-API-020 P0 POST create returns 201 + serialized ECN view (status uppercase)")
  void createReturns201() throws Exception {
    var id = UUID.randomUUID();
    when(notices.create(any(), any())).thenReturn(view(id, EcnStatus.DRAFT));

    mockMvc.perform(post("/api/v1/equipment-change-notices")
            .contentType("application/json")
            .content("""
                {"ecnNumber":"ECN-1","machineId":"%s","title":"Retrofit guard"}"""
                .formatted(UUID.randomUUID()))
            .with(auth(staff())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.ecnNumber").value("ECN-1"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-06T00:00:00Z"));
  }

  @Test
  @DisplayName("21.2-API-021 P0 blank ecnNumber/title or missing machineId → 400 VALIDATION_ERROR")
  void blankFieldsValidation() throws Exception {
    mockMvc.perform(post("/api/v1/equipment-change-notices")
            .contentType("application/json")
            .content("{\"ecnNumber\":\"\",\"title\":\"  \"}")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.ecnNumber").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.title").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.machineId").isNotEmpty());
  }

  @Test
  @DisplayName("21.2-API-022 P0 duplicate number → 409 DUPLICATE_IDENTIFIER envelope")
  void duplicateIdentifier() throws Exception {
    when(notices.create(any(), any())).thenThrow(new DuplicateIdentifierException());

    mockMvc.perform(post("/api/v1/equipment-change-notices")
            .contentType("application/json")
            .content("""
                {"ecnNumber":"ECN-1","machineId":"%s","title":"Guard"}"""
                .formatted(UUID.randomUUID()))
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_IDENTIFIER"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("21.2-API-023 P0 illegal transition → 409 INVALID_STATE_TRANSITION")
  void invalidTransition() throws Exception {
    when(notices.approve(any(), any(), any())).thenThrow(new InvalidStateTransitionException());

    mockMvc.perform(post("/api/v1/equipment-change-notices/{id}/approve", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"effectiveDate\":\"2026-10-01\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("21.2-API-024 P0 approve role gate denial → 403 FORBIDDEN envelope")
  void forbidden() throws Exception {
    when(notices.approve(any(), any(), any())).thenThrow(new ComplianceForbiddenException());

    mockMvc.perform(post("/api/v1/equipment-change-notices/{id}/approve", UUID.randomUUID())
            .contentType("application/json")
            .content("{}")
            .with(auth(staff())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("21.2-API-025 P0 unknown/out-of-scope ECN → 404 ECN_NOT_FOUND")
  void notFound() throws Exception {
    when(notices.get(any(), any())).thenThrow(new EquipmentChangeNoticeNotFoundException());

    mockMvc.perform(get("/api/v1/equipment-change-notices/{id}", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ECN_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-API-026 P0 execute with unknown workorder → 404 WORK_ORDER_NOT_FOUND")
  void workOrderNotFound() throws Exception {
    when(notices.execute(any(), any(), any())).thenThrow(
        new ComplianceReferenceNotFoundException("WORK_ORDER_NOT_FOUND",
            "Referenced workorder was not found."));

    mockMvc.perform(post("/api/v1/equipment-change-notices/{id}/execute", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"executedWoId\":\"NOPE\"}")
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.2-API-027 P0 submit returns 200 + UNDER_REVIEW view")
  void submitReturnsView() throws Exception {
    var id = UUID.randomUUID();
    when(notices.submit(any(), any())).thenReturn(view(id, EcnStatus.UNDER_REVIEW));

    mockMvc.perform(post("/api/v1/equipment-change-notices/{id}/submit", id)
            .with(auth(staff())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
  }

  @Test
  @DisplayName("21.2-API-028 P0 concurrent update → 409 VERSION_CONFLICT (never 500)")
  void optimisticLockConflict() throws Exception {
    when(notices.close(any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(
            "EquipmentChangeNoticeEntity", UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/equipment-change-notices/{id}/close", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("21.2-API-029 P0 unauthenticated request → 401 AUTHENTICATION_REQUIRED body code")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/equipment-change-notices"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("21.2-API-030 P1 invalid ?status= enum → 400 VALIDATION_ERROR envelope")
  void invalidStatusFilter() throws Exception {
    mockMvc.perform(get("/api/v1/equipment-change-notices").param("status", "NOT_A_STATUS")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
