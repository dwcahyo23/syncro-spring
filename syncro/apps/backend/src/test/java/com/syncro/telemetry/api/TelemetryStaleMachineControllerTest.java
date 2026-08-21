package com.syncro.telemetry.api;

import static org.mockito.ArgumentMatchers.any;
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
import com.syncro.telemetry.application.StaleMachineItem;
import com.syncro.telemetry.application.StaleMachineStatus;
import com.syncro.telemetry.application.TelemetryStaleMachineService;
import java.time.Instant;
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

@WebMvcTest(TelemetryStaleMachineController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class TelemetryStaleMachineControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TelemetryStaleMachineService staleMachineService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void superAdminGetsStaleMachineEvidence() throws Exception {
    UUID machineId = UUID.randomUUID();
    when(staleMachineService.staleMachines(any())).thenReturn(new StaleMachineStatus(
        "2026-08-21T08:00:00Z",
        2,
        List.of(
            new StaleMachineItem(machineId, "AA-01", "GM1", "OFFLINE", "offline", null),
            new StaleMachineItem(UUID.randomUUID(), "ZZ-01", "GM1", "STALE", "stale",
                Instant.parse("2026-08-21T07:40:00Z")))));

    mockMvc.perform(get("/api/v1/telemetry/stale-machines")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timestamp").value("2026-08-21T08:00:00Z"))
        .andExpect(jsonPath("$.staleMachineCount").value(2))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].machineId").value(machineId.toString()))
        .andExpect(jsonPath("$.items[0].machineCode").value("AA-01"))
        .andExpect(jsonPath("$.items[0].plantCode").value("GM1"))
        .andExpect(jsonPath("$.items[0].freshnessState").value("OFFLINE"))
        .andExpect(jsonPath("$.items[0].statusLabel").value("offline"))
        .andExpect(jsonPath("$.items[0].lastReceivedAt").isEmpty())
        .andExpect(jsonPath("$.items[1].freshnessState").value("STALE"))
        .andExpect(jsonPath("$.items[1].lastReceivedAt").value("2026-08-21T07:40:00Z"));
  }

  @Test
  void superAdminGetsEmptyEvidenceWhenNothingStale() throws Exception {
    when(staleMachineService.staleMachines(any()))
        .thenReturn(new StaleMachineStatus("2026-08-21T08:00:00Z", 0, List.of()));

    mockMvc.perform(get("/api/v1/telemetry/stale-machines")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.staleMachineCount").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/stale-machines")
            .with(auth(user(ApplicationRole.MANAGE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/stale-machines")
            .with(auth(user(ApplicationRole.VIEWER))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/stale-machines"))
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
