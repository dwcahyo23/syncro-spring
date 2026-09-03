package com.syncro.maintenance.preventive.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.syncro.maintenance.preventive.application.PmScheduleService;
import com.syncro.maintenance.preventive.application.PmScheduleService.ScheduleDateView;
import com.syncro.maintenance.preventive.application.PmScheduleService.ScheduleView;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import java.time.Instant;
import java.time.LocalDate;
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
 * Story 19-3 MockMvc contract test: DTO shape, status codes, and every new
 * exception→HTTP mapping for the /api/v1/pm-schedules surface (PmChecksheetControllerTest
 * error-path pattern — the service is stubbed to throw and the handler asserts status + code).
 */
@WebMvcTest(PmScheduleController.class)
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PmScheduleControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PmScheduleService schedules;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID SCHEDULE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID DATE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID PLANT_ID = UUID.fromString("ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb");
  private static final UUID MACHINE_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
  private static final UUID CS_ID = UUID.fromString("44444444-3333-2222-1111-000000000000");
  private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

  // -------------------------------------------------------------------------
  // Happy paths
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.3-API-001 P0 create schedule returns 201 with view shape")
  void createSchedule() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any())).thenReturn(view(PmScheduleStatus.DRAFT));

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(SCHEDULE_ID.toString()))
        .andExpect(jsonPath("$.year").value(2027))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.dates[0].status").value("SCHEDULED"));
  }

  @Test
  @DisplayName("19.3-API-002 P0 create schedule missing year → 400 VALIDATION_ERROR")
  void createScheduleMissingYear() throws Exception {
    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.year").exists());
  }

  @Test
  @DisplayName("19.3-API-003 P0 create schedule year out of range → 400 VALIDATION_ERROR")
  void createScheduleYearOutOfRange() throws Exception {
    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":1999}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("19.3-API-004 P0 list schedules returns 200 with filters")
  void listSchedules() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(schedules.list(eq(user), eq(2027), eq(PmScheduleStatus.DRAFT)))
        .thenReturn(List.of(view(PmScheduleStatus.DRAFT)));

    mockMvc.perform(get("/api/v1/pm-schedules")
            .param("year", "2027")
            .param("status", "DRAFT")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].year").value(2027))
        .andExpect(jsonPath("$[0].status").value("DRAFT"));
  }

  @Test
  @DisplayName("19.3-API-005 P0 get schedule returns 200 with dates")
  void getSchedule() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(schedules.get(eq(user), eq(SCHEDULE_ID))).thenReturn(view(PmScheduleStatus.ACTIVE));

    mockMvc.perform(get("/api/v1/pm-schedules/{id}", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.dates[0].id").value(DATE_ID.toString()));
  }

  @Test
  @DisplayName("19.3-API-006 P0 submit returns 200 PENDING_SPV_APPROVAL")
  void submitSchedule() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.submit(eq(user), eq(SCHEDULE_ID)))
        .thenReturn(view(PmScheduleStatus.PENDING_SPV_APPROVAL));

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/submit", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_SPV_APPROVAL"));
  }

  @Test
  @DisplayName("19.3-API-007 P0 approve-spv returns 200 PENDING_PRODUCTION_APPROVAL")
  void approveSpv() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(schedules.approveSpv(eq(user), eq(SCHEDULE_ID)))
        .thenReturn(view(PmScheduleStatus.PENDING_PRODUCTION_APPROVAL));

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/approve-spv", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_PRODUCTION_APPROVAL"));
  }

  @Test
  @DisplayName("19.3-API-008 P0 approve-prod returns 200 APPROVED")
  void approveProd() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(schedules.approveProd(eq(user), eq(SCHEDULE_ID)))
        .thenReturn(view(PmScheduleStatus.APPROVED));

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/approve-prod", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  @DisplayName("19.3-API-009 P0 activate returns 200 ACTIVE")
  void activateSchedule() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    when(schedules.activate(eq(user), eq(SCHEDULE_ID)))
        .thenReturn(view(PmScheduleStatus.ACTIVE));

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/activate", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("19.3-API-010 P0 date transition returns 200 with updated status")
  void transitionDate() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(schedules.transitionDate(eq(user), eq(SCHEDULE_ID), eq(DATE_ID),
        eq(PmScheduleDateStatus.EXECUTED)))
        .thenReturn(dateView(PmScheduleDateStatus.EXECUTED));

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/dates/{dateId}/transition", SCHEDULE_ID, DATE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"EXECUTED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(DATE_ID.toString()))
        .andExpect(jsonPath("$.status").value("EXECUTED"));
  }

  @Test
  @DisplayName("19.3-API-011 P0 date transition unknown status → 400 VALIDATION_ERROR")
  void transitionDateUnknownStatus() throws Exception {
    mockMvc.perform(post("/api/v1/pm-schedules/{id}/dates/{dateId}/transition", SCHEDULE_ID, DATE_ID)
            .with(auth(ApplicationRole.MANAGER_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"DONE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // -------------------------------------------------------------------------
  // Error paths — every new exception→HTTP mapping (stubbed service throws)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.3-API-012 P0 unknown schedule → 404 PM_SCHEDULE_NOT_FOUND")
  void getUnknownSchedule() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(schedules.get(eq(user), eq(SCHEDULE_ID)))
        .thenThrow(new PmScheduleService.PmScheduleNotFoundException());

    mockMvc.perform(get("/api/v1/pm-schedules/{id}", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_SCHEDULE_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.3-API-013 P0 unknown date → 404 PM_SCHEDULE_DATE_NOT_FOUND")
  void transitionDateNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(schedules.transitionDate(eq(user), eq(SCHEDULE_ID), eq(DATE_ID), any()))
        .thenThrow(new PmScheduleService.PmScheduleDateNotFoundException());

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/dates/{dateId}/transition", SCHEDULE_ID, DATE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"EXECUTED\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_SCHEDULE_DATE_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.3-API-014 P0 duplicate schedule → 409 SCHEDULE_ALREADY_EXISTS")
  void createScheduleAlreadyExists() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any()))
        .thenThrow(new PmScheduleService.ScheduleAlreadyExistsException());

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SCHEDULE_ALREADY_EXISTS"));
  }

  @Test
  @DisplayName("19.3-API-015 P0 invalid checksheet state → 409 INVALID_CHECKSHEET_STATE")
  void createScheduleInvalidChecksheetState() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any()))
        .thenThrow(new PmScheduleService.InvalidChecksheetStateException());

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_CHECKSHEET_STATE"));
  }

  @Test
  @DisplayName("19.3-API-016 P0 out-of-order approval → 409 INVALID_SCHEDULE_TRANSITION")
  void approveSpvInvalidTransition() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new PmScheduleService.InvalidScheduleTransitionException())
        .when(schedules).approveSpv(eq(user), eq(SCHEDULE_ID));

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/approve-spv", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_TRANSITION"));
  }

  @Test
  @DisplayName("19.3-API-017 P0 invalid date transition → 409 INVALID_SCHEDULE_DATE_TRANSITION")
  void transitionDateInvalid() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(schedules.transitionDate(eq(user), eq(SCHEDULE_ID), eq(DATE_ID), any()))
        .thenThrow(new PmScheduleService.InvalidScheduleDateTransitionException());

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/dates/{dateId}/transition", SCHEDULE_ID, DATE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"MISSED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE_DATE_TRANSITION"));
  }

  @Test
  @DisplayName("19.3-API-018 P0 forbidden → 403 FORBIDDEN")
  void submitForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(schedules.submit(eq(user), eq(SCHEDULE_ID)))
        .thenThrow(new PmScheduleService.PmScheduleForbiddenException());

    mockMvc.perform(post("/api/v1/pm-schedules/{id}/submit", SCHEDULE_ID)
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.3-API-019 P0 unknown machine → 404 MACHINE_NOT_FOUND")
  void createScheduleMachineNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any()))
        .thenThrow(new PmScheduleService.MachineNotFoundException());

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.3-API-020 P0 unknown checksheet → 404 PM_CHECKSHEET_NOT_FOUND")
  void createScheduleChecksheetNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any()))
        .thenThrow(new PmScheduleService.PmChecksheetNotFoundException());

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKSHEET_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.3-API-021 P0 unknown plant → 404 PLANT_NOT_FOUND")
  void createSchedulePlantNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any()))
        .thenThrow(new PmScheduleService.PlantNotFoundException());

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.3-API-022 P0 machine of another plant → 400 VALIDATION_ERROR")
  void createScheduleMachineWrongPlant() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(schedules.create(eq(user), any())).thenThrow(
        new PmScheduleService.ScheduleValidationException(
            Map.of("machineId", "Machine does not belong to this plant.")));

    mockMvc.perform(post("/api/v1/pm-schedules")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"machineId\":\"" + MACHINE_ID
                + "\",\"checksheetId\":\"" + CS_ID + "\",\"year\":2027}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.machineId").exists());
  }

  @Test
  @DisplayName("19.3-API-023 P0 malformed UUID path value → 400 INVALID_PATH_VALUE")
  void getScheduleMalformedPathValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-schedules/{id}", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("19.3-API-024 P0 malformed UUID query param → 400 INVALID_QUERY_VALUE")
  void listSchedulesMalformedQueryValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-schedules")
            .param("year", "not-a-year")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("19.3-API-025 P0 year query param out of range → 400 VALIDATION_ERROR")
  void listSchedulesYearOutOfRange() throws Exception {
    mockMvc.perform(get("/api/v1/pm-schedules")
            .param("year", "3000")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("19.3-API-026 P0 unknown status query param → 400 INVALID_QUERY_VALUE")
  void listSchedulesUnknownStatus() throws Exception {
    mockMvc.perform(get("/api/v1/pm-schedules")
            .param("status", "NOT_A_STATUS")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private static ScheduleView view(PmScheduleStatus status) {
    return new ScheduleView(SCHEDULE_ID, PLANT_ID, MACHINE_ID, CS_ID, 1,
        UUID.randomUUID(), "MONTHLY", "Monthly", 2027, status,
        status == PmScheduleStatus.PENDING_SPV_APPROVAL ? UUID.randomUUID() : null,
        status == PmScheduleStatus.PENDING_SPV_APPROVAL ? NOW : null,
        status == PmScheduleStatus.PENDING_PRODUCTION_APPROVAL ? UUID.randomUUID() : null,
        status == PmScheduleStatus.PENDING_PRODUCTION_APPROVAL ? NOW : null,
        status == PmScheduleStatus.APPROVED ? UUID.randomUUID() : null,
        status == PmScheduleStatus.APPROVED ? NOW : null,
        null, NOW, NOW, List.of(dateView(PmScheduleDateStatus.SCHEDULED)));
  }

  private static ScheduleDateView dateView(PmScheduleDateStatus status) {
    return new ScheduleDateView(DATE_ID, SCHEDULE_ID, LocalDate.of(2027, 1, 15), status, NOW, NOW);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }

  private static RequestPostProcessor auth(ApplicationRole role) {
    return auth(user(role));
  }
}
