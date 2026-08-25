package com.syncro.telemetry.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.telemetry.infrastructure.TelemetryQuarantineEntity;
import com.syncro.telemetry.infrastructure.TelemetryQuarantineRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

@WebMvcTest(TelemetryQuarantineController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class TelemetryQuarantineControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TelemetryQuarantineRepository repository;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("3.11-API-001 P1 SUPER_ADMIN gets quarantine list with correct fields")
  void superAdminGetsQuarantineList() throws Exception {
    var entity = quarantineEntity("trace-001", "factory/PLANT1/M1/telemetry",
        "{\"running\":true}", "malformed_topic", null);
    when(repository.findAllByOrderByReceivedAtDesc(any()))
        .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));

    mockMvc.perform(get("/api/v1/telemetry/quarantine")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].traceId").value("trace-001"))
        .andExpect(jsonPath("$.content[0].rejectionReason").value("malformed_topic"))
        .andExpect(jsonPath("$.content[0].rejectionField").isEmpty());
  }

  @Test
  @DisplayName("3.11-API-002 P1 SUPER_ADMIN gets quarantine entry with rejectionField set")
  void quarantineEntryIncludesRejectionField() throws Exception {
    var entity = quarantineEntity("trace-002", "factory/PLANT1/M1/telemetry",
        "{}", "missing_contract_field", "schemaVersion");
    when(repository.findAllByOrderByReceivedAtDesc(any()))
        .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));

    mockMvc.perform(get("/api/v1/telemetry/quarantine")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].rejectionField").value("schemaVersion"));
  }

  @Test
  @DisplayName("3.11-API-003 P0 MANAGER_MAINTENANCE user gets 403")
  void manageUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/quarantine")
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("3.11-API-004 P0 AUDITOR user gets 403")
  void viewerUserIsForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/quarantine")
        .with(auth(user(ApplicationRole.AUDITOR))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("3.11-API-005 P0 unauthenticated gets 401")
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/telemetry/quarantine"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("3.11-API-006 P1 empty quarantine returns empty page")
  void emptyQuarantineReturnsEmptyPage() throws Exception {
    when(repository.findAllByOrderByReceivedAtDesc(any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mockMvc.perform(get("/api/v1/telemetry/quarantine")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.content").isEmpty());
  }

  @Test
  @DisplayName("3.11-API-007 P1 size param capped at 100")
  void sizeCappedAt100() throws Exception {
    when(repository.findAllByOrderByReceivedAtDesc(any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

    mockMvc.perform(get("/api/v1/telemetry/quarantine")
        .param("size", "500")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk());
  }

  // --- helpers ---

  private static TelemetryQuarantineEntity quarantineEntity(String traceId, String topic,
      String rawPayload, String reason, String field) {
    var entity = new TelemetryQuarantineEntity();
    entity.setId(UUID.randomUUID());
    entity.setTraceId(traceId);
    entity.setTopic(topic);
    entity.setRawPayload(rawPayload);
    entity.setRejectionReason(reason);
    entity.setRejectionField(field);
    entity.setReceivedAt(Instant.parse("2026-08-14T10:00:00Z"));
    entity.setCreatedAt(Instant.parse("2026-08-14T10:00:00Z"));
    return entity;
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
