package com.syncro.sparepart.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.sparepart.application.MachineSparepartInstallationService;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationDataIntegrityException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationMutationForbiddenException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationNotFoundException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationView;
import com.syncro.sparepart.application.MachineSparepartInstallationService.MachineForInstallationNotFoundException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.SparepartForInstallationNotFoundException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.TaxonomyRefView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@WebMvcTest(MachineSparepartInstallationController.class)
@Import({SecurityConfig.class, MachineSparepartInstallationExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class MachineSparepartInstallationControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MachineSparepartInstallationService installations;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.6-API-001 P0 unauthenticated users cannot list installations")
  void listRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/machine-sparepart-installations"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.6-API-002 P0 VIEWER can list installations with nullable telemetry evidence")
  void viewerCanListInstallations() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var installationId = UUID.randomUUID();
    when(installations.list(eq(user), any())).thenReturn(List.of(view(installationId)));

    mockMvc.perform(get("/api/v1/machine-sparepart-installations").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(installationId.toString()))
        .andExpect(jsonPath("$.items[0].machineCode").value("BF-08410"))
        .andExpect(jsonPath("$.items[0].sparepartCode").value("PLC-WECON-LX5"))
        .andExpect(jsonPath("$.items[0].expectedProductionCount").value(1000000))
        .andExpect(jsonPath("$.items[0].baselineCounter").value(1200))
        .andExpect(jsonPath("$.items[0].currentCount").isEmpty())
        .andExpect(jsonPath("$.items[0].consumedProductionCount").isEmpty())
        .andExpect(jsonPath("$.items[0].consumedPercentage").isEmpty())
        .andExpect(jsonPath("$.items[0].thresholdPercentage").value(90))
        .andExpect(jsonPath("$.items[0].calculationBasis").value("COUNTER_BASED"));
  }

  @Test
  @DisplayName("2.6-API-003 P0 MANAGE can create installation")
  void manageCanCreateInstallation() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var installationId = UUID.randomUUID();
    when(installations.create(eq(user), any())).thenReturn(view(installationId));

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(90)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(installationId.toString()))
        .andExpect(jsonPath("$.thresholdPercentage").value(90));
  }

  @ParameterizedTest
  @DisplayName("2.6-API-004 P0 omitted and null threshold are accepted for defaulting")
  @ValueSource(strings = {
      "{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":1000000,\"baselineCounter\":1200}",
      "{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":1000000,\"baselineCounter\":1200,\"thresholdPercentage\":null}"
  })
  void omittedAndNullThresholdAcceptedForDefaulting(String body) throws Exception {
    var user = user(ApplicationRole.MANAGE);
    when(installations.create(eq(user), any())).thenReturn(view(UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body))
        .andExpect(status().isCreated());
  }

  @Test
  @DisplayName("2.6-API-004B P0 blank threshold is accepted for defaulting")
  void blankThresholdAcceptedForDefaulting() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    when(installations.create(eq(user), any())).thenReturn(view(UUID.randomUUID()));

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":1000000,\"baselineCounter\":1200,\"thresholdPercentage\":\"\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.thresholdPercentage").value(90));
  }

  @ParameterizedTest
  @DisplayName("2.6-API-005 P0 invalid installation requests return field errors")
  @ValueSource(strings = {
      "{\"machineId\":null,\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":100,\"baselineCounter\":0,\"thresholdPercentage\":90}",
      "{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":null,\"expectedProductionCount\":100,\"baselineCounter\":0,\"thresholdPercentage\":90}",
      "{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":0,\"baselineCounter\":0,\"thresholdPercentage\":90}",
      "{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":100,\"baselineCounter\":-1,\"thresholdPercentage\":90}",
      "{\"machineId\":\"00000000-0000-0000-0000-000000000001\",\"sparepartId\":\"00000000-0000-0000-0000-000000000002\",\"expectedProductionCount\":100,\"baselineCounter\":0,\"thresholdPercentage\":101}"
  })
  void invalidRequestsReturnFieldErrors(String body) throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  @Test
  @DisplayName("2.6-API-006 P0 malformed JSON returns safe error")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"machineId\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
  }

  @Test
  @DisplayName("2.6-API-007 P0 missing machine returns safe not-found error")
  void missingMachineReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new MachineForInstallationNotFoundException()).when(installations).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(90)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("2.6-API-008 P0 missing sparepart returns safe not-found error")
  void missingSparepartReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new SparepartForInstallationNotFoundException()).when(installations).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(90)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("2.6-API-009 P0 VIEWER cannot update installation")
  void viewerCannotUpdateInstallation() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var installationId = UUID.randomUUID();
    doThrow(new InstallationMutationForbiddenException()).when(installations).update(eq(user), eq(installationId), any());

    mockMvc.perform(put("/api/v1/machine-sparepart-installations/{installationId}", installationId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(updatePayload()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.6-API-010 P0 VIEWER cannot create installation")
  void viewerCannotCreateInstallation() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new InstallationMutationForbiddenException()).when(installations).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machine-sparepart-installations")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(90)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.6-API-011 P0 VIEWER cannot delete installation")
  void viewerCannotDeleteInstallation() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var installationId = UUID.randomUUID();
    doThrow(new InstallationMutationForbiddenException()).when(installations).delete(user, installationId);

    mockMvc.perform(delete("/api/v1/machine-sparepart-installations/{installationId}", installationId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.6-API-012 P0 invalid list limit returns safe validation error")
  void invalidListLimitReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(get("/api/v1/machine-sparepart-installations?limit=0").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("2.6-API-013 P0 update preserves installation identity")
  void updateReturnsInstallation() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var installationId = UUID.randomUUID();
    when(installations.update(eq(user), eq(installationId), any())).thenReturn(view(installationId));

    mockMvc.perform(put("/api/v1/machine-sparepart-installations/{installationId}", installationId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(updatePayload()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(installationId.toString()));
  }

  @Test
  @DisplayName("2.6-API-011 P0 delete installation returns no content")
  void deleteReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    mockMvc.perform(delete("/api/v1/machine-sparepart-installations/{installationId}", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("2.6-API-012 P0 missing installation returns safe not-found error")
  void missingInstallationReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var installationId = UUID.randomUUID();
    doThrow(new InstallationNotFoundException()).when(installations).get(user, installationId);

    mockMvc.perform(get("/api/v1/machine-sparepart-installations/{installationId}", installationId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INSTALLATION_NOT_FOUND"));
  }

  @Test
  @DisplayName("2.6-API-013 P0 delete conflict returns safe conflict error")
  void deleteConflictReturnsSafeConflictError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var installationId = UUID.randomUUID();
    doThrow(new InstallationDataIntegrityException()).when(installations).delete(user, installationId);

    mockMvc.perform(delete("/api/v1/machine-sparepart-installations/{installationId}", installationId).with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSTALLATION_DATA_INTEGRITY_VIOLATION"));
  }

  @Test
  @DisplayName("2.6-API-014 P0 invalid path UUID returns safe error")
  void invalidPathUuidReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(get("/api/v1/machine-sparepart-installations/not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  private static String payload(Integer thresholdPercentage) {
    var threshold = thresholdPercentage == null ? "null" : thresholdPercentage.toString();
    return """
        {"machineId":"00000000-0000-0000-0000-000000000001","sparepartId":"00000000-0000-0000-0000-000000000002","expectedProductionCount":1000000,"baselineCounter":1200,"thresholdPercentage":%s}
        """.formatted(threshold);
  }

  private static String updatePayload() {
    return """
        {"expectedProductionCount":2000000,"baselineCounter":1300,"thresholdPercentage":95}
        """;
  }

  private static InstallationView view(UUID installationId) {
    return new InstallationView(installationId, UUID.randomUUID(), "BF-08410", "JBF19", UUID.randomUUID(), "GM1", "GM1",
        UUID.randomUUID(), "Forming", UUID.randomUUID(), "PLC-WECON-LX5", "Electric PLC Wecon LX5",
        new TaxonomyRefView(UUID.randomUUID(), "ELEC", "Electric"), new TaxonomyRefView(UUID.randomUUID(), "WECON", "Wecon"),
        new TaxonomyRefView(UUID.randomUUID(), "PLC", "PLC"), new TaxonomyRefView(UUID.randomUUID(), "LX5", "LX5"),
        1_000_000L, 1_200L, null, null, null, 90, "COUNTER_BASED",
        Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"), Instant.parse("2026-05-28T00:00:00Z"));
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
