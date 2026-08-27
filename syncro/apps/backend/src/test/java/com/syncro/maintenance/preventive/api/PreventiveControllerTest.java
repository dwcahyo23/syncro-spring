package com.syncro.maintenance.preventive.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService;
import com.syncro.maintenance.preventive.application.PreventiveProgramService;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.CreateProgramCommand;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.MachineNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveValidationException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.ProgramNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveReportService;
import com.syncro.maintenance.preventive.application.PreventiveScheduleService;
import com.syncro.maintenance.preventive.application.PreventiveScheduleService.ScheduleView;
import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.PreventiveProgram;
import com.syncro.maintenance.preventive.domain.ScheduleType;
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

@WebMvcTest({PreventiveProgramController.class, PreventiveScheduleController.class})
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PreventiveControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PreventiveProgramService programs;

  @MockitoBean
  private PreventiveScheduleService schedules;

  @MockitoBean
  private PreventiveChecklistService checklists;

  @MockitoBean
  private PreventiveEvidenceService evidence;

  @MockitoBean
  private PreventiveReportService reportService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID PROGRAM_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
  private static final UUID MACHINE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Test
  @DisplayName("11.1-API-001 P0 create program returns 201")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(programs.create(eq(user), any(CreateProgramCommand.class))).thenReturn(programDomain());

    mockMvc.perform(post("/api/v1/preventive-programs")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"category\":\"MECHANICAL\",\"scheduleType\":\"MONTHLY\","
                + "\"dayOfMonth\":15,\"title\":\"Monthly lube\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(PROGRAM_ID.toString()))
        .andExpect(jsonPath("$.category").value("MECHANICAL"));
  }

  @Test
  @DisplayName("11.1-API-002 P0 create program with invalid enum maps to 400 VALIDATION_ERROR")
  void createInvalidCategory() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    mockMvc.perform(post("/api/v1/preventive-programs")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"category\":\"BOGUS\",\"scheduleType\":\"MONTHLY\","
                + "\"dayOfMonth\":15,\"title\":\"P\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("11.1-API-003 P0 create program without access maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new PreventiveForbiddenException()).when(programs).create(eq(user), any(CreateProgramCommand.class));

    mockMvc.perform(post("/api/v1/preventive-programs")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"category\":\"MECHANICAL\",\"scheduleType\":\"MONTHLY\","
                + "\"dayOfMonth\":15,\"title\":\"P\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("11.1-API-004 P0 create program on unknown machine maps to 404 MACHINE_NOT_FOUND")
  void createUnknownMachine() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new MachineNotFoundException()).when(programs).create(eq(user), any(CreateProgramCommand.class));

    mockMvc.perform(post("/api/v1/preventive-programs")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"category\":\"MECHANICAL\",\"scheduleType\":\"MONTHLY\","
                + "\"dayOfMonth\":15,\"title\":\"P\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("11.1-API-005 P0 list programs returns 200")
  void listReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(programs.list(user)).thenReturn(List.of(programDomain()));

    mockMvc.perform(get("/api/v1/preventive-programs").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Monthly lube"));
  }

  @Test
  @DisplayName("11.1-API-006 P0 update program returns 200")
  void updateReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(programs.update(eq(user), eq(PROGRAM_ID.toString()), any())).thenReturn(programDomain());

    mockMvc.perform(put("/api/v1/preventive-programs/{id}", PROGRAM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"dayOfMonth\":20,\"title\":\"Renamed\",\"active\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Monthly lube"));
  }

  @Test
  @DisplayName("11.1-API-007 P0 update unknown program maps to 404 PROGRAM_NOT_FOUND")
  void updateNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new ProgramNotFoundException()).when(programs).update(eq(user), eq(PROGRAM_ID.toString()), any());

    mockMvc.perform(put("/api/v1/preventive-programs/{id}", PROGRAM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"dayOfMonth\":20,\"title\":\"Renamed\",\"active\":true}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PROGRAM_NOT_FOUND"));
  }

  @Test
  @DisplayName("11.1-API-008 P0 delete program returns 204")
  void deleteReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    mockMvc.perform(delete("/api/v1/preventive-programs/{id}", PROGRAM_ID).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("11.1-API-009 P0 generate schedules returns 200")
  void generateReturnsOk() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);

    mockMvc.perform(post("/api/v1/preventive-programs/{id}/generate", PROGRAM_ID).with(auth(user)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("11.1-API-010 P0 list schedules returns 200 with derived status and shift context")
  void listSchedulesReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    var view = new ScheduleView(UUID.randomUUID(), PROGRAM_ID, MACHINE_ID, LocalDate.of(2026, 9, 15),
        "SCHEDULED", "SCHEDULED", null, null, "MECHANICAL", "MONTHLY", null, LocalDate.of(2026, 8, 27),
        "NONE");
    when(schedules.list(user)).thenReturn(List.of(view));

    mockMvc.perform(get("/api/v1/preventive-schedules").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].derivedStatus").value("SCHEDULED"))
        .andExpect(jsonPath("$[0].dueDate").value("2026-09-15"));
  }

  @Test
  @DisplayName("11.1-API-011 P0 validation exception maps to 400 VALIDATION_ERROR with fieldErrors")
  void createValidationError() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new PreventiveValidationException(Map.of("monthOfYear", "Month of year is required for ANNUAL schedules.")))
        .when(programs).create(eq(user), any(CreateProgramCommand.class));

    mockMvc.perform(post("/api/v1/preventive-programs")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"category\":\"MECHANICAL\",\"scheduleType\":\"ANNUAL\","
                + "\"dayOfMonth\":15,\"title\":\"P\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.monthOfYear").exists());
  }

  private static PreventiveProgram programDomain() {
    return new PreventiveProgram(PROGRAM_ID, MACHINE_ID, PreventiveCategory.MECHANICAL, ScheduleType.MONTHLY,
        15, null, "Monthly lube", null, true, false, UUID.randomUUID(), Instant.parse("2026-08-27T00:00:00Z"),
        Instant.parse("2026-08-27T00:00:00Z"));
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