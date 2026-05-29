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
import com.syncro.sparepart.application.SparepartService;
import com.syncro.sparepart.application.SparepartService.DuplicateSparepartException;
import com.syncro.sparepart.application.SparepartService.SparepartDataIntegrityException;
import com.syncro.sparepart.application.SparepartService.SparepartMutationForbiddenException;
import com.syncro.sparepart.application.SparepartService.SparepartNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyDimensionMismatchException;
import com.syncro.sparepart.application.SparepartService.SparepartMachineRefView;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyRefView;
import com.syncro.sparepart.application.SparepartService.SparepartListView;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyReferenceNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartView;
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

@WebMvcTest(SparepartController.class)
@Import({SecurityConfig.class, SparepartExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class SparepartControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartService spareparts;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.5-API-001 P0 unauthenticated users cannot list spareparts")
  void listSparepartsRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/spareparts"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.5-API-002 P1 VIEWER can list spareparts with filters")
  void viewerCanListSparepartsWithFilters() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var sparepartId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var brandId = UUID.randomUUID();
    var kindId = UUID.randomUUID();
    var typeId = UUID.randomUUID();
    when(spareparts.list(eq(user), any())).thenReturn(new SparepartListView(List.of(view(sparepartId, categoryId, brandId, kindId, typeId)), 1, 0, 200));

    mockMvc.perform(get("/api/v1/spareparts")
        .param("categoryId", categoryId.toString())
        .param("brandId", brandId.toString())
        .param("kindId", kindId.toString())
        .param("typeId", typeId.toString())
        .param("search", "PLC")
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(sparepartId.toString()))
        .andExpect(jsonPath("$.items[0].code").value("PLC-WECON-LX5"))
        .andExpect(jsonPath("$.items[0].name").value("Wecon LX5 PLC"))
        .andExpect(jsonPath("$.items[0].category.id").value(categoryId.toString()))
        .andExpect(jsonPath("$.items[0].brand.id").value(brandId.toString()))
        .andExpect(jsonPath("$.items[0].kind.id").value(kindId.toString()))
        .andExpect(jsonPath("$.items[0].type.id").value(typeId.toString()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(200));
  }

  @Test
  @DisplayName("2.5-API-003 P1 MANAGE can create sparepart")
  void manageCanCreateSparepart() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var sparepartId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var brandId = UUID.randomUUID();
    var kindId = UUID.randomUUID();
    var typeId = UUID.randomUUID();
    when(spareparts.create(eq(user), any())).thenReturn(view(sparepartId, categoryId, brandId, kindId, typeId));

    mockMvc.perform(post("/api/v1/spareparts")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(categoryId, brandId, kindId, typeId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(sparepartId.toString()))
        .andExpect(jsonPath("$.code").value("PLC-WECON-LX5"))
        .andExpect(jsonPath("$.name").value("Wecon LX5 PLC"));
  }

  @ParameterizedTest
  @DisplayName("2.5-API-004 P1 invalid sparepart requests return field errors")
  @ValueSource(strings = {
      "{\"code\":\"\",\"name\":\"Wecon LX5 PLC\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":\"\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":null,\"name\":\"Wecon LX5 PLC\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":null,\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":\"Wecon LX5 PLC\",\"machineId\":null,\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":\"Wecon LX5 PLC\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":null,\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":\"Wecon LX5 PLC\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":null,\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":\"Wecon LX5 PLC\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":null,\"typeId\":\"00000000-0000-0000-0000-000000000004\"}",
      "{\"code\":\"PLC-WECON-LX5\",\"name\":\"Wecon LX5 PLC\",\"machineId\":\"00000000-0000-0000-0000-000000000010\",\"categoryId\":\"00000000-0000-0000-0000-000000000001\",\"brandId\":\"00000000-0000-0000-0000-000000000002\",\"kindId\":\"00000000-0000-0000-0000-000000000003\",\"typeId\":null}"
  })
  void invalidSparepartRequestReturnsFieldErrors(String payload) throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/spareparts")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.5-API-005 P1 malformed JSON returns safe error")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/spareparts")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
        .andExpect(jsonPath("$.message").value("Request body is malformed."));
  }

  @Test
  @DisplayName("2.5-API-006 P1 duplicate sparepart returns safe validation error")
  void duplicateSparepartReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var categoryId = UUID.randomUUID();
    var brandId = UUID.randomUUID();
    var kindId = UUID.randomUUID();
    var typeId = UUID.randomUUID();
    doThrow(new DuplicateSparepartException()).when(spareparts).create(eq(user), any());

    mockMvc.perform(post("/api/v1/spareparts")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(categoryId, brandId, kindId, typeId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SPAREPART"))
        .andExpect(jsonPath("$.message").value("Sparepart code or name already exists."));
  }

  @Test
  @DisplayName("2.5-API-007 P1 missing taxonomy reference returns safe validation error")
  void missingTaxonomyReferenceReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var categoryId = UUID.randomUUID();
    var brandId = UUID.randomUUID();
    var kindId = UUID.randomUUID();
    var typeId = UUID.randomUUID();
    doThrow(new SparepartTaxonomyReferenceNotFoundException()).when(spareparts).create(eq(user), any());

    mockMvc.perform(post("/api/v1/spareparts")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(categoryId, brandId, kindId, typeId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SPAREPART_TAXONOMY_REFERENCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("2.5-API-008 P1 wrong taxonomy dimension returns safe validation error")
  void wrongTaxonomyDimensionReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var categoryId = UUID.randomUUID();
    var brandId = UUID.randomUUID();
    var kindId = UUID.randomUUID();
    var typeId = UUID.randomUUID();
    doThrow(new SparepartTaxonomyDimensionMismatchException()).when(spareparts).create(eq(user), any());

    mockMvc.perform(post("/api/v1/spareparts")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(categoryId, brandId, kindId, typeId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SPAREPART_TAXONOMY_DIMENSION_MISMATCH"));
  }

  @ParameterizedTest
  @DisplayName("2.5-API-009 P0 unauthenticated users cannot mutate spareparts")
  @ValueSource(strings = {"POST", "PUT", "DELETE"})
  void mutateSparepartsRequiresAuthentication(String method) throws Exception {
    var sparepartId = UUID.randomUUID();
    var request = switch (method) {
      case "POST" -> post("/api/v1/spareparts")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"code\":\"PLC-WECON-LX5\"}");
      case "PUT" -> put("/api/v1/spareparts/{sparepartId}", sparepartId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"code\":\"PLC-WECON-LX5\"}");
      case "DELETE" -> delete("/api/v1/spareparts/{sparepartId}", sparepartId);
      default -> throw new IllegalArgumentException(method);
    };

    mockMvc.perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.5-API-010 P0 VIEWER cannot update sparepart")
  void viewerCannotUpdateSparepart() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var sparepartId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var brandId = UUID.randomUUID();
    var kindId = UUID.randomUUID();
    var typeId = UUID.randomUUID();
    doThrow(new SparepartMutationForbiddenException()).when(spareparts).update(eq(user), eq(sparepartId), any());

    mockMvc.perform(put("/api/v1/spareparts/{sparepartId}", sparepartId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(categoryId, brandId, kindId, typeId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.5-API-011 P0 VIEWER cannot delete sparepart")
  void viewerCannotDeleteSparepart() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var sparepartId = UUID.randomUUID();
    doThrow(new SparepartMutationForbiddenException()).when(spareparts).delete(user, sparepartId);

    mockMvc.perform(delete("/api/v1/spareparts/{sparepartId}", sparepartId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.5-API-012 P1 invalid sparepart id returns safe path error")
  void invalidSparepartIdReturnsSafePathError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/spareparts/not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"))
        .andExpect(jsonPath("$.message").value("Path value is invalid."));
  }

  @Test
  @DisplayName("2.R-API-002 P1 invalid sparepart taxonomy query UUID returns safe query error")
  void invalidSparepartTaxonomyQueryUuidReturnsSafeQueryError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/spareparts").param("categoryId", "not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"))
        .andExpect(jsonPath("$.message").value("Query value is invalid."));
  }

  @Test
  @DisplayName("2.5-API-013 P1 missing sparepart returns safe not-found error")
  void missingSparepartReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var sparepartId = UUID.randomUUID();
    doThrow(new SparepartNotFoundException()).when(spareparts).get(user, sparepartId);

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}", sparepartId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Sparepart was not found."));
  }

  @Test
  @DisplayName("2.5-API-014 P1 delete integrity conflict returns safe conflict error")
  void deleteIntegrityConflictReturnsSafeConflictError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var sparepartId = UUID.randomUUID();
    doThrow(new SparepartDataIntegrityException()).when(spareparts).delete(user, sparepartId);

    mockMvc.perform(delete("/api/v1/spareparts/{sparepartId}", sparepartId).with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SPAREPART_DATA_INTEGRITY_VIOLATION"))
        .andExpect(jsonPath("$.message").value("Sparepart data conflicts with existing records."));
  }

  private static String payload(UUID categoryId, UUID brandId, UUID kindId, UUID typeId) {
    return """
        {"code":"PLC-WECON-LX5","name":"Wecon LX5 PLC","machineId":"00000000-0000-0000-0000-000000000010","categoryId":"%s","brandId":"%s","kindId":"%s","typeId":"%s"}
        """.formatted(categoryId, brandId, kindId, typeId);
  }

  private static SparepartView view(UUID sparepartId, UUID categoryId, UUID brandId, UUID kindId, UUID typeId) {
    return new SparepartView(
        sparepartId,
        "PLC-WECON-LX5",
        "Wecon LX5 PLC",
        new SparepartMachineRefView(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "MCH-1",
            "Machine 1",
            UUID.fromString("00000000-0000-0000-0000-000000000011"),
            "PLANT-1",
            "Plant 1"),
        new SparepartTaxonomyRefView(categoryId, "ELEC", "Electric"),
        new SparepartTaxonomyRefView(brandId, "WECON", "Wecon"),
        new SparepartTaxonomyRefView(kindId, "PLC", "PLC"),
        new SparepartTaxonomyRefView(typeId, "LX5", "LX5"),
        Instant.parse("2026-05-28T00:00:00Z"),
        Instant.parse("2026-05-28T00:00:00Z"));
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
