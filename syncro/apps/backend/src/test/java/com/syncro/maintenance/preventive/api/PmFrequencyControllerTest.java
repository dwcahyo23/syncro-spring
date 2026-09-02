package com.syncro.maintenance.preventive.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
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
import com.syncro.maintenance.preventive.application.PmChecksheetService;
import com.syncro.maintenance.preventive.application.PmFrequencyService;
import com.syncro.maintenance.preventive.application.PmFrequencyService.FrequencyView;
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

@WebMvcTest({PmFrequencyController.class, PmChecksheetController.class})
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PmFrequencyControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PmFrequencyService frequencies;

  @MockitoBean
  private PmChecksheetService checksheets;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID FREQ_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

  @Test
  @DisplayName("19.1-API-001 P0 GET list frequencies returns 200")
  void listFrequencies() throws Exception {
    when(frequencies.list()).thenReturn(List.of(
        new FrequencyView(FREQ_ID, "MONTHLY", "Monthly", null, 1, true, NOW, NOW)));

    mockMvc.perform(get("/api/v1/pm-frequencies")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("MONTHLY"))
        .andExpect(jsonPath("$[0].sortOrder").value(1));
  }

  @Test
  @DisplayName("19.1-API-002 P0 GET frequency by id returns 200")
  void getFrequency() throws Exception {
    when(frequencies.get(FREQ_ID)).thenReturn(
        new FrequencyView(FREQ_ID, "MONTHLY", "Monthly", null, 1, true, NOW, NOW));

    mockMvc.perform(get("/api/v1/pm-frequencies/{id}", FREQ_ID)
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("MONTHLY"));
  }

  @Test
  @DisplayName("19.1-API-003 P0 POST create frequency returns 201")
  void createFrequency() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(frequencies.create(eq(user), any())).thenReturn(
        new FrequencyView(FREQ_ID, "QUARTERLY", "Quarterly", null, 3, true, NOW, NOW));

    mockMvc.perform(post("/api/v1/pm-frequencies")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"QUARTERLY\",\"name\":\"Quarterly\",\"sortOrder\":3}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("QUARTERLY"))
        .andExpect(jsonPath("$.sortOrder").value(3));
  }

  @Test
  @DisplayName("19.1-API-004 P0 PUT update frequency returns 200")
  void updateFrequency() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(frequencies.update(eq(user), eq(FREQ_ID), any())).thenReturn(
        new FrequencyView(FREQ_ID, "MONTHLY", "Monthly v2", null, 1, false, NOW, NOW));

    mockMvc.perform(put("/api/v1/pm-frequencies/{id}", FREQ_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Monthly v2\",\"isActive\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Monthly v2"))
        .andExpect(jsonPath("$.isActive").value(false));
  }

  @Test
  @DisplayName("19.1-API-005 P0 POST create frequency missing code → 400")
  void createFrequencyMissingCode() throws Exception {
    mockMvc.perform(post("/api/v1/pm-frequencies")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"No code\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("19.1-API-006 P0 forbidden role maps to 403 FORBIDDEN")
  void createFrequencyForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new PmFrequencyService.PmFrequencyForbiddenException())
        .when(frequencies).create(eq(user), any());

    mockMvc.perform(post("/api/v1/pm-frequencies")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"X\",\"name\":\"X\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.1-API-007 P0 duplicate code maps to 409 DUPLICATE_FREQUENCY_CODE")
  void createFrequencyDuplicateCode() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(frequencies.create(eq(user), any()))
        .thenThrow(new PmFrequencyService.DuplicateFrequencyCodeException());

    mockMvc.perform(post("/api/v1/pm-frequencies")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"MONTHLY\",\"name\":\"Monthly\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_FREQUENCY_CODE"));
  }

  @Test
  @DisplayName("19.1-API-008 P0 unknown frequency maps to 404 PM_FREQUENCY_NOT_FOUND")
  void getUnknownFrequency() throws Exception {
    when(frequencies.get(FREQ_ID))
        .thenThrow(new PmFrequencyService.PmFrequencyNotFoundException());

    mockMvc.perform(get("/api/v1/pm-frequencies/{id}", FREQ_ID)
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_FREQUENCY_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.1-API-009 P0 blank-after-trim code maps to 400 VALIDATION_ERROR with fieldErrors")
  void createFrequencyBlankCode() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(frequencies.create(eq(user), any())).thenThrow(
        new PmFrequencyService.FrequencyValidationException(Map.of("code", "code must not be blank.")));

    mockMvc.perform(post("/api/v1/pm-frequencies")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"   \",\"name\":\"X\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.code").exists());
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
