package com.syncro.notification.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import com.syncro.notification.api.WhatsAppMessageLogDtos.MessageLogListResponse;
import com.syncro.notification.api.WhatsAppMessageLogDtos.MessageLogView;
import com.syncro.notification.application.WhatsAppMessageLogQueryService;
import com.syncro.notification.application.WhatsAppMessageLogQueryService.WhatsAppMessageLogForbiddenException;
import com.syncro.notification.domain.WhatsAppMessageDirection;
import com.syncro.notification.domain.WhatsAppMessageLogStatus;
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

/**
 * Story 22-4 contract coverage: the message-log read answers with the house error
 * envelope for 401/403/400, returns a paged masked projection for SUPER_ADMIN, and
 * NEVER serializes a raw phone, raw message text, or WAHA secret. The service is
 * mocked — the gate is unit-tested in the query service; this locks the HTTP shape.
 */
@WebMvcTest(WhatsAppMessageLogController.class)
@Import({SecurityConfig.class, WhatsAppMessageLogExceptionHandler.class,
    JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class WhatsAppMessageLogControllerTest {

  private static final UUID LOG_ID = UUID.fromString("33333333-4444-5555-6666-777788889999");
  private static final UUID JOB_ID = UUID.fromString("44444444-5555-6666-7777-888899990000");
  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WhatsAppMessageLogQueryService queryService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  // -- 401 ---------------------------------------------------------------------

  @Test
  @DisplayName("22.4-API-001 P0 unauthenticated list → 401 AUTHENTICATION_REQUIRED")
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/whatsapp-message-logs"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // -- 403 ---------------------------------------------------------------------

  @Test
  @DisplayName("22.4-API-002 P0 MANAGER read → 403 FORBIDDEN envelope")
  void managerReadForbidden() throws Exception {
    doThrow(new WhatsAppMessageLogForbiddenException()).when(queryService)
        .list(any(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));

    mockMvc.perform(get("/api/v1/whatsapp-message-logs")
            .with(auth(ApplicationRole.MANAGER_MAINTENANCE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("You do not have permission to access this resource."))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  // -- 200 + masked projection ----------------------------------------------------

  @Test
  @DisplayName("22.4-API-003 P0 SUPER_ADMIN status filter → paged newest-first, masked, job link")
  void superAdminFilteredReadReturnsMaskedPage() throws Exception {
    when(queryService.list(any(), eq(WhatsAppMessageLogStatus.FAILED), isNull(), isNull(),
        isNull(), eq(0), eq(20)))
        .thenReturn(new MessageLogListResponse(List.of(logView()), 1, 1, 0, 20,
            "sentAt:desc:nullslast,loggedAt:desc,id:desc"));

    mockMvc.perform(get("/api/v1/whatsapp-message-logs")
            .param("status", "FAILED")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("FAILED"))
        .andExpect(jsonPath("$.items[0].direction").value("OUTBOUND"))
        .andExpect(jsonPath("$.items[0].recipientMasked").value("628***890"))
        .andExpect(jsonPath("$.items[0].notificationJobId").value(JOB_ID.toString()))
        .andExpect(jsonPath("$.items[0].traceId").value("trace-1"))
        .andExpect(jsonPath("$.items[0].textSha256").value("a".repeat(64)))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.sort").value("sentAt:desc:nullslast,loggedAt:desc,id:desc"))
        // raw phone / raw text / secret must never appear
        .andExpect(jsonPath("$.items[0].recipientPhone").doesNotExist())
        .andExpect(jsonPath("$.items[0].text").doesNotExist())
        .andExpect(jsonPath("$.items[0].fromPhone").doesNotExist())
        .andExpect(jsonPath("$.items[0].payload").doesNotExist());
  }

  @Test
  @DisplayName("22.4-API-004 P1 all four filters pass through to the query service")
  void filtersPassThrough() throws Exception {
    when(queryService.list(any(), eq(WhatsAppMessageLogStatus.SENT), eq("WORK_ORDER"),
        eq("WO-2609-00001"), eq("trace-9"), eq(1), eq(50)))
        .thenReturn(new MessageLogListResponse(List.of(), 0, 0, 1, 50,
            "sentAt:desc:nullslast,loggedAt:desc,id:desc"));

    mockMvc.perform(get("/api/v1/whatsapp-message-logs")
            .param("status", "SENT")
            .param("targetType", "WORK_ORDER")
            .param("workOrderId", "WO-2609-00001")
            .param("traceId", "trace-9")
            .param("page", "1")
            .param("size", "50")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(50));
  }

  @Test
  @DisplayName("22.4-API-005 P1 unknown status filter → 400 VALIDATION_ERROR")
  void unknownStatusFilterIsValidationError() throws Exception {
    mockMvc.perform(get("/api/v1/whatsapp-message-logs")
            .param("status", "NOT_A_STATUS")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static MessageLogView logView() {
    return new MessageLogView(LOG_ID, WhatsAppMessageDirection.OUTBOUND, JOB_ID, "ALERT",
        UUID.randomUUID().toString(), "alert_notification", "628***890",
        WhatsAppMessageLogStatus.FAILED, 2, "trace-1", "a".repeat(64), null, USER_ID,
        UUID.randomUUID() + "::TECHNICIAN", null,
        java.time.Instant.parse("2026-09-08T08:00:00Z"), null, 1);
  }

  private static RequestPostProcessor auth(ApplicationRole role) {
    var user = new AuthenticatedUser(USER_ID.toString(), role.name().toLowerCase() + "@syncro.dev", role);
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }
}
