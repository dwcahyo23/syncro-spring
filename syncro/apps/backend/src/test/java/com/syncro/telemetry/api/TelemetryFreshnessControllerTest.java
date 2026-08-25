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
import com.syncro.telemetry.application.TelemetryFreshnessState;
import com.syncro.telemetry.application.TelemetryFreshnessStatus;
import com.syncro.telemetry.application.TelemetryFreshnessService;
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

@WebMvcTest(TelemetryFreshnessController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class TelemetryFreshnessControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TelemetryFreshnessService freshnessService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void superAdminGetsTelemetryFreshness() throws Exception {
    when(freshnessService.freshness()).thenReturn(new TelemetryFreshnessStatus(
        TelemetryFreshnessState.LIVE,
        "Live",
        "SUCCESS",
        null,
        "2026-08-21T08:00:00Z",
        "2026-08-21T07:59:00Z",
        null));

    mockMvc.perform(get("/api/v1/telemetry/freshness")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIVE"))
        .andExpect(jsonPath("$.statusLabel").value("Live"))
        .andExpect(jsonPath("$.statusSeverity").value("SUCCESS"))
        .andExpect(jsonPath("$.timestamp").value("2026-08-21T08:00:00Z"))
        .andExpect(jsonPath("$.lastAcceptedAt").value("2026-08-21T07:59:00Z"))
        .andExpect(jsonPath("$.staleSince").isEmpty());
  }

  @Test
  void superAdminGetsNoDataFreshnessContract() throws Exception {
    when(freshnessService.freshness()).thenReturn(new TelemetryFreshnessStatus(
        TelemetryFreshnessState.NO_DATA,
        "No data",
        "NEUTRAL",
        TelemetryFreshnessService.NO_DATA_REASON,
        "2026-08-21T08:00:00Z",
        null,
        null));

    mockMvc.perform(get("/api/v1/telemetry/freshness")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NO_DATA"))
        .andExpect(jsonPath("$.statusLabel").value("No data"))
        .andExpect(jsonPath("$.statusSeverity").value("NEUTRAL"))
        .andExpect(jsonPath("$.statusReason").value(TelemetryFreshnessService.NO_DATA_REASON))
        .andExpect(jsonPath("$.lastAcceptedAt").isEmpty())
        .andExpect(jsonPath("$.staleSince").isEmpty());
  }

  @Test
  void superAdminGetsStaleFreshnessContract() throws Exception {
    when(freshnessService.freshness()).thenReturn(new TelemetryFreshnessStatus(
        TelemetryFreshnessState.STALE,
        "Stale",
        "WARNING",
        "No telemetry accepted since 2026-08-21T07:50:00Z",
        "2026-08-21T08:00:00Z",
        "2026-08-21T07:50:00Z",
        "2026-08-21T07:55:00Z"));

    mockMvc.perform(get("/api/v1/telemetry/freshness")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("STALE"))
        .andExpect(jsonPath("$.statusLabel").value("Stale"))
        .andExpect(jsonPath("$.statusSeverity").value("WARNING"))
        .andExpect(jsonPath("$.statusReason").value("No telemetry accepted since 2026-08-21T07:50:00Z"))
        .andExpect(jsonPath("$.lastAcceptedAt").value("2026-08-21T07:50:00Z"))
        .andExpect(jsonPath("$.staleSince").value("2026-08-21T07:55:00Z"));
  }

  @Test
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/freshness")
            .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/freshness")
            .with(auth(user(ApplicationRole.AUDITOR))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/freshness"))
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