package com.syncro.sync.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import com.syncro.sync.application.SyncStatusService;
import com.syncro.sync.application.SyncStatusView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(SyncStatusController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class SyncStatusControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SyncStatusService statusService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final Instant LAST_RUN = Instant.parse("2026-08-28T01:00:00Z");

  @Test
  void superAdminGetsSyncStatus() throws Exception {
    when(statusService.getStatus()).thenReturn(new SyncStatusView(
        LAST_RUN, "SUCCESS", 10, 8, 2, null, 2,
        Instant.parse("2026-08-28T01:30:00Z")));

    mockMvc.perform(get("/api/v1/sync/status")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.lastRunAt").value("2026-08-28T01:00:00Z"))
        .andExpect(jsonPath("$.rowsRead").value(10))
        .andExpect(jsonPath("$.rowsUpserted").value(8))
        .andExpect(jsonPath("$.rowsRejected").value(2))
        .andExpect(jsonPath("$.errorMessage").value(nullValue()))
        .andExpect(jsonPath("$.quarantinedCount").value(2))
        .andExpect(jsonPath("$.lastQuarantinedAt").value("2026-08-28T01:30:00Z"));
  }

  @Test
  void neverRunReportsNeverRunStatus() throws Exception {
    when(statusService.getStatus()).thenReturn(SyncStatusView.neverRun());

    mockMvc.perform(get("/api/v1/sync/status")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEVER_RUN"))
        .andExpect(jsonPath("$.lastRunAt").value(nullValue()))
        .andExpect(jsonPath("$.rowsRead").value(0))
        .andExpect(jsonPath("$.quarantinedCount").value(0));
  }

  @Test
  void superAdminListsQuarantineWithoutRawPayload() throws Exception {
    var id = UUID.randomUUID();
    var row = new SyncStatusDtos.SyncQuarantineListRow(
        id, "EXT-00004", "TERMINAL_STATE_PROTECTED", "trace-1",
        Instant.parse("2026-08-28T01:30:00Z"));
    when(statusService.listQuarantine(any()))
        .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

    mockMvc.perform(get("/api/v1/sync/quarantine")
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].sheetNo").value("EXT-00004"))
        .andExpect(jsonPath("$.content[0].reason").value("TERMINAL_STATE_PROTECTED"))
        .andExpect(jsonPath("$.content[0].rawPayload").doesNotExist())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void superAdminGetsQuarantineDetailWithRawPayload() throws Exception {
    var id = UUID.randomUUID();
    var detail = new SyncStatusDtos.SyncQuarantineDetailView(
        id, "EXT-00004", "PARENT_CLOSED", Map.of("sheetNo", "EXT-00004"), "trace-1",
        Instant.parse("2026-08-28T01:30:00Z"));
    when(statusService.getQuarantine(id)).thenReturn(Optional.of(detail));

    mockMvc.perform(get("/api/v1/sync/quarantine/" + id)
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.sheetNo").value("EXT-00004"))
        .andExpect(jsonPath("$.reason").value("PARENT_CLOSED"))
        .andExpect(jsonPath("$.rawPayload.sheetNo").value("EXT-00004"))
        .andExpect(jsonPath("$.traceId").value("trace-1"));
  }

  @Test
  void quarantineDetailNotFoundReturns404() throws Exception {
    var id = UUID.randomUUID();
    when(statusService.getQuarantine(id)).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/v1/sync/quarantine/" + id)
            .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isNotFound());
  }

  @Test
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/sync/status")
            .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/sync/quarantine")
            .with(auth(user(ApplicationRole.AUDITOR))))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/sync/status"))
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