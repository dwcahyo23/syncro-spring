package com.syncro.alert.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.alert.application.SparepartAlertCommandService;
import com.syncro.alert.application.SparepartAlertQueryService;
import com.syncro.alert.application.SparepartAlertQueryService.AlertDetailView;
import com.syncro.alert.application.SparepartAlertQueryService.AlertListView;
import com.syncro.alert.application.SparepartAlertQueryService.AlertNotFoundException;
import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.notification.application.NotificationHistoryQueryService;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
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

@WebMvcTest(SparepartAlertController.class)
@Import({SecurityConfig.class, SparepartAlertExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class SparepartAlertControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartAlertQueryService alertQuery;

  @MockitoBean
  private SparepartAlertCommandService alertCommand;

  @MockitoBean
  private NotificationHistoryQueryService notificationHistoryQuery;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    var principal = new UsernamePasswordAuthenticationToken(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
    return SecurityMockMvcRequestPostProcessors.authentication(principal);
  }

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser("u-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private static AlertDetailView procurementRiskDetail() {
    var now = Instant.parse("2026-08-24T10:00:00Z");
    return new AlertDetailView(
        UUID.randomUUID(), UUID.randomUUID(), "BF-08410", "JBF19",
        UUID.randomUUID(), "GM1", "Plant GM1",
        UUID.randomUUID(), "Forming",
        UUID.randomUUID(), UUID.randomUUID(), "BF-08410GM1ELEPLCWEC000", "Electric · PLC · Wecon · LX5",
        "Primary", SparepartAlertType.PROCUREMENT_RISK, null, 1000L, 4600L,
        null, null, null, SparepartAlertStatus.OPEN, null, "trace-1", now, now,
        new BigDecimal("36.50"), new BigDecimal("1200.00"), CalculationBasis.FULL_HISTORY,
        now.plusSeconds(3600), null);
  }

  private static AlertDetailView thresholdDetail() {
    var now = Instant.parse("2026-08-24T10:00:00Z");
    return new AlertDetailView(
        UUID.randomUUID(), UUID.randomUUID(), "BF-08410", "JBF19",
        UUID.randomUUID(), "GM1", "Plant GM1",
        UUID.randomUUID(), "Forming",
        UUID.randomUUID(), UUID.randomUUID(), "BF-08410GM1ELEPLCWEC000", "Electric · PLC · Wecon · LX5",
        "Primary", SparepartAlertType.THRESHOLD_PERCENTAGE, 90, 1000L, 4600L,
        4140L, 3140L, new BigDecimal("78.50"), SparepartAlertStatus.OPEN, null, "trace-2", now, now,
        null, null, null, null, null);
  }

  @Test
  @DisplayName("8.7-API-001 P0 GET alert serializes alertType and procurement evidence with null threshold")
  void getProcurementRiskAlertSerializesTypeAndEvidence() throws Exception {
    var user = superAdmin();
    var alertId = UUID.randomUUID();
    when(alertQuery.get(eq(user), eq(alertId))).thenReturn(procurementRiskDetail());

    mockMvc.perform(get("/api/v1/alerts/{alertId}", alertId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertType").value("PROCUREMENT_RISK"))
        .andExpect(jsonPath("$.thresholdPercentage").doesNotExist())
        .andExpect(jsonPath("$.leadTimeHours").value(36.50))
        .andExpect(jsonPath("$.ratePerOperatingHour").value(1200.00))
        .andExpect(jsonPath("$.calculationBasis").value("FULL_HISTORY"))
        .andExpect(jsonPath("$.projectedDepletionAt").value("2026-08-24T11:00:00Z"))
        .andExpect(jsonPath("$.currentCounterSnapshot").doesNotExist())
        .andExpect(jsonPath("$.consumedPercentageSnapshot").doesNotExist());
  }

  @Test
  @DisplayName("8.7-API-002 P0 GET threshold alert serializes threshold and null evidence")
  void getThresholdAlertSerializesNullEvidence() throws Exception {
    var user = superAdmin();
    var alertId = UUID.randomUUID();
    when(alertQuery.get(eq(user), eq(alertId))).thenReturn(thresholdDetail());

    mockMvc.perform(get("/api/v1/alerts/{alertId}", alertId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertType").value("THRESHOLD_PERCENTAGE"))
        .andExpect(jsonPath("$.thresholdPercentage").value(90))
        .andExpect(jsonPath("$.consumedPercentageSnapshot").value(78.50))
        .andExpect(jsonPath("$.leadTimeHours").doesNotExist())
        .andExpect(jsonPath("$.ratePerOperatingHour").doesNotExist())
        .andExpect(jsonPath("$.projectedDepletionAt").doesNotExist());
  }

  @Test
  @DisplayName("8.7-API-003 P0 GET list serializes the type discriminator")
  void listSerializesAlertType() throws Exception {
    var user = superAdmin();
    var detail = procurementRiskDetail();
    when(alertQuery.list(eq(user), eq(null), eq(null), eq(null), eq(0), eq(50), eq("createdAt,desc")))
        .thenReturn(new AlertListView(List.of(detail), 1L, 0, 50, "createdAt,desc"));

    mockMvc.perform(get("/api/v1/alerts").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].alertType").value("PROCUREMENT_RISK"))
        .andExpect(jsonPath("$.items[0].thresholdPercentage").doesNotExist())
        .andExpect(jsonPath("$.items[0].leadTimeHours").value(36.50));
  }

  @Test
  @DisplayName("8.7-API-004 unknown alert maps to 404 ALERT_NOT_FOUND")
  void unknownAlertNotFound() throws Exception {
    var user = superAdmin();
    var alertId = UUID.randomUUID();
    when(alertQuery.get(eq(user), eq(alertId))).thenThrow(new AlertNotFoundException());

    mockMvc.perform(get("/api/v1/alerts/{alertId}", alertId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.7-API-005 out-of-scope alert maps to 403 FORBIDDEN")
  void outOfScopeAlertForbidden() throws Exception {
    var user = new AuthenticatedUser("u-2", "other@syncro.dev", ApplicationRole.MANAGER_MAINTENANCE);
    var alertId = UUID.randomUUID();
    when(alertQuery.get(eq(user), eq(alertId))).thenThrow(new PlantAccessDeniedException());

    mockMvc.perform(get("/api/v1/alerts/{alertId}", alertId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.7-API-006 unauthenticated request maps to 401")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/alerts/{alertId}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
