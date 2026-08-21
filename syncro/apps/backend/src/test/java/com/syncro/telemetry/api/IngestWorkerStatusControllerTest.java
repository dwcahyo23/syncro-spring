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
import com.syncro.telemetry.application.IngestWorkerState;
import com.syncro.telemetry.application.IngestWorkerStatus;
import com.syncro.telemetry.application.IngestWorkerStatusService;
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

@WebMvcTest(IngestWorkerStatusController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class IngestWorkerStatusControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private IngestWorkerStatusService statusService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void superAdminGetsIngestWorkerStatus() throws Exception {
    when(statusService.status()).thenReturn(new IngestWorkerStatus(
        IngestWorkerState.RUNNING,
        "Running",
        "SUCCESS",
        null,
        "2026-08-21T08:00:00Z",
        "SUBSCRIBED",
        "2026-08-21T07:59:00Z",
        null,
        0,
        1000,
        42));

    mockMvc.perform(get("/api/v1/telemetry/ingest/status")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.statusLabel").value("Running"))
        .andExpect(jsonPath("$.statusSeverity").value("SUCCESS"))
        .andExpect(jsonPath("$.timestamp").value("2026-08-21T08:00:00Z"))
        .andExpect(jsonPath("$.mqttState").value("SUBSCRIBED"))
        .andExpect(jsonPath("$.lastAcceptedAt").value("2026-08-21T07:59:00Z"))
        .andExpect(jsonPath("$.queueDepth").value(0))
        .andExpect(jsonPath("$.queueCapacity").value(1000))
        .andExpect(jsonPath("$.acceptedCount").value(42));
  }

  @Test
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/ingest/status")
            .with(auth(user(ApplicationRole.MANAGE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/ingest/status")
            .with(auth(user(ApplicationRole.VIEWER))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/ingest/status"))
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
