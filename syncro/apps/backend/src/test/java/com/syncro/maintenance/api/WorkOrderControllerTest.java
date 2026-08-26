package com.syncro.maintenance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.syncro.maintenance.application.WorkOrderEvidenceService;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceCommand;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceAttachmentNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceForbiddenException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.StorageException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.ValidationException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.WorkorderAttachmentView;
import com.syncro.maintenance.application.WorkOrderEvidenceService.WorkorderAttachmentsView;
import com.syncro.maintenance.application.WorkOrderReportService;
import com.syncro.maintenance.application.WorkOrderReportService.CpkPdfCommand;
import com.syncro.maintenance.application.WorkOrderReportService.ReportForbiddenException;
import com.syncro.maintenance.application.WorkOrderReportService.ReportWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderReportService.SaveReportCommand;
import com.syncro.maintenance.application.WorkOrderReportService.WorkOrderReportValidationException;
import com.syncro.maintenance.application.WorkOrderReportService.WorkOrderReportView;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.application.WorkOrderService.AssignWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.BreakdownCategoryRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ChildrenNotTerminalException;
import com.syncro.maintenance.application.WorkOrderService.CreateResult;
import com.syncro.maintenance.application.WorkOrderService.CreateWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.DoneWithoutSessionReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.NoOpenSessionException;
import com.syncro.maintenance.application.WorkOrderService.OverrideReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ProcurementRequestConflictException;
import com.syncro.maintenance.application.WorkOrderService.RepairSessionsResult;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.SessionAlreadyOpenException;
import com.syncro.maintenance.application.WorkOrderService.SessionOverlapException;
import com.syncro.maintenance.application.WorkOrderService.StartSessionCommand;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderCategoryNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderParentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderNotInProgressException;
import com.syncro.maintenance.application.WorkOrderService.StopTimeReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderTodoService;
import com.syncro.maintenance.application.WorkOrderTodoService.CreateTodoCommand;
import com.syncro.maintenance.application.WorkOrderTodoService.KanbanView;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoForbiddenException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderKanbanItem;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderTerminalException;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderTodoValidationException;
import com.syncro.maintenance.application.WorkOrderRatingService;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingForbiddenException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingAlreadyExistsException;
import com.syncro.maintenance.application.WorkOrderRatingService.WorkorderNotClosedException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatedUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.UserNotExecutorException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingValidationException;
import com.syncro.maintenance.domain.workorder.RepairSession;
import com.syncro.maintenance.domain.workorder.TodoStatus;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator.WorkorderIdExhaustedException;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.domain.workorder.WorkOrderTodo;
import com.syncro.maintenance.domain.workorder.WorkorderRating;
import com.syncro.maintenance.domain.workorder.RatingType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockMultipartFile;
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
  private WorkOrderEvidenceService evidence;

  @MockitoBean
  private WorkOrderReportService report;

  @MockitoBean
  private WorkOrderTodoService todos;

  @MockitoBean
  private WorkOrderRatingService ratings;

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

  @Test
  @DisplayName("10.4-API-001 P0 start session returns 200 with the workorder and sessions")
  void startSessionReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.startSession(eq(user), eq("WO-2409-00001"), any(StartSessionCommand.class)))
        .thenReturn(sessionsResult(inProgressView()));

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"description\":\"diagnosis\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrder.id").value("WO-2409-00001"))
        .andExpect(jsonPath("$.workOrder.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.sessions[0].description").value("diagnosis"));
  }

  @Test
  @DisplayName("10.4-API-002 P0 start session with a blank body still works (description optional)")
  void startSessionWithoutBody() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.startSession(eq(user), eq("WO-2409-00001"), any(StartSessionCommand.class)))
        .thenReturn(sessionsResult(inProgressView()));

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrder.id").value("WO-2409-00001"));
  }

  @Test
  @DisplayName("10.4-API-003 P0 forbidden session start maps to 403 FORBIDDEN")
  void startSessionForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new WorkorderForbiddenException()).when(workOrders)
        .startSession(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.4-API-004 P0 already-open session maps to 409 SESSION_ALREADY_OPEN")
  void startSessionAlreadyOpen() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new SessionAlreadyOpenException()).when(workOrders)
        .startSession(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SESSION_ALREADY_OPEN"));
  }

  @Test
  @DisplayName("10.4-API-005 P0 session overlap maps to 409 SESSION_OVERLAP")
  void startSessionOverlap() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new SessionOverlapException()).when(workOrders)
        .startSession(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SESSION_OVERLAP"));
  }

  @Test
  @DisplayName("10.4-API-006 P0 not-in-progress workorder maps to 409 WORKORDER_NOT_IN_PROGRESS")
  void startSessionNotInProgress() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkorderNotInProgressException()).when(workOrders)
        .startSession(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_IN_PROGRESS"));
  }

  @Test
  @DisplayName("10.4-API-007 P0 unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void startSessionNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderNotFoundException()).when(workOrders)
        .startSession(eq(user), eq("WO-2409-NADA"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions", "WO-2409-NADA")
        .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.4-API-008 P0 stop session returns 200 with recomputed MTTR")
  void stopSessionReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.stopSession(eq(user), eq("WO-2409-00001")))
        .thenReturn(sessionsResult(inProgressView()));

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions/stop", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrder.id").value("WO-2409-00001"));
  }

  @Test
  @DisplayName("10.4-API-009 P0 no open session maps to 409 NO_OPEN_SESSION")
  void stopSessionNoOpen() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new NoOpenSessionException()).when(workOrders).stopSession(eq(user), eq("WO-2409-00001"));

    mockMvc.perform(post("/api/v1/workorders/{id}/sessions/stop", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NO_OPEN_SESSION"));
  }

  @Test
  @DisplayName("10.4-API-010 P0 list sessions returns 200 for any authenticated user")
  void listSessionsReturnsOk() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(workOrders.listSessions("WO-2409-00001")).thenReturn(sessionsResult(inProgressView()));

    mockMvc.perform(get("/api/v1/workorders/{id}/sessions", "WO-2409-00001")
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessions[0].workOrderId").value("WO-2409-00001"));
  }

  @Test
  @DisplayName("10.4-API-011 P0 DONE without session reason maps to 400 DONE_WITHOUT_SESSION_REASON_REQUIRED")
  void doneWithoutSessionReasonRequired() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new DoneWithoutSessionReasonRequiredException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toStatus\":\"DONE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DONE_WITHOUT_SESSION_REASON_REQUIRED"));
  }

  // -------------------------------------------------------------------------
  // Evidence & technical drawings (10.5)
  // -------------------------------------------------------------------------

  private static final UUID ATTACHMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID UPLOADER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @Test
  @DisplayName("10.5-API-001 P0 technician uploads an attachment and receives 200 with view")
  void uploadAttachmentReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(evidence.create(eq(user), eq("WO-2409-00001"), any(EvidenceCommand.class)))
        .thenReturn(attachmentView());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments", "WO-2409-00001")
            .file(new MockMultipartFile("data", "photo.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "photo.jpg")
            .param("contentType", "image/jpeg")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ATTACHMENT_ID.toString()))
        .andExpect(jsonPath("$.workOrderId").value("WO-2409-00001"))
        .andExpect(jsonPath("$.filename").value("photo.jpg"))
        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
        .andExpect(jsonPath("$.presignedUrl").value("https://presigned/key"));
  }

  @Test
  @DisplayName("10.5-API-002 P0 replace maps the PUT multipart and returns 200")
  void replaceAttachmentReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(evidence.replace(eq(user), eq("WO-2409-00001"), eq(ATTACHMENT_ID), any(EvidenceCommand.class)))
        .thenReturn(attachmentView());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments/{attachmentId}", "WO-2409-00001", ATTACHMENT_ID)
            .file(new MockMultipartFile("data", "new.pdf", "application/pdf", new byte[] {9}))
            .param("filename", "new.pdf")
            .param("contentType", "application/pdf")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ATTACHMENT_ID.toString()));
  }

  @Test
  @DisplayName("10.5-API-003 P0 list returns 200 with attachments for any authenticated user")
  void listAttachmentsReturnsOk() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(evidence.list("WO-2409-00001"))
        .thenReturn(new WorkorderAttachmentsView("WO-2409-00001", List.of(attachmentView())));

    mockMvc.perform(get("/api/v1/workorders/{id}/attachments", "WO-2409-00001")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrderId").value("WO-2409-00001"))
        .andExpect(jsonPath("$.attachments[0].filename").value("photo.jpg"));
  }

  @Test
  @DisplayName("10.5-API-004 P0 get single returns 200 with view")
  void getAttachmentReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(evidence.get("WO-2409-00001", ATTACHMENT_ID)).thenReturn(attachmentView());

    mockMvc.perform(get("/api/v1/workorders/{id}/attachments/{attachmentId}", "WO-2409-00001", ATTACHMENT_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ATTACHMENT_ID.toString()))
        .andExpect(jsonPath("$.presignedUrl").value("https://presigned/key"));
  }

  @Test
  @DisplayName("10.5-API-005 P0 delete returns 204")
  void deleteAttachmentReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);

    mockMvc.perform(delete("/api/v1/workorders/{id}/attachments/{attachmentId}", "WO-2409-00001", ATTACHMENT_ID)
            .with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("10.5-API-006 P0 forbidden upload maps to 403 FORBIDDEN")
  void uploadForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new EvidenceForbiddenException()).when(evidence).create(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments", "WO-2409-00001")
            .file(new MockMultipartFile("data", "photo.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "photo.jpg")
            .param("contentType", "image/jpeg")
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.5-API-007 P0 unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void uploadWorkOrderNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new EvidenceWorkOrderNotFoundException()).when(evidence).create(eq(user), eq("WO-2409-NADA"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments", "WO-2409-NADA")
            .file(new MockMultipartFile("data", "photo.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "photo.jpg")
            .param("contentType", "image/jpeg")
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.5-API-008 P0 unknown attachment maps to 404 ATTACHMENT_NOT_FOUND")
  void replaceAttachmentNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new EvidenceAttachmentNotFoundException()).when(evidence)
        .replace(eq(user), eq("WO-2409-00001"), eq(ATTACHMENT_ID), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments/{attachmentId}", "WO-2409-00001", ATTACHMENT_ID)
            .file(new MockMultipartFile("data", "new.pdf", "application/pdf", new byte[] {9}))
            .param("filename", "new.pdf")
            .param("contentType", "application/pdf")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.5-API-009 P0 validation error maps to 400 VALIDATION_ERROR with fieldErrors")
  void uploadValidationError() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new ValidationException(Map.of("data", "Attachment file exceeds the maximum allowed size.")))
        .when(evidence).create(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments", "WO-2409-00001")
            .file(new MockMultipartFile("data", "photo.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "photo.jpg")
            .param("contentType", "image/jpeg")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.data").exists());
  }

  @Test
  @DisplayName("10.5-API-010 P0 object storage error maps to 502 OBJECT_STORAGE_ERROR")
  void uploadStorageError() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new StorageException(new RuntimeException("s3 down"))).when(evidence)
        .create(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments", "WO-2409-00001")
            .file(new MockMultipartFile("data", "photo.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "photo.jpg")
            .param("contentType", "image/jpeg")
            .with(auth(user)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("OBJECT_STORAGE_ERROR"));
  }

  @Test
  @DisplayName("10.5-API-011 P0 missing multipart part maps to 400 VALIDATION_ERROR")
  void uploadMissingPart() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);

    mockMvc.perform(multipart("/api/v1/workorders/{id}/attachments", "WO-2409-00001")
            .file(new MockMultipartFile("data", "photo.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "photo.jpg")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.contentType").exists());
  }

  // -------------------------------------------------------------------------
  // Report, CP/CPK, FMEA & stop-time (10.6)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.6-API-001 P0 report save returns 200 with the report view")
  void saveReportReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(report.saveReport(eq(user), eq("WO-2409-00001"), any(SaveReportCommand.class)))
        .thenReturn(new WorkOrderReportView("WO-2409-00001", "Repair log", "Diagnosis", "Fixed", "Preventive",
            null, null, new BigDecimal("1.5"), "https://presigned/cpk.pdf", "MECHANICAL", "MECHANICAL", null));

    mockMvc.perform(put("/api/v1/workorders/{id}/report", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reportChronological\":\"Repair log\",\"reportAnalyze\":\"Diagnosis\","
                + "\"reportCorrective\":\"Fixed\",\"reportPreventive\":\"Preventive\","
                + "\"cpk\":1.5,\"fmeaFailureType\":\"MECHANICAL\",\"stopTimeReason\":\"MECHANICAL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrderId").value("WO-2409-00001"))
        .andExpect(jsonPath("$.reportChronological").value("Repair log"))
        .andExpect(jsonPath("$.reportAnalyze").value("Diagnosis"))
        .andExpect(jsonPath("$.reportCorrective").value("Fixed"))
        .andExpect(jsonPath("$.reportPreventive").value("Preventive"))
        .andExpect(jsonPath("$.cpk").value(1.5))
        .andExpect(jsonPath("$.fmeaFailureType").value("MECHANICAL"))
        .andExpect(jsonPath("$.stopTimeReason").value("MECHANICAL"));
  }

  @Test
  @DisplayName("10.6-API-002 P0 report save by an out-of-scope user maps to 403 FORBIDDEN")
  void saveReportForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new ReportForbiddenException()).when(report)
        .saveReport(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(put("/api/v1/workorders/{id}/report", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reportChronological\":\"Repair log\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.6-API-003 P0 report save on an unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void saveReportNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new ReportWorkOrderNotFoundException()).when(report)
        .saveReport(eq(user), eq("WO-2409-NADA"), any());

    mockMvc.perform(put("/api/v1/workorders/{id}/report", "WO-2409-NADA")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reportChronological\":\"Repair log\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.6-API-004 P0 report validation maps to 400 VALIDATION_ERROR with fieldErrors")
  void saveReportValidationError() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderReportValidationException(Map.of("cpk", "Capability index must not be negative.")))
        .when(report).saveReport(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(put("/api/v1/workorders/{id}/report", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cpk\":-1.0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.cpk").exists());
  }

  @Test
  @DisplayName("10.6-API-005 P0 report read returns 200 for any authenticated user")
  void getReportReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(report.getReport("WO-2409-00001"))
        .thenReturn(reportView("Repair log", "Diagnosis", "Fixed", "Preventive"));

    mockMvc.perform(get("/api/v1/workorders/{id}/report", "WO-2409-00001")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrderId").value("WO-2409-00001"))
        .andExpect(jsonPath("$.cpkPdfPresignedUrl").value("https://presigned/cpk.pdf"));
  }

  @Test
  @DisplayName("10.6-API-005b P0 report read on an unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void getReportNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(report.getReport("WO-2409-NADA"))
        .thenThrow(new ReportWorkOrderNotFoundException());

    mockMvc.perform(get("/api/v1/workorders/{id}/report", "WO-2409-NADA")
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.6-API-006 P0 CPK PDF upload returns 200 with the report view")
  void uploadCpkPdfReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(report.uploadCpkPdf(eq(user), eq("WO-2409-00001"), any(CpkPdfCommand.class)))
        .thenReturn(reportView("Repair log", "Diagnosis", "Fixed", "Preventive"));

    mockMvc.perform(multipart("/api/v1/workorders/{id}/report/cpk", "WO-2409-00001")
            .file(new MockMultipartFile("data", "capability.pdf", "application/pdf", new byte[] {1}))
            .param("filename", "capability.pdf")
            .param("contentType", "application/pdf")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workOrderId").value("WO-2409-00001"));
  }

  @Test
  @DisplayName("10.6-API-007 P0 CPK PDF delete returns 200 with the cleared view")
  void deleteCpkPdfReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(report.deleteCpkPdf(eq(user), eq("WO-2409-00001")))
        .thenReturn(new WorkOrderReportView("WO-2409-00001", null, null, null, null,
            null, null, null, null, null, null, null));

    mockMvc.perform(delete("/api/v1/workorders/{id}/report/cpk", "WO-2409-00001")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cpkPdfPresignedUrl").doesNotExist());
  }

  @Test
  @DisplayName("10.6-API-008 P0 CPK upload by a forbidden user maps to 403 FORBIDDEN")
  void uploadCpkForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new ReportForbiddenException()).when(report)
        .uploadCpkPdf(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/report/cpk", "WO-2409-00001")
            .file(new MockMultipartFile("data", "capability.pdf", "application/pdf", new byte[] {1}))
            .param("filename", "capability.pdf")
            .param("contentType", "application/pdf")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.6-API-009 P0 CPK upload validation error maps to 400 VALIDATION_ERROR")
  void uploadCpkValidationError() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderReportValidationException(Map.of("contentType", "Content type must be application/pdf.")))
        .when(report).uploadCpkPdf(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/report/cpk", "WO-2409-00001")
            .file(new MockMultipartFile("data", "capability.png", "image/png", new byte[] {1}))
            .param("filename", "capability.png")
            .param("contentType", "image/png")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.contentType").exists());
  }

  @Test
  @DisplayName("10.6-API-010 P0 breakdown DONE without stop-time reason maps to 400 STOP_TIME_REASON_REQUIRED")
  void doneWithoutStopTimeReason() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new StopTimeReasonRequiredException()).when(workOrders)
        .transition(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/transition", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toStatus\":\"DONE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("STOP_TIME_REASON_REQUIRED"));
  }

  @Test
  @DisplayName("10.6-API-011 P1 CPK upload storage failure maps to 502 OBJECT_STORAGE_ERROR")
  void uploadCpkStorageError() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderReportService.StorageException(new RuntimeException("s3 down"))).when(report)
        .uploadCpkPdf(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(multipart("/api/v1/workorders/{id}/report/cpk", "WO-2409-00001")
            .file(new MockMultipartFile("data", "capability.pdf", "application/pdf", new byte[] {1}))
            .param("filename", "capability.pdf")
            .param("contentType", "application/pdf")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("OBJECT_STORAGE_ERROR"));
  }

  @Test
  @DisplayName("10.6-API-012 P1 missing CPK multipart part maps to 400 VALIDATION_ERROR")
  void uploadCpkMissingPart() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);

    mockMvc.perform(multipart("/api/v1/workorders/{id}/report/cpk", "WO-2409-00001")
            .file(new MockMultipartFile("data", "capability.pdf", "application/pdf", new byte[] {1}))
            .param("filename", "capability.pdf")
            .with(auth(user))
            .with(request -> { request.setMethod("PUT"); return request; }))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.contentType").exists());
  }

  // -------------------------------------------------------------------------
  // Todos & kanban (10.7)
  // -------------------------------------------------------------------------

  private static final UUID TODO_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

  @Test
  @DisplayName("10.7-API-001 P0 create todo returns 201 with the todo view")
  void createTodoReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(todos.create(eq(user), eq("WO-2409-00001"), any(CreateTodoCommand.class)))
        .thenReturn(todoView());

    mockMvc.perform(post("/api/v1/workorders/{id}/todos", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fix bearing\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(TODO_ID.toString()))
        .andExpect(jsonPath("$.title").value("Fix bearing"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  @DisplayName("10.7-API-002 P0 create todo on a terminal workorder maps to 400 WORKORDER_TERMINAL")
  void createTodoTerminal() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderTerminalException()).when(todos).create(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/todos", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fix bearing\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WORKORDER_TERMINAL"));
  }

  @Test
  @DisplayName("10.7-API-003 P0 create todo with blank title maps to 400 VALIDATION_ERROR")
  void createTodoBlankTitle() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderTodoValidationException(Map.of("title", "Title must not be blank.")))
        .when(todos).create(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/todos", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.title").exists());
  }

  @Test
  @DisplayName("10.7-API-004 P0 list todos returns 200 for any authenticated user")
  void listTodosReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(todos.list("WO-2409-00001")).thenReturn(List.of(todoDomain()));

    mockMvc.perform(get("/api/v1/workorders/{id}/todos", "WO-2409-00001")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(TODO_ID.toString()))
        .andExpect(jsonPath("$[0].title").value("Fix bearing"));
  }

  @Test
  @DisplayName("10.7-API-005 P0 list todos on an unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void listTodosWorkOrderNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(todos.list("WO-2409-NADA")).thenThrow(new TodoWorkOrderNotFoundException());

    mockMvc.perform(get("/api/v1/workorders/{id}/todos", "WO-2409-NADA")
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.7-API-006 P0 assign todo returns 200 with the updated view")
  void assignTodoReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    var techId = UUID.randomUUID();
    when(todos.assign(eq(user), eq("WO-2409-00001"), eq(TODO_ID), eq(techId)))
        .thenReturn(todoView());

    mockMvc.perform(put("/api/v1/workorders/{id}/todos/{todoId}/assign", "WO-2409-00001", TODO_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"assignedTechnicianId\":\"" + techId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TODO_ID.toString()));
  }

  @Test
  @DisplayName("10.7-API-007 P0 complete todo returns 200 with the COMPLETED view")
  void completeTodoReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(todos.complete(eq(user), eq("WO-2409-00001"), eq(TODO_ID)))
        .thenReturn(todoView());

    mockMvc.perform(put("/api/v1/workorders/{id}/todos/{todoId}/complete", "WO-2409-00001", TODO_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TODO_ID.toString()));
  }

  @Test
  @DisplayName("10.7-API-008 P0 reorder todo returns 200 with the updated view")
  void reorderTodoReturnsOk() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(todos.reorder(eq(user), eq("WO-2409-00001"), eq(TODO_ID), eq(5)))
        .thenReturn(todoView());

    mockMvc.perform(put("/api/v1/workorders/{id}/todos/{todoId}/reorder", "WO-2409-00001", TODO_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sortOrder\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TODO_ID.toString()));
  }

  @Test
  @DisplayName("10.7-API-009 P0 delete todo returns 204")
  void deleteTodoReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);

    mockMvc.perform(delete("/api/v1/workorders/{id}/todos/{todoId}", "WO-2409-00001", TODO_ID)
            .with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("10.7-API-010 P0 delete on an unknown todo maps to 404 TODO_NOT_FOUND")
  void deleteTodoNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new TodoNotFoundException()).when(todos).delete(eq(user), eq("WO-2409-00001"), eq(TODO_ID));

    mockMvc.perform(delete("/api/v1/workorders/{id}/todos/{todoId}", "WO-2409-00001", TODO_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TODO_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.7-API-011 P0 forbidden todo mutation maps to 403 FORBIDDEN")
  void createTodoForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new TodoForbiddenException()).when(todos).create(eq(user), eq("WO-2409-00001"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/todos", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fix bearing\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.7-API-012 P0 kanban returns 200 with status groups")
  void kanbanReturnsOk() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    var groups = new java.util.HashMap<WorkOrderStatus, List<WorkOrderKanbanItem>>();
    groups.put(WorkOrderStatus.IN_PROGRESS, List.of());
    when(todos.kanban(user)).thenReturn(new KanbanView(groups));

    mockMvc.perform(get("/api/v1/workorders/kanban")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groups").isMap());
  }

  @Test
  @DisplayName("10.7-API-013 P0 create todo with unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void createTodoWorkOrderNotFound() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new TodoWorkOrderNotFoundException()).when(todos).create(eq(user), eq("WO-2409-NADA"), any());

    mockMvc.perform(post("/api/v1/workorders/{id}/todos", "WO-2409-NADA")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Fix bearing\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  // -------------------------------------------------------------------------
  // Ratings (10.8, FR-121/FR-124)
  // -------------------------------------------------------------------------

  private static final UUID RATED_USER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

  @Test
  @DisplayName("10.8-API-001 P0 rate technician returns 201 with the rating view")
  void rateTechnicianReturnsCreated() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    var rating = ratingView(RatingType.TECHNICIAN, RATED_USER_ID);
    when(ratings.rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class)))
        .thenReturn(rating);

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":4,\"WORK_QUALITY\":5}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ratingType").value("TECHNICIAN"))
        .andExpect(jsonPath("$.scores[0].dimensionCode").value("SPEED"))
        .andExpect(jsonPath("$.scores[0].score").value(4));
  }

  @Test
  @DisplayName("10.8-API-002 P0 rate technician on a non-closed workorder maps to 400 RATING_WORKORDER_NOT_CLOSED")
  void rateTechnicianNotClosed() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new WorkorderNotClosedException()).when(ratings)
        .rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":4}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RATING_WORKORDER_NOT_CLOSED"));
  }

  @Test
  @DisplayName("10.8-API-003 P0 rate technician forbidden maps to 403 FORBIDDEN")
  void rateTechnicianForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new RatingForbiddenException()).when(ratings)
        .rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":4}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.8-API-004 P0 rate technician unknown rated user maps to 404 USER_NOT_FOUND")
  void rateTechnicianUserNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new RatedUserNotFoundException()).when(ratings)
        .rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":4}}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.8-API-005 P0 rate technician not executor maps to 400 RATING_USER_NOT_EXECUTOR")
  void rateTechnicianNotExecutor() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new UserNotExecutorException()).when(ratings)
        .rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":4}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RATING_USER_NOT_EXECUTOR"));
  }

  @Test
  @DisplayName("10.8-API-006 P0 duplicate rating maps to 409 RATING_ALREADY_EXISTS")
  void rateTechnicianDuplicate() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new RatingAlreadyExistsException()).when(ratings)
        .rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":4}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RATING_ALREADY_EXISTS"));
  }

  @Test
  @DisplayName("10.8-API-007 P0 rating validation maps to 400 VALIDATION_ERROR with fieldErrors")
  void rateTechnicianValidationError() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new RatingValidationException(Map.of("scores.SPEED", "Score must be an integer between 1 and 5.")))
        .when(ratings).rateTechnician(eq(user), eq("WO-2409-00001"), any(UUID.class), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/technician", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratedUserId\":\"" + RATED_USER_ID + "\",\"scores\":{\"SPEED\":6}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors['scores.SPEED']").exists());
  }

  @Test
  @DisplayName("10.8-API-008 P0 rate workorder returns 201 with the rating view")
  void rateWorkorderReturnsCreated() throws Exception {
    var user = user(ApplicationRole.PRODUCTION_LEADER);
    var rating = ratingView(RatingType.WORKORDER, null);
    when(ratings.rateWorkorder(eq(user), eq("WO-2409-00001"), any(Map.class)))
        .thenReturn(rating);

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/workorder", "WO-2409-00001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scores\":{\"SPEED\":3}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ratingType").value("WORKORDER"))
        .andExpect(jsonPath("$.ratedUserId").doesNotExist());
  }

  @Test
  @DisplayName("10.8-API-009 P0 rate workorder on unknown workorder maps to 404 WORKORDER_NOT_FOUND")
  void rateWorkorderNotFound() throws Exception {
    var user = user(ApplicationRole.PRODUCTION_LEADER);
    doThrow(new RatingWorkOrderNotFoundException()).when(ratings)
        .rateWorkorder(eq(user), eq("WO-2409-NADA"), any(Map.class));

    mockMvc.perform(post("/api/v1/workorders/{id}/ratings/workorder", "WO-2409-NADA")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scores\":{\"SPEED\":3}}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.8-API-010 P0 list ratings returns 200 with the ratings")
  void listRatingsReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(ratings.listRatings("WO-2409-00001")).thenReturn(List.of(ratingDomain(RatingType.TECHNICIAN, RATED_USER_ID)));

    mockMvc.perform(get("/api/v1/workorders/{id}/ratings", "WO-2409-00001")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ratingType").value("TECHNICIAN"))
        .andExpect(jsonPath("$[0].scores[0].dimensionCode").value("SPEED"));
  }

  @Test
  @DisplayName("10.8-API-011 P0 ratings page returns 200 with rateable workorders")
  void ratingsPageReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    var item = new WorkOrderRatingService.RateableWorkorder("WO-2409-00001", "INTERNAL", WorkOrderStatus.CLOSED,
        "01", MACHINE_ID, "breakdown", ASSIGNEE_ID, Instant.parse("2026-08-26T00:00:00Z"),
        List.of(ASSIGNEE_ID));
    when(ratings.listRateableClosed(user)).thenReturn(List.of(item));

    mockMvc.perform(get("/api/v1/workorders/ratings")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("WO-2409-00001"))
        .andExpect(jsonPath("$[0].executorPool[0]").value(ASSIGNEE_ID.toString()));
  }

  private static WorkorderRating ratingDomain(RatingType ratingType, UUID ratedUserId) {
    return new WorkorderRating(UUID.randomUUID(), "WO-2409-00001", ratingType, ratedUserId,
        UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"), Map.of("SPEED", 4));
  }

  private static WorkorderRating ratingView(RatingType ratingType, UUID ratedUserId) {
    return ratingDomain(ratingType, ratedUserId);
  }

  private static WorkOrderReportView reportView(String chronological, String analyze, String corrective,
      String preventive) {
    return new WorkOrderReportView("WO-2409-00001", chronological, analyze, corrective, preventive,
        null, null, null, "https://presigned/cpk.pdf", null, null, null);
  }

  private static WorkOrderTodo todoDomain() {
    return new WorkOrderTodo(TODO_ID, "WO-2409-00001", "Fix bearing", null, null, TodoStatus.PENDING, 0,
        UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"), null);
  }

  private static WorkOrderTodo todoView() {
    return todoDomain();
  }

  private static WorkorderAttachmentView attachmentView() {
    return new WorkorderAttachmentView(ATTACHMENT_ID, "WO-2409-00001", "photo.jpg", "image/jpeg",
        "workorders/WO-2409-00001/photo.jpg", 3, UPLOADER_ID, Instant.parse("2026-08-26T00:00:00Z"), null,
        "https://presigned/key");
  }

  private static WorkOrder inProgressView() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.IN_PROGRESS, CATEGORY_ID, MACHINE_ID,
        "breakdown", null, ASSIGNEE_ID, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"),
        Instant.parse("2026-08-26T00:00:00Z"), null, null, null, null, null, null, null, null, null, null, null,
        null, null, null);
  }

  private static WorkOrder closedView() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.CLOSED, CATEGORY_ID, MACHINE_ID,
        "breakdown", null, ASSIGNEE_ID, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"),
        Instant.parse("2026-08-26T00:00:00Z"), null, null, null, null, null, null, null, null, null, null, null,
        null, null, null);
  }

  private static WorkOrder view() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.OPEN, CATEGORY_ID, MACHINE_ID, "breakdown",
        null, null, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"),
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static WorkOrder assignedView() {
    return new WorkOrder("WO-2409-00001", "INTERNAL", WorkOrderStatus.ASSIGNED, CATEGORY_ID, MACHINE_ID, "breakdown",
        null, ASSIGNEE_ID, UUID.randomUUID(), Instant.parse("2026-08-26T00:00:00Z"), Instant.parse("2026-08-26T00:00:00Z"),
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static RepairSessionsResult sessionsResult(WorkOrder workOrder) {
    return new RepairSessionsResult(workOrder, List.of(new RepairSession(
        UUID.randomUUID(), "WO-2409-00001", ASSIGNEE_ID, "diagnosis",
        Instant.parse("2026-08-26T00:00:00Z"), null, null)));
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