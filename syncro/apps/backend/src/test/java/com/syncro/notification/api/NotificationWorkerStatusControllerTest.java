package com.syncro.notification.api;

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
import com.syncro.notification.application.NotificationWorkerState;
import com.syncro.notification.application.NotificationWorkerStatus;
import com.syncro.notification.application.NotificationWorkerStatusService;
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

@WebMvcTest(NotificationWorkerStatusController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class NotificationWorkerStatusControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private NotificationWorkerStatusService statusService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void superAdminGetsNotificationWorkerStatus() throws Exception {
    when(statusService.status()).thenReturn(new NotificationWorkerStatus(
        NotificationWorkerState.RUNNING,
        "Running",
        "SUCCESS",
        null,
        "2026-08-21T08:00:00Z",
        "2026-08-21T07:59:00Z",
        null,
        2,
        0,
        null,
        "2026-08-21T07:58:00Z",
        "CLOSED"));

    mockMvc.perform(get("/api/v1/notification/worker/status")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.statusLabel").value("Running"))
        .andExpect(jsonPath("$.pendingJobCount").value(2))
        .andExpect(jsonPath("$.lastSuccessfulSendAt").value("2026-08-21T07:58:00Z"));
  }

  @Test
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/notification/worker/status")
            .with(auth(user(ApplicationRole.MANAGE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/notification/worker/status")
            .with(auth(user(ApplicationRole.VIEWER))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/notification/worker/status"))
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
