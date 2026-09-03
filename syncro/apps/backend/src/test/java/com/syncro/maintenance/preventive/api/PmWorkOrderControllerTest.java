package com.syncro.maintenance.preventive.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.syncro.maintenance.preventive.application.PmWorkOrderService;
import com.syncro.maintenance.preventive.application.PmWorkOrderService.WorkOrderView;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * Story 19-4 MockMvc contract test: DTO shape, status codes, and every new
 * exception→HTTP mapping for the /api/v1/pm-work-orders surface
 * (PmScheduleControllerTest error-path pattern — the service is stubbed to throw
 * and the handler asserts status + code).
 */
@WebMvcTest(PmWorkOrderController.class)
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PmWorkOrderControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PmWorkOrderService workOrders;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID WO_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID SCHEDULE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID MACHINE_ID = UUID.fromString("ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb");
  private static final UUID TEMPLATE_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
  private static final UUID TECH_ID = UUID.fromString("44444444-3333-2222-1111-000000000000");
  private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

  // -------------------------------------------------------------------------
  // Happy paths
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-API-001 P0 generate returns 200 with created workorder list")
  void generate() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.generate(eq(user), eq(SCHEDULE_ID)))
        .thenReturn(List.of(view(PmWorkOrderStatus.SCHEDULED)));

    mockMvc.perform(post("/api/v1/pm-work-orders/generate")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleId\":\"" + SCHEDULE_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(WO_ID.toString()))
        .andExpect(jsonPath("$[0].status").value("SCHEDULED"))
        .andExpect(jsonPath("$[0].scheduledDate").value("2027-01-15"))
        .andExpect(jsonPath("$[0].templateRevision").value(1));
  }

  @Test
  @DisplayName("19.4-API-002 P0 generate missing scheduleId → 400 VALIDATION_ERROR")
  void generateMissingScheduleId() throws Exception {
    mockMvc.perform(post("/api/v1/pm-work-orders/generate")
            .with(auth(ApplicationRole.SECTION_LEADER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.scheduleId").exists());
  }

  @Test
  @DisplayName("19.4-API-003 P0 assign returns 200 ASSIGNED with technician stamp")
  void assign() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(workOrders.assign(eq(user), eq(WO_ID), eq(TECH_ID)))
        .thenReturn(view(PmWorkOrderStatus.ASSIGNED));

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/assign", WO_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"technicianId\":\"" + TECH_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.assignedTechnicianId").value(TECH_ID.toString()));
  }

  @Test
  @DisplayName("19.4-API-004 P0 start returns 200 IN_PROGRESS")
  void start() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.start(eq(user), eq(WO_ID))).thenReturn(view(PmWorkOrderStatus.IN_PROGRESS));

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/start", WO_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.startedAt").exists());
  }

  @Test
  @DisplayName("19.4-API-005 P0 complete returns 200 COMPLETED with certificate")
  void complete() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.complete(eq(user), eq(WO_ID), eq("https://cert/x.pdf")))
        .thenReturn(view(PmWorkOrderStatus.COMPLETED));

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/complete", WO_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"certificateUrl\":\"https://cert/x.pdf\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.certificateUrl").value("https://cert/x.pdf"));
  }

  @Test
  @DisplayName("19.4-API-006 P0 complete without body returns 200 (certificateUrl optional)")
  void completeWithoutBody() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.complete(eq(user), eq(WO_ID), eq(null)))
        .thenReturn(view(PmWorkOrderStatus.COMPLETED));

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/complete", WO_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  @DisplayName("19.4-API-007 P0 sweep-overdue returns 200 with count")
  void sweepOverdue() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    when(workOrders.sweepOverdue(eq(user))).thenReturn(3);

    mockMvc.perform(post("/api/v1/pm-work-orders/sweep-overdue")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overdueCount").value(3));
  }

  @Test
  @DisplayName("19.4-API-008 P0 list returns 200 with filters")
  void list() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(workOrders.list(eq(user), eq(PmWorkOrderStatus.SCHEDULED), eq(MACHINE_ID)))
        .thenReturn(List.of(view(PmWorkOrderStatus.SCHEDULED)));

    mockMvc.perform(get("/api/v1/pm-work-orders")
            .param("status", "SCHEDULED")
            .param("machineId", MACHINE_ID.toString())
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
  }

  @Test
  @DisplayName("19.4-API-009 P0 get returns 200 with view shape")
  void getWorkOrder() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(workOrders.get(eq(user), eq(WO_ID))).thenReturn(view(PmWorkOrderStatus.OVERDUE));

    mockMvc.perform(get("/api/v1/pm-work-orders/{id}", WO_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(WO_ID.toString()))
        .andExpect(jsonPath("$.status").value("OVERDUE"))
        .andExpect(jsonPath("$.machineId").value(MACHINE_ID.toString()));
  }

  // -------------------------------------------------------------------------
  // Error paths — every new exception→HTTP mapping (stubbed service throws)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.4-API-010 P0 unknown workorder → 404 PM_WORK_ORDER_NOT_FOUND")
  void getUnknownWorkOrder() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(workOrders.get(eq(user), eq(WO_ID)))
        .thenThrow(new PmWorkOrderService.PmWorkOrderNotFoundException());

    mockMvc.perform(get("/api/v1/pm-work-orders/{id}", WO_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_WORK_ORDER_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.4-API-011 P0 unknown schedule on generate → 404 PM_SCHEDULE_NOT_FOUND")
  void generateScheduleNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.generate(eq(user), eq(SCHEDULE_ID)))
        .thenThrow(new PmWorkOrderService.PmScheduleNotFoundException());

    mockMvc.perform(post("/api/v1/pm-work-orders/generate")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleId\":\"" + SCHEDULE_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_SCHEDULE_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.4-API-012 P0 unknown technician on assign → 404 TECHNICIAN_NOT_FOUND")
  void assignTechnicianNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.assign(eq(user), eq(WO_ID), any()))
        .thenThrow(new PmWorkOrderService.TechnicianNotFoundException());

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/assign", WO_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"technicianId\":\"" + TECH_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TECHNICIAN_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.4-API-013 P0 non-ACTIVE schedule on generate → 409 INVALID_SCHEDULE_STATE")
  void generateInvalidScheduleState() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.generate(eq(user), eq(SCHEDULE_ID)))
        .thenThrow(new PmWorkOrderService.InvalidScheduleStateException());

    mockMvc.perform(post("/api/v1/pm-work-orders/generate")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleId\":\"" + SCHEDULE_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_STATE"));
  }

  @Test
  @DisplayName("19.4-API-014 P0 duplicate period on generate → 409 WORK_ORDER_PERIOD_EXISTS")
  void generatePeriodExists() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(workOrders.generate(eq(user), eq(SCHEDULE_ID)))
        .thenThrow(new PmWorkOrderService.WorkOrderPeriodExistsException());

    mockMvc.perform(post("/api/v1/pm-work-orders/generate")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleId\":\"" + SCHEDULE_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WORK_ORDER_PERIOD_EXISTS"));
  }

  @Test
  @DisplayName("19.4-API-015 P0 out-of-order transition → 409 INVALID_WORK_ORDER_TRANSITION")
  void startInvalidTransition() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.start(eq(user), eq(WO_ID)))
        .thenThrow(new PmWorkOrderService.InvalidWorkOrderTransitionException());

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/start", WO_ID)
            .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_WORK_ORDER_TRANSITION"));
  }

  @Test
  @DisplayName("19.4-API-016 P0 non-assignee start → 403 FORBIDDEN")
  void startForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(workOrders.start(eq(user), eq(WO_ID)))
        .thenThrow(new PmWorkOrderService.PmWorkOrderForbiddenException());

    mockMvc.perform(post("/api/v1/pm-work-orders/{id}/start", WO_ID)
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.4-API-017 P0 unknown machine on list → 404 MACHINE_NOT_FOUND")
  void listMachineNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(workOrders.list(eq(user), any(), eq(MACHINE_ID)))
        .thenThrow(new PmWorkOrderService.MachineNotFoundException());

    mockMvc.perform(get("/api/v1/pm-work-orders")
            .param("machineId", MACHINE_ID.toString())
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.4-API-018 P0 unknown status query param → 400 INVALID_QUERY_VALUE")
  void listUnknownStatus() throws Exception {
    mockMvc.perform(get("/api/v1/pm-work-orders")
            .param("status", "NOT_A_STATUS")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("19.4-API-019 P0 malformed UUID path value → 400 INVALID_PATH_VALUE")
  void getMalformedPathValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-work-orders/{id}", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("19.4-API-020 P0 malformed UUID body field → 400 VALIDATION_ERROR")
  void generateMalformedBody() throws Exception {
    mockMvc.perform(post("/api/v1/pm-work-orders/generate")
            .with(auth(ApplicationRole.SECTION_LEADER))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"scheduleId\":\"not-a-uuid\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private static WorkOrderView view(PmWorkOrderStatus status) {
    return new WorkOrderView(WO_ID, MACHINE_ID, TEMPLATE_ID, UUID.randomUUID(),
        "MONTHLY", "Monthly", 1, status,
        status == PmWorkOrderStatus.SCHEDULED ? null : TECH_ID,
        LocalDate.of(2027, 1, 15),
        status == PmWorkOrderStatus.SCHEDULED || status == PmWorkOrderStatus.ASSIGNED
            ? null : NOW,
        status == PmWorkOrderStatus.COMPLETED ? NOW : null,
        status == PmWorkOrderStatus.COMPLETED ? "https://cert/x.pdf" : null,
        NOW, NOW);
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
