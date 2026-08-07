package com.syncro.audit.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.audit.application.AuditLogService;
import com.syncro.audit.application.AuditLogService.InvalidAuditLogQueryException;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
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

@WebMvcTest(AuditLogController.class)
@Import({SecurityConfig.class, AuditLogExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class AuditLogAtddGapApiScaffoldTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuditLogService auditLog;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @Disabled("RED - 2.9-API-007 acceptance lock for R-2.9-8; activate once the gap run reaches it")
  @DisplayName("2.9-API-007 P1 invalid sort property returns 400")
  void invalidSortReturnsBadRequest() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    doThrow(new InvalidAuditLogQueryException()).when(auditLog).list(eq(user), any());

    mockMvc.perform(get("/api/v1/audit-log")
        .param("sort", "plantId,asc")
        .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @Disabled("RED - 2.9-API-008 acceptance lock for R-2.9-8; activate once the gap run reaches it")
  @DisplayName("2.9-API-008 P1 malformed from date returns 400")
  void malformedFromReturnsBadRequest() throws Exception {
    mockMvc.perform(get("/api/v1/audit-log")
        .param("from", "not-a-date")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @Disabled("RED - 2.9-API-009 acceptance lock for R-2.9-8; activate once the gap run reaches it")
  @DisplayName("2.9-API-009 P1 malformed to date returns 400")
  void malformedToReturnsBadRequest() throws Exception {
    mockMvc.perform(get("/api/v1/audit-log")
        .param("to", "not-a-date")
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @Disabled("RED - 2.9-API-010 acceptance lock for R-2.9-1 API immutability; activate once the gap run reaches it")
  @DisplayName("2.9-API-010 P1 no write endpoints exist for the audit log")
  void noWriteEndpointsForAuditLog() throws Exception {
    var admin = user(ApplicationRole.SUPER_ADMIN);
    mockMvc.perform(put("/api/v1/audit-log").with(auth(admin)))
        .andExpect(status().isMethodNotAllowed());
    mockMvc.perform(delete("/api/v1/audit-log").with(auth(admin)))
        .andExpect(status().isMethodNotAllowed());
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
