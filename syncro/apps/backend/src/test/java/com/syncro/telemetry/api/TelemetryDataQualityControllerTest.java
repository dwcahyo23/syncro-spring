package com.syncro.telemetry.api;

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
import com.syncro.telemetry.application.LatencyState;
import com.syncro.telemetry.application.TelemetryDataQualityService;
import com.syncro.telemetry.application.TelemetryDataQualityState;
import com.syncro.telemetry.application.TelemetryDataQualityStatus;
import java.util.List;
import java.util.UUID;
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

@WebMvcTest(TelemetryDataQualityController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class TelemetryDataQualityControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TelemetryDataQualityService dataQualityService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void superAdminGetsDegradedDataQualityContract() throws Exception {
    when(dataQualityService.status()).thenReturn(new TelemetryDataQualityStatus(
        TelemetryDataQualityState.DEGRADED,
        "Degraded",
        "WARNING",
        "rejection rate 2.5% above 1.0%",
        "2026-08-22T10:00:00Z",
        3600,
        25,
        2.5,
        3,
        0,
        1000,
        "WARNING",
        "WARNING",
        "WARNING",
        "SUCCESS",
        5200L,
        LatencyState.ELEVATED,
        "WARNING"));

    mockMvc.perform(get("/api/v1/telemetry/data-quality")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEGRADED"))
        .andExpect(jsonPath("$.statusLabel").value("Degraded"))
        .andExpect(jsonPath("$.statusSeverity").value("WARNING"))
        .andExpect(jsonPath("$.statusReason").value("rejection rate 2.5% above 1.0%"))
        .andExpect(jsonPath("$.timestamp").value("2026-08-22T10:00:00Z"))
        .andExpect(jsonPath("$.windowSeconds").value(3600))
        .andExpect(jsonPath("$.quarantinedCount").value(25))
        .andExpect(jsonPath("$.rejectionRatePct").value(2.5))
        .andExpect(jsonPath("$.anomalyCount").value(3))
        .andExpect(jsonPath("$.deadLetterCount").value(0))
        .andExpect(jsonPath("$.receivedCount").value(1000))
        .andExpect(jsonPath("$.quarantinedSeverity").value("WARNING"))
        .andExpect(jsonPath("$.rejectionRateSeverity").value("WARNING"))
        .andExpect(jsonPath("$.anomalySeverity").value("WARNING"))
        .andExpect(jsonPath("$.deadLetterSeverity").value("SUCCESS"))
        .andExpect(jsonPath("$.lastLatencyMs").value(5200))
        .andExpect(jsonPath("$.latencyState").value("ELEVATED"))
        .andExpect(jsonPath("$.latencySeverity").value("WARNING"));
  }

  @Test
  void superAdminGetsNoDataLatencyContract() throws Exception {
    when(dataQualityService.status()).thenReturn(new TelemetryDataQualityStatus(
        TelemetryDataQualityState.GOOD,
        "Good",
        "SUCCESS",
        null,
        "2026-08-22T10:00:00Z",
        3600,
        0,
        0.0,
        0,
        0,
        0,
        "SUCCESS",
        "SUCCESS",
        "SUCCESS",
        "SUCCESS",
        null,
        LatencyState.NO_DATA,
        "NEUTRAL"));

    mockMvc.perform(get("/api/v1/telemetry/data-quality")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("GOOD"))
        .andExpect(jsonPath("$.statusReason").isEmpty())
        .andExpect(jsonPath("$.lastLatencyMs").isEmpty())
        .andExpect(jsonPath("$.latencyState").value("NO_DATA"))
        .andExpect(jsonPath("$.latencySeverity").value("NEUTRAL"));
  }

  @Test
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/data-quality")
            .with(auth(user(ApplicationRole.MANAGE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/data-quality")
            .with(auth(user(ApplicationRole.VIEWER))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/data-quality"))
        .andExpect(status().isUnauthorized());
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(
        UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}
