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
import com.syncro.sparepart.application.SparepartTaxonomyService;
import com.syncro.sparepart.application.SparepartTaxonomyService.DuplicateSparepartTaxonomyException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyDataIntegrityException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyMutationForbiddenException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyNotFoundException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyView;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
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

@WebMvcTest(SparepartTaxonomyController.class)
@Import({SecurityConfig.class, SparepartTaxonomyExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class SparepartTaxonomyControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartTaxonomyService taxonomy;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.4-API-001 P0 unauthenticated users cannot list taxonomy")
  void listTaxonomyRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/sparepart-taxonomies"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.4-API-002 P1 VIEWER can list taxonomy")
  void viewerCanListTaxonomy() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var entryId = UUID.randomUUID();
    when(taxonomy.list(user, SparepartTaxonomyDimension.CATEGORY)).thenReturn(List.of(new SparepartTaxonomyView(
        entryId,
        SparepartTaxonomyDimension.CATEGORY,
        "ELEC",
        "Electric",
        Instant.parse("2026-05-28T00:00:00Z"),
        Instant.parse("2026-05-28T00:00:00Z"))));

    mockMvc.perform(get("/api/v1/sparepart-taxonomies").param("dimension", "CATEGORY").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(entryId.toString()))
        .andExpect(jsonPath("$.items[0].dimension").value("CATEGORY"))
        .andExpect(jsonPath("$.items[0].code").value("ELEC"))
        .andExpect(jsonPath("$.items[0].name").value("Electric"));
  }

  @Test
  @DisplayName("2.4-API-003 P1 MANAGE can create taxonomy")
  void manageCanCreateTaxonomy() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var entryId = UUID.randomUUID();
    when(taxonomy.create(eq(user), any())).thenReturn(new SparepartTaxonomyView(
        entryId,
        SparepartTaxonomyDimension.CATEGORY,
        "ELEC",
        "Electric",
        Instant.parse("2026-05-28T00:00:00Z"),
        Instant.parse("2026-05-28T00:00:00Z")));

    mockMvc.perform(post("/api/v1/sparepart-taxonomies")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":\"Electric\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(entryId.toString()))
        .andExpect(jsonPath("$.dimension").value("CATEGORY"))
        .andExpect(jsonPath("$.code").value("ELEC"))
        .andExpect(jsonPath("$.name").value("Electric"));
  }

  @Test
  @DisplayName("2.4-API-004 P0 VIEWER cannot create taxonomy")
  void viewerCannotCreateTaxonomy() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new SparepartTaxonomyMutationForbiddenException()).when(taxonomy).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sparepart-taxonomies")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":\"Electric\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @ParameterizedTest
  @DisplayName("2.4-API-005 P1 invalid taxonomy requests return field errors")
  @ValueSource(strings = {
      "{\"dimension\":null,\"code\":\"ELEC\",\"name\":\"Electric\"}",
      "{\"dimension\":\"CATEGORY\",\"code\":\"\",\"name\":\"Electric\"}",
      "{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":\"\"}",
      "{\"dimension\":\"CATEGORY\",\"code\":null,\"name\":\"Electric\"}",
      "{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":null}",
      "{\"dimension\":\"CATEGORY\",\"code\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLM\",\"name\":\"Electric\"}",
      "{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ\"}"
  })
  void invalidTaxonomyRequestReturnsFieldErrors(String payload) throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/sparepart-taxonomies")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.4-API-006 P1 malformed JSON returns safe error")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/sparepart-taxonomies")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"dimension\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
        .andExpect(jsonPath("$.message").value("Request body is malformed."));
  }

  @Test
  @DisplayName("2.4-API-007 P1 duplicate taxonomy returns safe validation error")
  void duplicateTaxonomyReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new DuplicateSparepartTaxonomyException()).when(taxonomy).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sparepart-taxonomies")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":\"Electric\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SPAREPART_TAXONOMY"))
        .andExpect(jsonPath("$.message").value("Sparepart taxonomy code or name already exists for this dimension."));
  }

  @Test
  @DisplayName("2.4-API-008 P1 missing taxonomy returns safe not-found error")
  void missingTaxonomyReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var entryId = UUID.randomUUID();
    doThrow(new SparepartTaxonomyNotFoundException()).when(taxonomy).get(user, entryId);

    mockMvc.perform(get("/api/v1/sparepart-taxonomies/{taxonomyId}", entryId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_TAXONOMY_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Sparepart taxonomy entry was not found."));
  }

  @ParameterizedTest
  @DisplayName("2.4-API-009 P0 unauthenticated users cannot mutate taxonomy")
  @ValueSource(strings = {"POST", "PUT", "DELETE"})
  void mutateTaxonomyRequiresAuthentication(String method) throws Exception {
    var entryId = UUID.randomUUID();
    var request = switch (method) {
      case "POST" -> post("/api/v1/sparepart-taxonomies")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"dimension\":\"CATEGORY\",\"name\":\"Electric\"}");
      case "PUT" -> put("/api/v1/sparepart-taxonomies/{taxonomyId}", entryId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"dimension\":\"CATEGORY\",\"name\":\"Electric\"}");
      case "DELETE" -> delete("/api/v1/sparepart-taxonomies/{taxonomyId}", entryId);
      default -> throw new IllegalArgumentException(method);
    };

    mockMvc.perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.4-API-010 P0 VIEWER cannot update taxonomy")
  void viewerCannotUpdateTaxonomy() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var entryId = UUID.randomUUID();
    doThrow(new SparepartTaxonomyMutationForbiddenException()).when(taxonomy).update(eq(user), eq(entryId), any());

    mockMvc.perform(put("/api/v1/sparepart-taxonomies/{taxonomyId}", entryId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"dimension\":\"CATEGORY\",\"code\":\"ELEC\",\"name\":\"Electric\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.4-API-011 P0 VIEWER cannot delete taxonomy")
  void viewerCannotDeleteTaxonomy() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var entryId = UUID.randomUUID();
    doThrow(new SparepartTaxonomyMutationForbiddenException()).when(taxonomy).delete(user, entryId);

    mockMvc.perform(delete("/api/v1/sparepart-taxonomies/{taxonomyId}", entryId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.4-API-012 P1 invalid taxonomy id returns safe path error")
  void invalidTaxonomyIdReturnsSafePathError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/sparepart-taxonomies/not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"))
        .andExpect(jsonPath("$.message").value("Path value is invalid."));
  }

  @Test
  @DisplayName("2.4-API-013 P1 delete integrity conflict returns safe conflict error")
  void deleteIntegrityConflictReturnsSafeConflictError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var entryId = UUID.randomUUID();
    doThrow(new SparepartTaxonomyDataIntegrityException()).when(taxonomy).delete(user, entryId);

    mockMvc.perform(delete("/api/v1/sparepart-taxonomies/{taxonomyId}", entryId).with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SPAREPART_TAXONOMY_DATA_INTEGRITY_VIOLATION"))
        .andExpect(jsonPath("$.message").value("Sparepart taxonomy data conflicts with existing records."));
  }

  @Test
  @DisplayName("2.4-API-014 P1 invalid dimension query returns safe query error")
  void invalidDimensionQueryReturnsSafeQueryError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/sparepart-taxonomies").param("dimension", "INVALID").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("2.R-API-004 P1 invalid taxonomy category query UUID returns safe query error")
  void invalidTaxonomyCategoryQueryUuidReturnsSafeQueryError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/sparepart-taxonomies").param("categoryId", "not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"))
        .andExpect(jsonPath("$.message").value("Query value is invalid."));
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
