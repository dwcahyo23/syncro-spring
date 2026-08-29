package com.syncro.maintenance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.maintenance.application.DashboardService;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import java.time.Instant;
import java.time.LocalDate;
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

@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, DashboardExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class DashboardControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DashboardService dashboards;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("14.1-API-001 P0 unauthenticated users cannot read dashboards")
  void unauthenticatedCannotReadDashboards() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/machines"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mockMvc.perform(get("/api/v1/dashboard/workorders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mockMvc.perform(get("/api/v1/dashboard/preventive"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("14.1-API-002 P1 machine dashboard returns rows and passes plantId")
  void machineDashboardReturnsRows() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var row = new DashboardDtos.MachineDashboardRow(machineId, "BF-08410", "JBF19", "ACTIVE",
        "Forming", "GM1", "Plant GM1", 2L, 1L,
        new DashboardDtos.TelemetryState("ONLINE", true, 123.4, 1000L,
            Instant.parse("2026-08-10T10:00:00Z")),
        new DashboardDtos.LifetimeRiskView("82.00", "80", "AT_RISK"));
    when(dashboards.machineDashboard(user, plantId))
        .thenReturn(new DashboardDtos.MachineDashboardResponse(List.of(row)));

    mockMvc.perform(get("/api/v1/dashboard/machines")
        .param("plantId", plantId.toString())
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].machineId").value(machineId.toString()))
        .andExpect(jsonPath("$.items[0].code").value("BF-08410"))
        .andExpect(jsonPath("$.items[0].openWorkOrderCount").value(2))
        .andExpect(jsonPath("$.items[0].openAlertCount").value(1))
        .andExpect(jsonPath("$.items[0].telemetryFreshness.freshnessState").value("ONLINE"))
        .andExpect(jsonPath("$.items[0].lifetimeRisk.status").value("AT_RISK"));
  }

  @Test
  @DisplayName("14.1-API-003 P1 workorder dashboard returns counts and passes filters")
  void workorderDashboardReturnsCounts() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    var sectionId = UUID.randomUUID();
    when(dashboards.workorderDashboard(eq(user), eq(plantId), eq(sectionId), eq(WorkOrderStatus.OPEN), eq("BRK")))
        .thenReturn(new DashboardDtos.WorkorderDashboardResponse(
            3L,
            List.of(new DashboardDtos.StatusCount("OPEN", 2L), new DashboardDtos.StatusCount("DONE", 1L)),
            List.of(new DashboardDtos.CategoryCount("BRK", "Breakdown", 2L))));

    mockMvc.perform(get("/api/v1/dashboard/workorders")
        .param("plantId", plantId.toString())
        .param("sectionId", sectionId.toString())
        .param("status", "OPEN")
        .param("categoryCode", "BRK")
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.byStatus[0].status").value("OPEN"))
        .andExpect(jsonPath("$.byStatus[0].count").value(2))
        .andExpect(jsonPath("$.byCategory[0].categoryCode").value("BRK"))
        .andExpect(jsonPath("$.byCategory[0].count").value(2));
  }

  @Test
  @DisplayName("14.1-API-003b P1 workorder dashboard passes null filters when omitted")
  void workorderDashboardPassesNullFilters() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(dashboards.workorderDashboard(eq(user), eq(null), eq(null), eq(null), eq(null)))
        .thenReturn(new DashboardDtos.WorkorderDashboardResponse(0L, List.of(), List.of()));

    mockMvc.perform(get("/api/v1/dashboard/workorders").with(auth(user)))
        .andExpect(status().isOk());

    verify(dashboards).workorderDashboard(eq(user), eq(null), eq(null), eq(null), eq(null));
  }

  @Test
  @DisplayName("14.1-API-004 P1 preventive dashboard returns due/overdue counts")
  void preventiveDashboardReturnsCounts() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var scheduleId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    when(dashboards.preventiveDashboard(user, null))
        .thenReturn(new DashboardDtos.PreventiveDashboardResponse(
            1L,
            1L,
            List.of(new DashboardDtos.PreventiveUpcomingRow(scheduleId, machineId, UUID.randomUUID(),
                LocalDate.now(), "SCHEDULED", "OVERDUE", "MECHANICAL", "MONTHLY",
                "MC-001", "Machine 1", "Monthly Check"))));

    mockMvc.perform(get("/api/v1/dashboard/preventive").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dueCount").value(1))
        .andExpect(jsonPath("$.overdueCount").value(1))
        .andExpect(jsonPath("$.upcoming[0].derivedStatus").value("OVERDUE"))
        .andExpect(jsonPath("$.upcoming[0].category").value("MECHANICAL"))
        .andExpect(jsonPath("$.upcoming[0].machineCode").value("MC-001"))
        .andExpect(jsonPath("$.upcoming[0].programTitle").value("Monthly Check"));
  }

  @Test
  @DisplayName("14.1-API-005 P1 invalid plantId returns safe query error")
  void invalidPlantIdReturnsSafeQueryError() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/machines")
        .param("plantId", "not-a-uuid")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("14.1-API-006 P1 invalid status returns safe query error")
  void invalidStatusReturnsSafeQueryError() throws Exception {
    mockMvc.perform(get("/api/v1/dashboard/workorders")
        .param("status", "NOT_A_STATUS")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
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