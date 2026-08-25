package com.syncro.masterdata.api;

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
import com.syncro.masterdata.application.PlantService;
import com.syncro.masterdata.application.PlantService.DuplicatePlantCodeException;
import com.syncro.masterdata.application.PlantService.PlantMutationForbiddenException;
import com.syncro.masterdata.application.PlantService.PlantNotFoundException;
import com.syncro.masterdata.application.PlantService.PlantView;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlantController.class)
@Import({SecurityConfig.class, PlantExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class PlantControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PlantService plants;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.1-API-001 P0 unauthenticated users cannot list plants")
  void listPlantsRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/plants"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.1-API-002 P1 SUPER_ADMIN can list plants")
  void superAdminCanListPlants() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    when(plants.list(user)).thenReturn(List.of(new PlantView(
        plantId,
        "GM1",
        "Plant GM1",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"))));

    mockMvc.perform(get("/api/v1/plants").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(plantId.toString()))
        .andExpect(jsonPath("$.items[0].code").value("GM1"))
        .andExpect(jsonPath("$.items[0].name").value("Plant GM1"))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-05-27T00:00:00Z"));
  }

  @Test
  @DisplayName("2.1-API-003 P1 MANAGER_MAINTENANCE can create plants")
  void manageCanCreatePlant() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    when(plants.create(eq(user), any())).thenReturn(new PlantView(
        plantId,
        "GM1",
        "Plant GM1",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z")));

    mockMvc.perform(post("/api/v1/plants")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"gm1\",\"name\":\"Plant GM1\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(plantId.toString()))
        .andExpect(jsonPath("$.code").value("GM1"));
  }

  @Test
  @DisplayName("2.1-API-004 P0 AUDITOR cannot create plants")
  void viewerCannotCreatePlant() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    doThrow(new PlantMutationForbiddenException()).when(plants).create(eq(user), any());

    mockMvc.perform(post("/api/v1/plants")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"GM1\",\"name\":\"Plant GM1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @ParameterizedTest
  @DisplayName("2.1-API-005 P1 invalid plant requests return field errors")
  @ValueSource(strings = {
      "{\"code\":\"\",\"name\":\"\"}",
      "{\"code\":null,\"name\":\"Plant GM1\"}",
      "{\"code\":\"GM1\",\"name\":null}",
      "{\"code\":\"GM 1\",\"name\":\"Plant GM1\"}",
      "{\"code\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLM\",\"name\":\"Plant GM1\"}",
      "{\"code\":\"GM1\",\"name\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ\"}"
  })
  void invalidPlantRequestReturnsFieldErrors(String payload) throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(post("/api/v1/plants")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.1-API-006 P1 malformed JSON returns safe error")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(post("/api/v1/plants")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
        .andExpect(jsonPath("$.message").value("Request body is malformed."))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.1-API-007 P1 duplicate plant code returns safe validation error")
  void duplicatePlantCodeReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new DuplicatePlantCodeException()).when(plants).create(eq(user), any());

    mockMvc.perform(post("/api/v1/plants")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"GM1\",\"name\":\"Plant GM1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_PLANT_CODE"))
        .andExpect(jsonPath("$.message").value("Plant code already exists."))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.1-API-008 P0 MANAGER_MAINTENANCE out-of-scope update returns safe forbidden error")
  void outOfScopeUpdateReturnsSafeForbiddenError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    doThrow(new PlantMutationForbiddenException()).when(plants).update(eq(user), eq(plantId), any());

    mockMvc.perform(put("/api/v1/plants/{plantId}", plantId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"GM1\",\"name\":\"Plant GM1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.1-API-009 P1 missing plant returns safe not-found error")
  void missingPlantReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    doThrow(new PlantNotFoundException()).when(plants).get(user, plantId);

    mockMvc.perform(get("/api/v1/plants/{plantId}", plantId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Plant was not found."));
  }

  @Test
  @DisplayName("2.1-API-010 P0 AUDITOR cannot delete plants")
  void viewerCannotDeletePlant() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    var plantId = UUID.randomUUID();
    doThrow(new PlantMutationForbiddenException()).when(plants).delete(user, plantId);

    mockMvc.perform(delete("/api/v1/plants/{plantId}", plantId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @ParameterizedTest
  @DisplayName("2.1-API-011 P0 unauthenticated users cannot mutate plants")
  @ValueSource(strings = {"POST", "PUT", "DELETE"})
  void mutatePlantsRequiresAuthentication(String method) throws Exception {
    var plantId = UUID.randomUUID();
    var request = switch (method) {
      case "POST" -> post("/api/v1/plants")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"code\":\"GM1\",\"name\":\"Plant GM1\"}");
      case "PUT" -> put("/api/v1/plants/{plantId}", plantId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"code\":\"GM1\",\"name\":\"Plant GM1\"}");
      case "DELETE" -> delete("/api/v1/plants/{plantId}", plantId);
      default -> throw new IllegalArgumentException(method);
    };

    mockMvc.perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.1-API-012 P0 AUDITOR cannot update plants")
  void viewerCannotUpdatePlant() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    var plantId = UUID.randomUUID();
    doThrow(new PlantMutationForbiddenException()).when(plants).update(eq(user), eq(plantId), any());

    mockMvc.perform(put("/api/v1/plants/{plantId}", plantId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"GM1\",\"name\":\"Plant GM1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.1-API-013 P0 MANAGER_MAINTENANCE out-of-scope delete returns safe forbidden error")
  void outOfScopeDeleteReturnsSafeForbiddenError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    doThrow(new PlantMutationForbiddenException()).when(plants).delete(user, plantId);

    mockMvc.perform(delete("/api/v1/plants/{plantId}", plantId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.1-API-014 P1 invalid plant id returns safe path error")
  void invalidPlantIdReturnsSafePathError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/plants/not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"))
        .andExpect(jsonPath("$.message").value("Path value is invalid."))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
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
