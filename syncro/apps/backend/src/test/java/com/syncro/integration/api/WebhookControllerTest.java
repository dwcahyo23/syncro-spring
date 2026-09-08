package com.syncro.integration.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.integration.api.IntegrationDtos.DeliveryListResponse;
import com.syncro.integration.api.IntegrationDtos.DeliveryView;
import com.syncro.integration.api.IntegrationDtos.WebhookConfigView;
import com.syncro.integration.application.WebhookConfigService;
import com.syncro.integration.application.WebhookConfigService.DuplicateWebhookNameException;
import com.syncro.integration.application.WebhookConfigService.WebhookConfigNotFoundException;
import com.syncro.integration.application.WebhookConfigService.WebhookForbiddenException;
import com.syncro.integration.application.WebhookConfigService.WebhookValidationException;
import com.syncro.integration.application.WebhookDeliveryQueryService;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Story 22-1 contract coverage: the webhook surface answers with the house error
 * envelope (code/message/timestamp/traceId) for 400/401/403/404/409, returns 201 +
 * Location on create, and NEVER serializes the raw HMAC secret. Services are mocked —
 * gates and validation are unit-tested in the service tests; this locks the HTTP shape.
 */
@WebMvcTest(WebhookController.class)
@Import({SecurityConfig.class, IntegrationExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class WebhookControllerTest {

  private static final UUID CONFIG_ID = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID DELIVERY_ID = UUID.fromString("22222222-3333-4444-5555-666677778888");
  private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WebhookConfigService configService;

  @MockitoBean
  private WebhookDeliveryQueryService deliveryService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  // -- 401 ---------------------------------------------------------------------

  @Test
  @DisplayName("22.1-API-001 P0 unauthenticated list → 401 AUTHENTICATION_REQUIRED")
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/webhooks"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("22.1-API-002 P1 unauthenticated deliveries → 401 envelope")
  void unauthenticatedDeliveriesRejected() throws Exception {
    mockMvc.perform(get("/api/v1/webhook-deliveries"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // -- 403 ---------------------------------------------------------------------

  @Test
  @DisplayName("22.1-API-003 P0 MANAGER create → 403 FORBIDDEN envelope")
  void managerCreateForbidden() throws Exception {
    doThrow(new WebhookForbiddenException()).when(configService).create(any(), any());

    mockMvc.perform(post("/api/v1/webhooks")
            .with(auth(ApplicationRole.MANAGER_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("erp-hook", "https://erp.test/hook", "supersecretvalue1234")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("You do not have permission to access this resource."))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("22.1-API-004 P0 MANAGER delivery read → 403")
  void managerDeliveryReadForbidden() throws Exception {
    doThrow(new WebhookForbiddenException()).when(deliveryService)
        .list(any(), isNull(), eq(0), eq(20));

    mockMvc.perform(get("/api/v1/webhook-deliveries")
            .with(auth(ApplicationRole.MANAGER_MAINTENANCE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  // -- 201 / masking -------------------------------------------------------------

  @Test
  @DisplayName("22.1-API-005 P0 SUPER_ADMIN create → 201 + Location + masked secret only")
  void superAdminCreateReturnsMaskedView() throws Exception {
    when(configService.create(any(), any())).thenReturn(configView("****cret"));

    mockMvc.perform(post("/api/v1/webhooks")
            .with(auth(ApplicationRole.SUPER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("erp-hook", "https://erp.test/hook", "brandnewsecretvalue")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(CONFIG_ID.toString()))
        .andExpect(jsonPath("$.hmacSecretMasked").value("****cret"))
        .andExpect(jsonPath("$.hmacSecret").doesNotExist())
        .andExpect(jsonPath("$.secret").doesNotExist());
  }

  @Test
  @DisplayName("22.1-API-006 P0 create with blank name → 400 VALIDATION_ERROR fieldErrors")
  void createBlankNameIsValidationError() throws Exception {
    mockMvc.perform(post("/api/v1/webhooks")
            .with(auth(ApplicationRole.SUPER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("   ", "https://erp.test/hook", "secretvalue1234567")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty());
  }

  @Test
  @DisplayName("22.1-API-007 P1 service-level OUTBOUND validation → 400 with field errors")
  void serviceValidationMapsTo400() throws Exception {
    when(configService.create(any(), any())).thenThrow(new WebhookValidationException(
        Map.of("endpointUrl", "endpointUrl is required for OUTBOUND configs.")));

    mockMvc.perform(post("/api/v1/webhooks")
            .with(auth(ApplicationRole.SUPER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("erp-hook", null, "secretvalue1234567")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.endpointUrl").isNotEmpty());
  }

  @Test
  @DisplayName("22.1-API-008 P1 duplicate name → 409 DUPLICATE_WEBHOOK_NAME")
  void duplicateNameIsConflict() throws Exception {
    when(configService.create(any(), any())).thenThrow(new DuplicateWebhookNameException());

    mockMvc.perform(post("/api/v1/webhooks")
            .with(auth(ApplicationRole.SUPER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("erp-hook", "https://erp.test/hook", "secretvalue1234567")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_WEBHOOK_NAME"));
  }

  @Test
  @DisplayName("22.1-API-015 P1 concurrent update (lost @Version) → 409 VERSION_CONFLICT")
  void optimisticLockConflictIs409() throws Exception {
    when(configService.update(any(), eq(CONFIG_ID), any()))
        .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
            "WebhookConfigEntity", 1));

    mockMvc.perform(patch("/api/v1/webhooks/" + CONFIG_ID)
            .with(auth(ApplicationRole.SUPER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hmacSecret\":\"rotatedvalue4321\",\"active\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  // -- 404 / PATCH ---------------------------------------------------------------

  @Test
  @DisplayName("22.1-API-009 P1 unknown config → 404 WEBHOOK_CONFIG_NOT_FOUND")
  void unknownConfigIsNotFound() throws Exception {
    when(configService.get(any(), eq(CONFIG_ID))).thenThrow(new WebhookConfigNotFoundException());

    mockMvc.perform(get("/api/v1/webhooks/" + CONFIG_ID)
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"));
  }

  @Test
  @DisplayName("22.1-API-010 P1 PATCH rotate secret → 200 masked view, raw secret absent")
  void patchRotatesSecretMasked() throws Exception {
    when(configService.update(any(), eq(CONFIG_ID), any())).thenReturn(
        new WebhookConfigView(CONFIG_ID, "erp-hook", WebhookDirection.OUTBOUND,
            List.of("CLOSED"), "https://erp.test/hook", "****4321", false,
            Instant.parse("2026-09-06T08:00:00Z"), Instant.parse("2026-09-06T08:00:00Z"), 1));

    mockMvc.perform(patch("/api/v1/webhooks/" + CONFIG_ID)
            .with(auth(ApplicationRole.SUPER_ADMIN))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hmacSecret\":\"rotatedvalue4321\",\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hmacSecretMasked").value("****4321"))
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.hmacSecret").doesNotExist());
  }

  // -- delivery reads ---------------------------------------------------------------

  @Test
  @DisplayName("22.1-API-011 P0 DLQ filter → paged newest-first, no secret in body")
  void dlqFilterReturnsPagedDeliveries() throws Exception {
    when(deliveryService.list(any(), eq(WebhookDeliveryStatus.DLQ), eq(0), eq(20)))
        .thenReturn(new DeliveryListResponse(List.of(deliveryView()), 1, 1, 0, 20, "createdAt,id:desc"));

    mockMvc.perform(get("/api/v1/webhook-deliveries")
            .param("status", "DLQ")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].status").value("DLQ"))
        .andExpect(jsonPath("$.items[0].traceId").value("trace-1"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.sort").value("createdAt,id:desc"))
        .andExpect(jsonPath("$.items[0].hmacSecret").doesNotExist())
        .andExpect(jsonPath("$.items[0].payload").doesNotExist());
  }

  @Test
  @DisplayName("22.1-API-012 P1 unknown status filter → 400 VALIDATION_ERROR")
  void unknownStatusFilterIsValidationError() throws Exception {
    mockMvc.perform(get("/api/v1/webhook-deliveries")
            .param("status", "NOT_A_STATUS")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("22.1-API-013 P2 per-config deliveries list → 200")
  void perConfigDeliveriesList() throws Exception {
    when(deliveryService.listForConfig(any(), eq(CONFIG_ID), eq(0), eq(20)))
        .thenReturn(new DeliveryListResponse(List.of(), 0, 0, 0, 20, "createdAt,id:desc"));

    mockMvc.perform(get("/api/v1/webhooks/" + CONFIG_ID + "/deliveries")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  @DisplayName("22.1-API-014 P2 response_code null on PENDING rows serializes as null")
  void pendingDeliveryNullResponseCode() throws Exception {
    var pending = new DeliveryView(DELIVERY_ID, CONFIG_ID, "CLOSED", WebhookDeliveryStatus.PENDING,
        null, null, null, 0, 3, null, "trace-1", "key", Instant.parse("2026-09-06T08:00:00Z"),
        Instant.parse("2026-09-06T08:00:00Z"));
    when(deliveryService.listForConfig(any(), eq(CONFIG_ID), eq(0), eq(20)))
        .thenReturn(new DeliveryListResponse(List.of(pending), 1, 1, 0, 20, "createdAt,id:desc"));

    mockMvc.perform(get("/api/v1/webhooks/" + CONFIG_ID + "/deliveries")
            .with(auth(ApplicationRole.SUPER_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].responseCode").value(nullValue()))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"));
  }

  private static String createBody(String name, String endpoint, String secret) {
    var endpointJson = endpoint == null ? "null" : "\"" + endpoint + "\"";
    return "{\"name\":\"" + name + "\",\"direction\":\"OUTBOUND\",\"eventTypes\":[\"CLOSED\"],"
        + "\"endpointUrl\":" + endpointJson + ",\"hmacSecret\":\"" + secret + "\"}";
  }

  private static WebhookConfigView configView(String masked) {
    return new WebhookConfigView(CONFIG_ID, "erp-hook", WebhookDirection.OUTBOUND,
        List.of("CLOSED"), "https://erp.test/hook", masked, true,
        Instant.parse("2026-09-06T08:00:00Z"), Instant.parse("2026-09-06T08:00:00Z"), 0);
  }

  private static DeliveryView deliveryView() {
    return new DeliveryView(DELIVERY_ID, CONFIG_ID, "CLOSED", WebhookDeliveryStatus.DLQ,
        500, "boom", 120, 3, 3, null, "trace-1",
        CONFIG_ID + ":CLOSED:WO-2609-00001",
        Instant.parse("2026-09-06T08:00:00Z"), Instant.parse("2026-09-06T08:05:00Z"));
  }

  private static RequestPostProcessor auth(ApplicationRole role) {
    var user = new AuthenticatedUser(USER_ID.toString(), role.name().toLowerCase() + "@syncro.dev", role);
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
  }
}
