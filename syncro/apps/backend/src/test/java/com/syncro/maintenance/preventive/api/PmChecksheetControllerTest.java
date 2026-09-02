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
import com.syncro.maintenance.preventive.application.PmChecksheetService;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ActiveChecksheetView;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ChecksheetView;
import com.syncro.maintenance.preventive.application.PmFrequencyService;
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

@WebMvcTest({PmFrequencyController.class, PmChecksheetController.class})
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PmChecksheetControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PmFrequencyService frequencies;

  @MockitoBean
  private PmChecksheetService checksheets;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID CS_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID MACHINE_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID FREQ_ID = UUID.fromString("ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb");
  private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

  @Test
  @DisplayName("19.1-API-010 P0 create checksheet returns 201")
  void createChecksheet() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checksheets.create(eq(user), any())).thenReturn(view(1, false, null));

    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.revisionNo").value(1))
        .andExpect(jsonPath("$.isActive").value(false));
  }

  @Test
  @DisplayName("19.1-API-011 P0 create checksheet missing machineId → 400")
  void createChecksheetMissingMachineId() throws Exception {
    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("19.1-API-012 P0 list checksheets returns 200")
  void listChecksheets() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checksheets.list(eq(user), eq(null), eq(null))).thenReturn(List.of(view(1, false, null)));

    mockMvc.perform(get("/api/v1/pm-checksheets")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].revisionNo").value(1));
  }

  @Test
  @DisplayName("19.1-API-013 P0 get checksheet returns 200")
  void getChecksheet() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checksheets.get(eq(user), eq(CS_ID))).thenReturn(view(2, true, NOW));

    mockMvc.perform(get("/api/v1/pm-checksheets/{id}", CS_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionNo").value(2))
        .andExpect(jsonPath("$.isActive").value(true));
  }

  @Test
  @DisplayName("19.1-API-014 P0 GET active checksheet returns 200")
  void getActiveChecksheet() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checksheets.getActive(eq(user), eq(MACHINE_ID), eq(FREQ_ID)))
        .thenReturn(new ActiveChecksheetView(CS_ID, MACHINE_ID, FREQ_ID, 2));

    mockMvc.perform(get("/api/v1/pm-checksheets/active")
            .param("machineId", MACHINE_ID.toString())
            .param("frequencyId", FREQ_ID.toString())
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checksheetId").value(CS_ID.toString()))
        .andExpect(jsonPath("$.revisionNo").value(2));
  }

  @Test
  @DisplayName("19.1-API-015 P0 revise checksheet returns 201")
  void reviseChecksheet() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checksheets.revise(eq(user), eq(CS_ID), any())).thenReturn(view(2, false, null));

    mockMvc.perform(post("/api/v1/pm-checksheets/{id}/revise", CS_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"revisionReason\":\"Updated\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.revisionNo").value(2));
  }

  @Test
  @DisplayName("19.1-API-015b P0 revise without body returns 201 (optional body)")
  void reviseChecksheetWithoutBody() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checksheets.revise(eq(user), eq(CS_ID), any())).thenReturn(view(2, false, null));

    mockMvc.perform(post("/api/v1/pm-checksheets/{id}/revise", CS_ID)
            .with(auth(user)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.revisionNo").value(2));
  }

  @Test
  @DisplayName("19.1-API-016 P0 approve checksheet returns 200")
  void approveChecksheet() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checksheets.approve(eq(user), eq(CS_ID), any()))
        .thenReturn(view(2, true, NOW));

    mockMvc.perform(post("/api/v1/pm-checksheets/{id}/approve", CS_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"effectiveDate\":\"2026-09-15\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revisionNo").value(2))
        .andExpect(jsonPath("$.isActive").value(true));
  }

  @Test
  @DisplayName("19.1-API-016b P0 approve without body returns 200 (optional body)")
  void approveChecksheetWithoutBody() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checksheets.approve(eq(user), eq(CS_ID), any()))
        .thenReturn(view(2, true, NOW));

    mockMvc.perform(post("/api/v1/pm-checksheets/{id}/approve", CS_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isActive").value(true));
  }

  @Test
  @DisplayName("19.1-API-017 P0 approve already-approved → 409")
  void approveAlreadyApproved() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new PmChecksheetService.InvalidChecksheetTransitionException())
        .when(checksheets).approve(eq(user), eq(CS_ID), any());

    mockMvc.perform(post("/api/v1/pm-checksheets/{id}/approve", CS_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_CHECKSHEET_TRANSITION"));
  }

  @Test
  @DisplayName("19.1-API-018 P0 unknown checksheet → 404")
  void getUnknownChecksheet() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checksheets.get(eq(user), eq(CS_ID)))
        .thenThrow(new PmChecksheetService.PmChecksheetNotFoundException());

    mockMvc.perform(get("/api/v1/pm-checksheets/{id}", CS_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKSHEET_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.1-API-019 P0 forbidden role maps to 403 FORBIDDEN")
  void createChecksheetForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(checksheets.create(eq(user), any()))
        .thenThrow(new PmChecksheetService.PmChecksheetForbiddenException());

    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.1-API-020 P0 duplicate pair maps to 409 CHECKSHEET_ALREADY_EXISTS")
  void createChecksheetAlreadyExists() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checksheets.create(eq(user), any()))
        .thenThrow(new PmChecksheetService.ChecksheetAlreadyExistsException());

    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHECKSHEET_ALREADY_EXISTS"));
  }

  @Test
  @DisplayName("19.1-API-021 P0 unknown machine maps to 404 MACHINE_NOT_FOUND")
  void createChecksheetMachineNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checksheets.create(eq(user), any()))
        .thenThrow(new PmChecksheetService.MachineNotFoundException());

    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.1-API-022 P0 unknown frequency maps to 404 PM_FREQUENCY_NOT_FOUND")
  void createChecksheetFrequencyNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checksheets.create(eq(user), any()))
        .thenThrow(new PmFrequencyService.PmFrequencyNotFoundException());

    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_FREQUENCY_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.1-API-023 P0 missing active pointer maps to 404 PM_CHECKSHEET_NOT_FOUND")
  void getActiveNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checksheets.getActive(eq(user), eq(MACHINE_ID), eq(FREQ_ID)))
        .thenThrow(new PmChecksheetService.ActiveChecksheetNotFoundException());

    mockMvc.perform(get("/api/v1/pm-checksheets/active")
            .param("machineId", MACHINE_ID.toString())
            .param("frequencyId", FREQ_ID.toString())
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKSHEET_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.1-API-025 P0 malformed UUID path value maps to 400 INVALID_PATH_VALUE")
  void getChecksheetMalformedPathValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-checksheets/{id}", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("19.1-API-026 P0 missing machineId query param maps to 400 VALIDATION_ERROR")
  void getActiveMissingParameter() throws Exception {
    mockMvc.perform(get("/api/v1/pm-checksheets/active")
            .param("frequencyId", FREQ_ID.toString())
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.machineId").exists());
  }

  @Test
  @DisplayName("19.1-API-027 P0 malformed UUID query param maps to 400 INVALID_QUERY_VALUE")
  void getActiveMalformedQueryValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-checksheets/active")
            .param("machineId", "not-a-uuid")
            .param("frequencyId", FREQ_ID.toString())
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("19.1-API-024 P0 inactive frequency maps to 400 VALIDATION_ERROR with fieldErrors")
  void createChecksheetInactiveFrequency() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checksheets.create(eq(user), any())).thenThrow(
        new PmChecksheetService.ChecksheetValidationException(
            Map.of("frequencyId", "Frequency is not active.")));

    mockMvc.perform(post("/api/v1/pm-checksheets")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"machineId\":\"" + MACHINE_ID + "\",\"frequencyId\":\"" + FREQ_ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.frequencyId").exists());
  }

  private static ChecksheetView view(int revision, boolean active, Instant approvedAt) {
    return new ChecksheetView(CS_ID, MACHINE_ID, FREQ_ID, revision, null, active,
        null, null, approvedAt, approvedAt != null ? LocalDate.of(2026, 9, 15) : null,
        UUID.randomUUID(), NOW, NOW);
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
