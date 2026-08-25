package com.syncro.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.audit.api.AuditLogDtos.AuditLogEntryView;
import com.syncro.audit.api.AuditLogDtos.AuditLogListResponse;
import com.syncro.audit.application.AuditLogService;
import com.syncro.audit.application.AuditLogService.AuditLogQuery;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(AuditLogController.class)
@Import({SecurityConfig.class, AuditLogExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class AuditLogControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuditLogService auditLog;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.9-API-001 P1 audit log listing returns the full entry shape")
  void returnsFullEntryShape() throws Exception {
    var entry = new AuditLogEntryView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "yusuf",
        AuditAction.UPDATE,
        AuditEntityType.MACHINE,
        UUID.randomUUID(),
        "BF-08410",
        UUID.randomUUID(),
        Map.of("status", "ACTIVE"),
        Map.of("status", "INACTIVE"),
        Instant.parse("2026-08-07T08:00:00Z"));
    when(auditLog.list(any(), any())).thenReturn(new AuditLogListResponse(List.of(entry), 1, 0, 100, "createdAt,desc"));

    mockMvc.perform(get("/api/v1/audit-log")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(100))
        .andExpect(jsonPath("$.sort").value("createdAt,desc"))
        .andExpect(jsonPath("$.items[0].actorId").isNotEmpty())
        .andExpect(jsonPath("$.items[0].actorName").value("yusuf"))
        .andExpect(jsonPath("$.items[0].action").value("UPDATE"))
        .andExpect(jsonPath("$.items[0].entityType").value("MACHINE"))
        .andExpect(jsonPath("$.items[0].entityId").isNotEmpty())
        .andExpect(jsonPath("$.items[0].entityLabel").value("BF-08410"))
        .andExpect(jsonPath("$.items[0].plantId").isNotEmpty())
        .andExpect(jsonPath("$.items[0].previousValue.status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].newValue.status").value("INACTIVE"))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-07T08:00:00Z"));
  }

  @Test
  @DisplayName("2.9-API-002 P1 filter parameters bind to the audit log query")
  void filterParametersBindToQuery() throws Exception {
    var plantId = UUID.randomUUID();
    when(auditLog.list(any(), any())).thenReturn(new AuditLogListResponse(List.of(), 0, 2, 25, "actorName,asc"));

    mockMvc.perform(get("/api/v1/audit-log")
        .param("entityType", "MACHINE")
        .param("actor", "yusuf")
        .param("plantId", plantId.toString())
        .param("from", "2026-08-01T00:00:00Z")
        .param("to", "2026-08-07T23:59:59Z")
        .param("page", "2")
        .param("size", "25")
        .param("sort", "actorName,asc")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk());

    var captor = ArgumentCaptor.forClass(AuditLogQuery.class);
    verify(auditLog).list(any(), captor.capture());
    var query = captor.getValue();
    assertThat(query.entityType()).isEqualTo(AuditEntityType.MACHINE);
    assertThat(query.actor()).isEqualTo("yusuf");
    assertThat(query.plantId()).isEqualTo(plantId);
    assertThat(query.from()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(query.to()).isEqualTo(Instant.parse("2026-08-07T23:59:59Z"));
    assertThat(query.page()).isEqualTo(2);
    assertThat(query.size()).isEqualTo(25);
    assertThat(query.sort()).isEqualTo("actorName,asc");
  }

  @Test
  @DisplayName("2.9-API-003 P1 out-of-scope plant filter returns safe 403")
  void outOfScopePlantFilterReturnsForbidden() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new PlantAccessDeniedException()).when(auditLog).list(eq(user), any());

    mockMvc.perform(get("/api/v1/audit-log")
        .param("plantId", UUID.randomUUID().toString())
        .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.9-API-004 P1 EMPTY scope returns an empty list without error")
  void emptyScopeReturnsEmptyList() throws Exception {
    when(auditLog.list(any(), any())).thenReturn(new AuditLogListResponse(List.of(), 0, 0, 100, "createdAt,desc"));

    mockMvc.perform(get("/api/v1/audit-log")
        .with(auth(user(ApplicationRole.AUDITOR))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @DisplayName("2.9-API-005 P1 unknown entity type filter returns 400")
  void unknownEntityTypeReturnsBadRequest() throws Exception {
    mockMvc.perform(get("/api/v1/audit-log")
        .param("entityType", "FOO")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("2.9-API-006 P0 unauthenticated users cannot list the audit log")
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/audit-log"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
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
