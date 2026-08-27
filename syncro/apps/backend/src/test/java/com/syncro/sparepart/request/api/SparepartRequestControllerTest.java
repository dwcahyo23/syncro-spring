package com.syncro.sparepart.request.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
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
import com.syncro.sparepart.request.application.SparepartRequestService;
import com.syncro.sparepart.request.application.SparepartRequestService.CreateRequestCommand;
import com.syncro.sparepart.request.application.SparepartRequestService.MachineNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.PriceEntryNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestValidationException;
import com.syncro.sparepart.request.application.SparepartRequestService.SparepartNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.WorkOrderNotFoundException;
import com.syncro.sparepart.request.domain.SparepartRequest;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
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

@WebMvcTest({SparepartRequestController.class})
@Import({SecurityConfig.class, SparepartRequestExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class SparepartRequestControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartRequestService service;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID MACHINE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Test
  @DisplayName("12.1-API-001 P0 create request returns 201")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(service.create(eq(user), any(CreateRequestCommand.class))).thenReturn(requestDomain());

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"SPAREPART\",\"machineId\":\"" + MACHINE_ID + "\",\"quantity\":2}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestType").value("SPAREPART"))
        .andExpect(jsonPath("$.status").value("REQUESTED"));
  }

  @Test
  @DisplayName("12.1-API-002 P0 type-rule violation maps to 400 VALIDATION_ERROR")
  void createTypeRuleViolation() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new RequestValidationException(Map.of("requestType", "SPAREPART requires a machine.")))
        .when(service).create(eq(user), any(CreateRequestCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"SPAREPART\",\"quantity\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.requestType").exists());
  }

  @Test
  @DisplayName("12.1-API-003 P0 invalid enum maps to 400 VALIDATION_ERROR")
  void createInvalidEnum() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"BOGUS\",\"quantity\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("12.1-API-004 P0 unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void createUnknownWorkOrder() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new WorkOrderNotFoundException())
        .when(service).create(eq(user), any(CreateRequestCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"SERVICE_EXTERNAL\",\"workOrderId\":\"WO-2609-99999\",\"quantity\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.1-API-005 P0 out-of-scope user maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new RequestForbiddenException())
        .when(service).create(eq(user), any(CreateRequestCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"CONSUMABLE\",\"quantity\":1}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("12.1-API-006 P0 unknown machine maps to 404 MACHINE_NOT_FOUND")
  void createUnknownMachine() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new MachineNotFoundException())
        .when(service).create(eq(user), any(CreateRequestCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"SPAREPART\",\"machineId\":\"99999999-9999-9999-9999-999999999999\",\"quantity\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.1-API-007 P0 unknown sparepart maps to 404 SPAREPART_NOT_FOUND")
  void createUnknownSparepart() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new SparepartNotFoundException())
        .when(service).create(eq(user), any(CreateRequestCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"SPAREPART\",\"machineId\":\"99999999-9999-9999-9999-999999999999\",\"sparepartId\":\"99999999-9999-9999-9999-999999999998\",\"quantity\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.1-API-008 P0 unknown price entry maps to 404 PRICE_ENTRY_NOT_FOUND")
  void createUnknownPriceEntry() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new PriceEntryNotFoundException())
        .when(service).create(eq(user), any(CreateRequestCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestType\":\"CONSUMABLE\",\"estPriceId\":\"99999999-9999-9999-9999-999999999997\",\"quantity\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRICE_ENTRY_NOT_FOUND"));
  }

  private static SparepartRequest requestDomain() {
    return new SparepartRequest(UUID.randomUUID(), SparepartRequestType.SPAREPART, null, MACHINE_ID, null,
        "MC-0001", (short) 2, null, new BigDecimal("50000"), null, SparepartRequestStatus.REQUESTED,
        UUID.randomUUID(), Instant.parse("2026-08-27T00:00:00Z"), null,
        Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"));
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
