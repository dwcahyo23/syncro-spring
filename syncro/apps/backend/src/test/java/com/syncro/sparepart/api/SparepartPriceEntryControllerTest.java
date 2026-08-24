package com.syncro.sparepart.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.sparepart.application.SparepartPriceEntryService;
import com.syncro.sparepart.application.SparepartPriceEntryService.DataIntegrityException;
import com.syncro.sparepart.application.SparepartPriceEntryService.MutationForbiddenException;
import com.syncro.sparepart.application.SparepartPriceEntryService.NotFoundException;
import com.syncro.sparepart.application.SparepartPriceEntryService.SparepartPriceEntryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest(SparepartPriceEntryController.class)
@Import({SecurityConfig.class, SparepartPriceEntryExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class SparepartPriceEntryControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartPriceEntryService priceEntries;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("8.3-API-001 P0 MANAGE appends a price entry and receives 201 with Location")
  void manageAppendsPriceEntry() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var sparepartId = UUID.randomUUID();
    var entryId = UUID.randomUUID();
    when(priceEntries.create(eq(user), eq(sparepartId), any())).thenReturn(view(entryId, sparepartId));

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", sparepartId).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1500000}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(entryId.toString()))
        .andExpect(jsonPath("$.sparepartId").value(sparepartId.toString()))
        .andExpect(jsonPath("$.amount").value(1500000.00))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.kursToIdr").value(1))
        .andExpect(jsonPath("$.idrAmount").value(1500000.00))
        .andExpect(jsonPath("$.enteredByName").value("manage@syncro.dev"));
  }

  @Test
  @DisplayName("8.3-API-002 P0 invalid request returns VALIDATION_ERROR with field errors")
  void invalidRequestReturnsFieldErrors() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":-5,\"currency\":\"usd\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.amount").exists())
        .andExpect(jsonPath("$.fieldErrors.currency").exists())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("8.3-API-003 P1 missing amount fails bean validation")
  void missingAmountFailsValidation() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currency\":\"USD\",\"kursToIdr\":15500}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.amount").exists());
  }

  @Test
  @DisplayName("8.3-API-004 P0 below-LEADER job scope returns JOB_SCOPE_REQUIRED with explanation")
  void belowLeaderJobScopeReturnsExplanation() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new JobScopeForbiddenException("LEADER")).when(priceEntries).create(eq(user), any(), any());

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1000}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("JOB_SCOPE_REQUIRED"))
        .andExpect(jsonPath("$.message").value("This action requires job scope LEADER or above."));
  }

  @Test
  @DisplayName("8.3-API-005 P0 VIEWER is forbidden by the app-role gate")
  void viewerForbiddenByAppRoleGate() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new MutationForbiddenException()).when(priceEntries).create(eq(user), any(), any());

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1000}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.3-API-006 P1 unknown sparepart returns SPAREPART_NOT_FOUND on create and list")
  void unknownSparepartReturnsNotFound() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    doThrow(new NotFoundException()).when(priceEntries).create(eq(user), any(), any());

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1000}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));

    doThrow(new NotFoundException()).when(priceEntries).list(eq(user), any());

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.3-API-007 P1 data integrity conflict returns 409 with stable code")
  void dataIntegrityConflictReturnsConflict() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new DataIntegrityException()).when(priceEntries).create(eq(user), any(), any());

    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1000}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SPAREPART_PRICE_ENTRY_DATA_INTEGRITY_VIOLATION"));
  }

  @Test
  @DisplayName("8.3-API-008 P1 list returns newest-first history array")
  void listReturnsHistoryArray() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var sparepartId = UUID.randomUUID();
    when(priceEntries.list(eq(user), eq(sparepartId))).thenReturn(List.of(view(UUID.randomUUID(), sparepartId)));

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}/price-entries", sparepartId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].currency").value("IDR"))
        .andExpect(jsonPath("$[0].enteredByName").value("manage@syncro.dev"));
  }

  @Test
  @DisplayName("8.3-API-009 P1 empty history returns empty array")
  void emptyHistoryReturnsEmptyArray() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    when(priceEntries.list(eq(user), any())).thenReturn(List.of());

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  @DisplayName("8.3-API-010 P0 unauthenticated append is rejected")
  void unauthenticatedAppendRejected() throws Exception {
    mockMvc.perform(post("/api/v1/spareparts/{sparepartId}/price-entries", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":1000}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  private static SparepartPriceEntryView view(UUID entryId, UUID sparepartId) {
    return new SparepartPriceEntryView(
        entryId,
        sparepartId,
        new BigDecimal("1500000"),
        "IDR",
        BigDecimal.ONE,
        new BigDecimal("1500000.00"),
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "manage@syncro.dev",
        Instant.parse("2026-08-24T00:00:00Z"));
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
