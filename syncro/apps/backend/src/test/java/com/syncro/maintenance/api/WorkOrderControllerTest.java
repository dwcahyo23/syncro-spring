package com.syncro.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.application.WorkOrderService.AssignWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.BreakdownCategoryRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ChildrenNotTerminalException;
import com.syncro.maintenance.application.WorkOrderService.CreateResult;
import com.syncro.maintenance.application.WorkOrderService.CreateWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.OverrideReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ProcurementRequestConflictException;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderCategoryNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderParentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator.WorkorderIdExhaustedException;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

@WebMvcTest(WorkOrderController.class)
@Import({SecurityConfig.class, WorkOrderExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class WorkOrderControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WorkOrderService workOrders;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID MACHINE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID CATEGORY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID ASSIGNEE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Test
  @DisplayName("10.2-API-001 P0 create as SECTION_LEADER returns 201 with Location and the workorder")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.create(eq(user), any(CreateWorkOrderCommand.class)))
        .thenReturn(new CreateResult(view(), false));

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\",\"description\":\"breakdown\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/workorders/WO-2409-00001"))
        .andExpect(jsonPath("$.id").value("WO-2409-00001"))
        .andExpect(jsonPath("$.source").value("INTERNAL"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.machineId").value(MACHINE_ID.toString()));
  }

  @Test
  @DisplayName("10.2-API-002 P0 the Idempotency-Key header is forwarded to the service")
  void createForwardsIdempotencyKey() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.create(eq(user), any(CreateWorkOrderCommand.class))).thenReturn(new CreateResult(view(), false));

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .header("Idempotency-Key", "0f8fad5b-d9cb-469f-a165-70867728950e")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"));

    var captor = ArgumentCaptor.forClass(CreateWorkOrderCommand.class);
    org.mockito.Mockito.verify(workOrders).create(eq(user), captor.capture());
    assertThat(captor.getValue().idempotencyKey()).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
  }

  @Test
  @DisplayName("10.2-API-003 P0 create as TECHNICIAN returns 403 FORBIDDEN")
  void createAsTechnicianForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkorderForbiddenException()).when(workOrders).create(eq(user), any());

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.2-API-004 P0 PRODUCTION_LEADER with a non-breakdown category returns 403 BREAKDOWN_CATEGORY_REQUIRED")
  void createWrongCategoryForbidden() throws Exception {
    var user = user(ApplicationRole.PRODUCTION_LEADER);
    doThrow(new BreakdownCategoryRequiredException()).when(workOrders).create(eq(user), any());

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"02\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("BREAKDOWN_CATEGORY_REQUIRED"));
  }

  @Test
  @DisplayName("10.2-API-005 P0 missing machine maps to 404 MACHINE_NOT_FOUND")
  void createMachineNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new WorkOrderMachineNotFoundException()).when(workOrders).create(eq(user), any());

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.2-API-006 P0 missing category maps to 404 CATEGORY_NOT_FOUND")
  void createCategoryNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new WorkOrderCategoryNotFoundException()).when(workOrders).create(eq(user), any());

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"99\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.2-API-007 P0 missing parent maps to 404 PARENT_NOT_FOUND")
  void createParentNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new WorkOrderParentNotFoundException()).when(workOrders).create(eq(user), any());

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\",\"parentId\":\"WO-2409-NADA\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PARENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.2-API-008 P0 assign returns 200 with the ASSIGNED workorder")
  void assignReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.assign(eq(user), eq("WO-2409-00001"), any(AssignWorkOrderCommand.class)))
        .thenReturn(assignedView());

    mockMvc.perform(post("/api/v1/workorders/{id}/assign", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"assigneeUserId\":\"" + ASSIGNEE_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.assignedTechnicianId").value(ASSIGNEE_ID.toString()));
  }

  @Test
  @DisplayName("10.2-API-009 P0 assigning a wrong-state workorder maps to 409 INVALID_STATE_TRANSITION")
  void assignInvalidState() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new InvalidStateTransitionException()).when(workOrders).assign(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/assign", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"assigneeUserId\":\"" + ASSIGNEE_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("10.2-API-010 P0 SECTION_LEADER self-assignment maps to 409 SELF_ASSIGNMENT_FORBIDDEN")
  void assignSelf() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new SelfAssignmentForbiddenException()).when(workOrders).assign(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/assign", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"assigneeUserId\":\"" + ASSIGNEE_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SELF_ASSIGNMENT_FORBIDDEN"));
  }

  @Test
  @DisplayName("10.2-API-011 P0 unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void assignWorkOrderNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new WorkOrderNotFoundException()).when(workOrders).assign(eq(user), eq("WO-2409-NADA"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/assign", "WO-2409-NADA")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"assigneeUserId\":\"" + ASSIGNEE_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.2-API-012 P0 unknown assignee maps to 404 USER_NOT_FOUND")
  void assignUserNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new WorkOrderUserNotFoundException()).when(workOrders).assign(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/assign", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"assigneeUserId\":\"" + ASSIGNEE_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.2-API-013 P0 exhausted id sequence maps to 503 WORKORDER_ID_EXHAUSTED")
  void createIdExhausted() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new WorkorderIdExhaustedException()).when(workOrders).create(eq(user), any());

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("WORKORDER_ID_EXHAUSTED"));
  }

  @Test
  @DisplayName("10.2-API-014 P1 invalid create body maps to VALIDATION_ERROR")
  void invalidCreateBody() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"\",\"machineId\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  @Test
  @DisplayName("10.2-API-016 P1 idempotent replay returns 200 with the existing workorder")
  void createReplayReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.create(eq(user), any(CreateWorkOrderCommand.class)))
        .thenReturn(new CreateResult(view(), true));

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .header("Idempotency-Key", "0f8fad5b-d9cb-469f-a165-70867728950e")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("WO-2409-00001"));
  }

  @Test
  @DisplayName("10.2-API-017 P1 an Idempotency-Key longer than 64 chars is rejected")
  void idempotencyKeyTooLong() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);

    mockMvc.perform(post("/api/v1/workorders")
        .with(auth(user))
        .header("Idempotency-Key", "k".repeat(65))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_TOO_LONG"));
  }

  @Test
  @DisplayName("10.2-API-015 P1 unauthenticated workorder requests are rejected")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(post("/api/v1/workorders")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"categoryCode\":\"01\",\"machineId\":\"" + MACHINE_ID + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("10.3-API-001 P0 transition returns 200 with the new status")
  void transitionReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.transition(eq(user), eq("WO-2409-00001"), any(TransitionWorkOrderCommand.class)))
        .thenReturn(inProgressView());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"IN_PROGRESS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("WO-2409-00001"))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
  }

  @Test
  @DisplayName("10.3-API-002 P0 invalid transition maps to 409 INVALID_STATE_TRANSITION")
  void transitionInvalidState() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new InvalidStateTransitionException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"IN_PROGRESS\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("10.3-API-003 P0 forbidden transition maps to 403 FORBIDDEN")
  void transitionForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkorderForbiddenException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"ON_PROCUREMENT\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.3-API-004 P0 unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void transitionNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new WorkOrderNotFoundException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-NADA"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-NADA")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"CLOSED\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.3-API-005 P0 procurement conflict maps to 409 PROCUREMENT_REQUEST_CONFLICT")
  void transitionProcurementConflict() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    doThrow(new ProcurementRequestConflictException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"ON_PROCUREMENT\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROCUREMENT_REQUEST_CONFLICT"));
  }

  @Test
  @DisplayName("10.3-API-006 P0 non-terminal children map to 409 CHILDREN_NOT_TERMINAL")
  void transitionChildrenNotTerminal() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    doThrow(new ChildrenNotTerminalException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"CLOSED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHILDREN_NOT_TERMINAL"));
  }

  @Test
  @DisplayName("10.3-API-007 P0 missing override reason maps to 400 OVERRIDE_REASON_REQUIRED")
  void transitionOverrideReasonRequired() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new OverrideReasonRequiredException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"CLOSED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("OVERRIDE_REASON_REQUIRED"));
  }

  @Test
  @DisplayName("10.3-API-008 P1 a missing toStatus maps to 400 VALIDATION_ERROR")
  void transitionMissingToStatus() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.toStatus").value("Invalid value."));
  }

  @Test
  @DisplayName("10.3-API-009 P1 an unknown toStatus enum maps to 400 VALIDATION_ERROR")
  void transitionUnknownStatus() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"NOT_A_STATUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("10.3-API-010 P1 the override reason is forwarded to the service")
  void transitionForwardsOverrideReason() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(workOrders.transition(eq(user), eq("WO-2409-00001"), any(TransitionWorkOrderCommand.class)))
        .thenReturn(closedView());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"CLOSED\",\"overrideReason\":\"expedite delivery\"}"));

    var captor = ArgumentCaptor.forClass(TransitionWorkOrderCommand.class);
    org.mockito.Mockito.verify(workOrders).transition(eq(user), eq("WO-2409-00001"), captor.capture());
    assertThat(captor.getValue().toStatus()).isEqualTo(WorkOrderStatus.CLOSED);
    assertThat(captor.getValue().overrideReason()).isEqualTo("expedite delivery");
  }

  private static WorkOrder inProgressView() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.IN_PROGRESS, CATEGORY_ID, MACHINE_ID,
        "breakdown", null, ASSIGNEE_ID, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"),
        Instant.parse("2026-08-26T00:00:00Z"));
  }

  private static WorkOrder closedView() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.CLOSED, CATEGORY_ID, MACHINE_ID,
        "breakdown", null, ASSIGNEE_ID, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"),
        Instant.parse("2026-08-26T00:00:00Z"));
  }

  private static WorkOrder view() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.OPEN, CATEGORY_ID, MACHINE_ID, "breakdown",
        null, null, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"));
  }

  private static WorkOrder assignedView() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.ASSIGNED, CATEGORY_ID, MACHINE_ID, "breakdown",
        null, ASSIGNEE_ID, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"));
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