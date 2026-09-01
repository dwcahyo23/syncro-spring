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
import com.syncro.sparepart.request.application.SparepartRequestService.InvalidRequestStateTransitionException;
import com.syncro.sparepart.request.application.SparepartRequestService.MachineNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.PriceEntryNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestNotFoundException;
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
    stubAllowedActions(user);

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
            .content("{\"requestType\":\"SERVICE_EXTERNAL\",\"workOrderId\":\"WO-260999999\",\"quantity\":1}"))
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

  @Test
  @DisplayName("12.2-API-001 P0 transition endpoint returns 200 view")
  void transitionReturnsView() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    when(service.transition(eq(user), eq(id), any(SparepartRequestService.TransitionCommand.class)))
        .thenReturn(requestDomain(SparepartRequestStatus.ACKED));
    stubAllowedActions(user);

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/transition")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"ACKED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKED"));
  }

  @Test
  @DisplayName("12.2-API-002 P0 invalid transition maps to 409 INVALID_STATE_TRANSITION")
  void transitionInvalidEdge() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new InvalidRequestStateTransitionException())
        .when(service).transition(eq(user), eq(id), any(SparepartRequestService.TransitionCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/transition")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"READY\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("12.2-API-003 P0 unknown request maps to 404 REQUEST_NOT_FOUND")
  void transitionNotFound() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new RequestNotFoundException())
        .when(service).transition(eq(user), eq(id), any(SparepartRequestService.TransitionCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/transition")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"ACKED\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.2-API-004 P0 forbidden transition maps to 403 FORBIDDEN")
  void transitionForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    var id = UUID.randomUUID();
    doThrow(new RequestForbiddenException())
        .when(service).transition(eq(user), eq(id), any(SparepartRequestService.TransitionCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/transition")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"ACKED\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("12.2-API-005 P0 unknown toStatus enum maps to 400 VALIDATION_ERROR")
  void transitionInvalidEnum() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/transition")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("12.2-API-006 P0 MRE endpoint records and returns the view")
  void mreReturnsView() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    when(service.recordMre(eq(user), eq(id), any(SparepartRequestService.MreCommand.class)))
        .thenReturn(requestDomain(SparepartRequestStatus.PURCHASE_REQUESTED));
    stubAllowedActions(user);

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/mre")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mreCode\":\"MRE26023xxxx\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PURCHASE_REQUESTED"));
  }

  @Test
  @DisplayName("12.2-API-007 P0 blank MRE code maps to 400 VALIDATION_ERROR fieldErrors.mreCode")
  void mreBlankCode() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/mre")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mreCode\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.mreCode").exists());
  }

  private void stubAllowedActions(AuthenticatedUser user) {
    when(service.allowedActionsFor(eq(user), any(SparepartRequest.class)))
        .thenReturn(new SparepartRequestService.RequestAllowedActions(Set.of(), null));
  }

  @Test
  @DisplayName("12.3-API-001 P0 approve endpoint returns 200 view with approval fields")
  void approveReturnsView() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    var id = UUID.randomUUID();
    when(service.approve(eq(user), eq(id), any(SparepartRequestService.ApproveCommand.class)))
        .thenReturn(requestDomain(SparepartRequestStatus.ACKED));
    when(service.allowedActionsFor(eq(user), any(SparepartRequest.class)))
        .thenReturn(new SparepartRequestService.RequestAllowedActions(Set.of(), null));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/approve")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKED"));
  }

  @Test
  @DisplayName("12.3-API-002 P0 self-approval maps to 403 SELF_APPROVAL_FORBIDDEN")
  void approveSelfApprovalForbidden() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    var id = UUID.randomUUID();
    doThrow(new SparepartRequestService.SelfApprovalForbiddenException())
        .when(service).approve(eq(user), eq(id), any(SparepartRequestService.ApproveCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/approve")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
  }

  @Test
  @DisplayName("12.3-API-003 P0 approval in a wrong state maps to 409 INVALID_STATE_TRANSITION")
  void approveInvalidState() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new InvalidRequestStateTransitionException())
        .when(service).approve(eq(user), eq(id), any(SparepartRequestService.ApproveCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/approve")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("12.3-API-004 P0 approval with insufficient role/scope maps to 403 FORBIDDEN")
  void approveForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    var id = UUID.randomUUID();
    doThrow(new RequestForbiddenException())
        .when(service).approve(eq(user), eq(id), any(SparepartRequestService.ApproveCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/approve")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("12.3-API-005 P0 unknown request on approve maps to 404 REQUEST_NOT_FOUND")
  void approveNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new RequestNotFoundException())
        .when(service).approve(eq(user), eq(id), any(SparepartRequestService.ApproveCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/approve")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.4-API-001 P0 complete endpoint returns 200 view with ACKED status")
  void completeReturnsView() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    when(service.complete(eq(user), eq(id), any(SparepartRequestService.CompleteCommand.class)))
        .thenReturn(requestDomain(SparepartRequestStatus.ACKED));
    stubAllowedActions(user);

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/complete")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-NEW-001\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKED"));
  }

  @Test
  @DisplayName("12.4-API-002 P0 blank material code maps to 400 VALIDATION_ERROR")
  void completeBlankMaterialCode() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/complete")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.materialCode").exists());
  }

  @Test
  @DisplayName("12.4-API-003 P0 duplicate material code maps to 409 DUPLICATE_MATERIAL_CODE")
  void completeDuplicateMaterialCode() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new SparepartRequestService.DuplicateMaterialCodeException())
        .when(service).complete(eq(user), eq(id), any(SparepartRequestService.CompleteCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/complete")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_MATERIAL_CODE"));
  }

  @Test
  @DisplayName("12.4-API-004 P0 wrong state maps to 409 INVALID_STATE_TRANSITION")
  void completeWrongState() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new InvalidRequestStateTransitionException())
        .when(service).complete(eq(user), eq(id), any(SparepartRequestService.CompleteCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/complete")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("12.4-API-005 P0 forbidden completion maps to 403 FORBIDDEN")
  void completeForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    var id = UUID.randomUUID();
    doThrow(new RequestForbiddenException())
        .when(service).complete(eq(user), eq(id), any(SparepartRequestService.CompleteCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/complete")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("12.4-API-006 P0 unknown request maps to 404 REQUEST_NOT_FOUND")
  void completeNotFound() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    var id = UUID.randomUUID();
    doThrow(new RequestNotFoundException())
        .when(service).complete(eq(user), eq(id), any(SparepartRequestService.CompleteCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-requests/" + id + "/complete")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
  }

  private static SparepartRequest requestDomain() {
    return requestDomain(SparepartRequestStatus.REQUESTED);
  }

  private static SparepartRequest requestDomain(SparepartRequestStatus status) {
    return new SparepartRequest(UUID.randomUUID(), SparepartRequestType.SPAREPART, null, MACHINE_ID, null,
        "MC-0001", (short) 2, null, new BigDecimal("50000"), null, status,
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
