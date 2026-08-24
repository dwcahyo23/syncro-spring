package com.syncro.projection.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.projection.api.ProjectionDtos.InstallationProjection;
import com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView;
import com.syncro.projection.application.SparepartProjectionService;
import com.syncro.projection.application.SparepartProjectionService.MachineNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ProjectionController.class)
@Import({SecurityConfig.class, ProjectionExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class ProjectionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartProjectionService projections;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    var principal = new UsernamePasswordAuthenticationToken(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
    return SecurityMockMvcRequestPostProcessors.authentication(principal);
  }

  private static MachineSparepartProjectionsView happyView(UUID machineId) {
    var now = Instant.parse("2026-08-24T10:00:00Z");
    return new MachineSparepartProjectionsView(machineId, true,
        com.syncro.projection.application.CounterRateEstimator.CalculationBasis.FULL_HISTORY,
        now.minusSeconds(10800), now, now.minusSeconds(10800), now.minusSeconds(600),
        null, new BigDecimal("1200.00"), "MACHINE", new BigDecimal("24.00"),
        List.of(new InstallationProjection(UUID.randomUUID(), UUID.randomUUID(), "Feeder",
            true, null, 1000L, now.plusSeconds(2988), new BigDecimal("36.5"), 43800L)));
  }

  @Test
  @DisplayName("8.6-API-001 P0 GET projections returns 200 with rate and installation rows")
  void getProjectionsReturnsView() throws Exception {
    var user = new AuthenticatedUser("u-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var machineId = UUID.randomUUID();
    when(projections.getProjections(eq(user), eq(machineId))).thenReturn(happyView(machineId));

    mockMvc.perform(get("/api/v1/machines/{machineId}/sparepart-projections", machineId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.machineId").value(machineId.toString()))
        .andExpect(jsonPath("$.rateAvailable").value(true))
        .andExpect(jsonPath("$.calculationBasis").value("FULL_HISTORY"))
        .andExpect(jsonPath("$.ratePerOperatingHour").value(1200.00))
        .andExpect(jsonPath("$.shiftSource").value("MACHINE"))
        .andExpect(jsonPath("$.dailyOperatingHours").value(24.00))
        .andExpect(jsonPath("$.projections.length()").value(1))
        .andExpect(jsonPath("$.projections[0].available").value(true))
        .andExpect(jsonPath("$.projections[0].remainingCounters").value(1000))
        .andExpect(jsonPath("$.projections[0].leadTimeHours").value(36.5))
        .andExpect(jsonPath("$.projections[0].consumptionDuringLeadTime").value(43800));
  }

  @Test
  @DisplayName("8.6-API-002 P0 insufficient data still returns 200 with explicit reason")
  void insufficientDataReturnsExplicitReason() throws Exception {
    var user = new AuthenticatedUser("u-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var machineId = UUID.randomUUID();
    when(projections.getProjections(eq(user), eq(machineId)))
        .thenReturn(new MachineSparepartProjectionsView(machineId, false, null, null, null, null,
            null, com.syncro.projection.application.CounterRateEstimator.InsufficientReason.NO_TELEMETRY,
            null, "NONE", BigDecimal.ZERO.setScale(2), List.of()));

    mockMvc.perform(get("/api/v1/machines/{machineId}/sparepart-projections", machineId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rateAvailable").value(false))
        .andExpect(jsonPath("$.insufficientReason").value("NO_TELEMETRY"))
        .andExpect(jsonPath("$.ratePerOperatingHour").doesNotExist());
  }

  @Test
  @DisplayName("8.6-API-003 P0 wrong-plant machine maps to 403 FORBIDDEN")
  void wrongPlantForbidden() throws Exception {
    var user = new AuthenticatedUser("u-2", "other@syncro.dev", ApplicationRole.MANAGE);
    var machineId = UUID.randomUUID();
    when(projections.getProjections(eq(user), eq(machineId))).thenThrow(new PlantAccessDeniedException());

    mockMvc.perform(get("/api/v1/machines/{machineId}/sparepart-projections", machineId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.6-API-004 P0 unknown machine maps to 404 MACHINE_NOT_FOUND")
  void unknownMachineNotFound() throws Exception {
    var user = new AuthenticatedUser("u-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    var machineId = UUID.randomUUID();
    when(projections.getProjections(eq(user), eq(machineId))).thenThrow(new MachineNotFoundException());

    mockMvc.perform(get("/api/v1/machines/{machineId}/sparepart-projections", machineId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.6-API-005 malformed path uuid maps to 400 INVALID_PATH_VALUE")
  void invalidPathValue() throws Exception {
    var user = new AuthenticatedUser("u-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/machines/{machineId}/sparepart-projections", "not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("8.6-API-006 unauthenticated request maps to 401")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/machines/{machineId}/sparepart-projections", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
