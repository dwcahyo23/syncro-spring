package com.syncro.compliance.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.syncro.compliance.application.LessonLearnedService;
import com.syncro.compliance.application.LessonLearnedService.LessonNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Story 21-3 lesson API contract tests (21-2 CalibrationControllerTest pattern):
 * DTO shape, the stable error envelope (401/403/400/404/409 codes), the 201
 * create response, and the ?q=&tag= search binding. Services are mocked —
 * event-link validation and scope are proven in the service/integration tests;
 * this locks the serialization + status-code contract.
 */
@WebMvcTest(LessonLearnedController.class)
@Import({SecurityConfig.class, ComplianceExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class LessonLearnedControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private LessonLearnedService lessons;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    var principal = new UsernamePasswordAuthenticationToken(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
    return SecurityMockMvcRequestPostProcessors.authentication(principal);
  }

  private static AuthenticatedUser staff() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "staff@syncro.test",
        ApplicationRole.STAFF_MAINTENANCE);
  }

  private static LessonLearnedService.LessonView view(UUID id) {
    return new LessonLearnedService.LessonView(id, "PRJ-1", null, "KAIZEN", "Guide rail wear",
        "Three guide failures", null, null, null, null, null, List.of("mechanical"), null, null,
        null, List.of(Map.of("objectKey", "k1", "filename", "f.pdf")),
        Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T00:00:00Z"), 0L);
  }

  @Test
  @DisplayName("21.3-API-001 P0 POST create returns 201 + serialized lesson view")
  void createReturns201() throws Exception {
    var id = UUID.randomUUID();
    when(lessons.create(any(), any())).thenReturn(view(id));

    mockMvc.perform(post("/api/v1/lessons-learned")
            .contentType("application/json")
            .content("""
                {"projectId":"PRJ-1","title":"Guide rail wear",
                 "problemSummary":"Three guide failures","tags":["mechanical"],
                 "evidence":[{"objectKey":"k1","filename":"f.pdf"}]}""")
            .with(auth(staff())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.projectId").value("PRJ-1"))
        .andExpect(jsonPath("$.tags[0]").value("mechanical"))
        .andExpect(jsonPath("$.evidence[0].objectKey").value("k1"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-07T00:00:00Z"));
  }

  @Test
  @DisplayName("21.3-API-002 P0 blank projectId/title/problemSummary → 400 VALIDATION_ERROR")
  void blankFieldsValidation() throws Exception {
    mockMvc.perform(post("/api/v1/lessons-learned")
            .contentType("application/json")
            .content("{\"projectId\":\"\",\"title\":\"  \",\"problemSummary\":\"\"}")
            .with(auth(staff())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.projectId").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.title").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.problemSummary").isNotEmpty());
  }

  @Test
  @DisplayName("21.3-API-003 P0 duplicate project id → 409 DUPLICATE_IDENTIFIER envelope")
  void duplicateIdentifier() throws Exception {
    when(lessons.create(any(), any())).thenThrow(new DuplicateIdentifierException());

    mockMvc.perform(post("/api/v1/lessons-learned")
            .contentType("application/json")
            .content("{\"projectId\":\"PRJ-1\",\"title\":\"T\",\"problemSummary\":\"P\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_IDENTIFIER"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("21.3-API-004 P0 unknown event link → 404 with the stable code")
  void referenceNotFound() throws Exception {
    when(lessons.create(any(), any())).thenThrow(
        new ComplianceReferenceNotFoundException("NON_CONFORMANCE_NOT_FOUND",
            "Referenced non-conformance was not found."));

    mockMvc.perform(post("/api/v1/lessons-learned")
            .contentType("application/json")
            .content("""
                {"projectId":"PRJ-1","title":"T","problemSummary":"P",
                 "ncId":"7b7c6d5e-1111-2222-3333-444455556666"}""")
            .with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NON_CONFORMANCE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-API-005 P0 unknown/out-of-scope lesson → 404 LESSON_NOT_FOUND")
  void lessonNotFound() throws Exception {
    when(lessons.get(any(), any())).thenThrow(new LessonNotFoundException());

    mockMvc.perform(get("/api/v1/lessons-learned/{id}", UUID.randomUUID()).with(auth(staff())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LESSON_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-API-006 P0 GET search passes ?q=&tag= to the service")
  void searchBindsParams() throws Exception {
    when(lessons.search(any(), anyString(), anyString())).thenReturn(List.of(view(UUID.randomUUID())));

    mockMvc.perform(get("/api/v1/lessons-learned").param("q", "forming").param("tag", "setup")
            .with(auth(staff())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].projectId").value("PRJ-1"));

    verify(lessons).search(any(), eq("forming"), eq("setup"));
  }

  @Test
  @DisplayName("21.3-API-007 P0 role gate denial on a mutation → 403 FORBIDDEN envelope (F4)")
  void forbidden() throws Exception {
    when(lessons.create(any(), any())).thenThrow(new ComplianceForbiddenException());

    mockMvc.perform(post("/api/v1/lessons-learned")
            .contentType("application/json")
            .content("{\"projectId\":\"PRJ-1\",\"title\":\"T\",\"problemSummary\":\"P\"}")
            .with(auth(staff())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("21.3-API-007b P0 GET by id returns 200 + version field (VG-8)")
  void getReturns200WithVersion() throws Exception {
    var id = UUID.randomUUID();
    when(lessons.get(any(), any())).thenReturn(view(id));

    mockMvc.perform(get("/api/v1/lessons-learned/{id}", id).with(auth(staff())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  @DisplayName("21.3-API-007c P0 PATCH returns 200 + updated view with version (VG-8)")
  void patchReturns200() throws Exception {
    var id = UUID.randomUUID();
    when(lessons.update(any(), any(), any())).thenReturn(view(id));

    mockMvc.perform(patch("/api/v1/lessons-learned/{id}", id)
            .contentType("application/json")
            .content("{\"title\":\"Renamed\"}")
            .with(auth(staff())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.version").value(0));
  }

  @Test
  @DisplayName("21.3-API-008 P0 concurrent update → 409 VERSION_CONFLICT (never 500)")
  void optimisticLockConflict() throws Exception {
    when(lessons.update(any(), any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(
            "LessonLearnedEntity", UUID.randomUUID()));

    mockMvc.perform(patch("/api/v1/lessons-learned/{id}", UUID.randomUUID())
            .contentType("application/json")
            .content("{\"title\":\"Race\"}")
            .with(auth(staff())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("21.3-API-009 P1 delete returns 204 no content")
  void deleteReturns204() throws Exception {
    mockMvc.perform(delete("/api/v1/lessons-learned/{id}", UUID.randomUUID())
            .with(auth(staff())))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("21.3-API-010 P0 unauthenticated request → 401 AUTHENTICATION_REQUIRED body code")
  void unauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/lessons-learned"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }
}
