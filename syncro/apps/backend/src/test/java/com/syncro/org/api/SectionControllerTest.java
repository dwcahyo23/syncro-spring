package com.syncro.org.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.org.application.SectionService;
import com.syncro.org.application.SectionService.CreateSectionCommand;
import com.syncro.org.application.SectionService.DuplicateSectionCodeException;
import com.syncro.org.application.SectionService.DuplicateSectionNameException;
import com.syncro.org.application.SectionService.PlantNotFoundForSectionException;
import com.syncro.org.application.SectionService.SectionHasActiveMachineGroupsException;
import com.syncro.org.application.SectionService.SectionListView;
import com.syncro.org.application.SectionService.SectionMutationForbiddenException;
import com.syncro.org.application.SectionService.SectionNotFoundException;
import com.syncro.org.application.SectionService.SectionView;
import com.syncro.org.application.SectionService.UpdateSectionCommand;
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

@WebMvcTest(SectionController.class)
@Import({SecurityConfig.class, SectionExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class SectionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SectionService sections;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

  @Test
  @DisplayName("9.1-API-001 P1 create section returns 201")
  void createSectionReturnsCreated() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    var sectionId = UUID.randomUUID();
    when(sections.create(eq(user), any(CreateSectionCommand.class)))
        .thenReturn(new SectionView(sectionId, plantId, "GM1", "Plant GM1", "MACHINERY",
            "Machinery", true, null, NOW, NOW));

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"Machinery\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(sectionId.toString()))
        .andExpect(jsonPath("$.code").value("MACHINERY"))
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  @DisplayName("9.1-API-002 P1 list sections returns 200")
  void listSectionsReturnsOk() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    when(sections.list(user, plantId, false)).thenReturn(new SectionListView(List.of(
        new SectionView(UUID.randomUUID(), plantId, "GM1", "Plant GM1", "MACHINERY",
            "Machinery", true, null, NOW, NOW))));

    mockMvc.perform(get("/api/v1/sections").param("plantId", plantId.toString()).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].code").value("MACHINERY"));
  }

  @Test
  @DisplayName("9.1-API-003 P1 get section returns 200")
  void getSectionReturnsOk() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var sectionId = UUID.randomUUID();
    when(sections.get(user, sectionId))
        .thenReturn(new SectionView(sectionId, UUID.randomUUID(), "GM1", "Plant GM1", "UTILITY",
            "Utility", true, null, NOW, NOW));

    mockMvc.perform(get("/api/v1/sections/{sectionId}", sectionId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("UTILITY"));
  }

  @Test
  @DisplayName("9.1-API-004 P1 update section returns 200")
  void updateSectionReturnsOk() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var sectionId = UUID.randomUUID();
    when(sections.update(eq(user), eq(sectionId), any(UpdateSectionCommand.class)))
        .thenReturn(new SectionView(sectionId, UUID.randomUUID(), "GM1", "Plant GM1", "MACHINERY",
            "Machine Section", true, null, NOW, NOW));

    mockMvc.perform(put("/api/v1/sections/{sectionId}", sectionId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Machine Section\",\"active\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Machine Section"));
  }

  @Test
  @DisplayName("9.1-API-005 P0 AUDITOR create is forbidden with FORBIDDEN")
  void viewerCreateForbidden() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    var plantId = UUID.randomUUID();
    doThrow(new SectionMutationForbiddenException()).when(sections).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"Machinery\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("9.1-API-006 P0 out-of-scope plant is forbidden")
  void outOfScopePlantForbidden() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    doThrow(new PlantAccessDeniedException()).when(sections).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"Machinery\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("9.1-API-007 P1 unknown section maps to 404 SECTION_NOT_FOUND")
  void unknownSectionNotFound() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var sectionId = UUID.randomUUID();
    doThrow(new SectionNotFoundException()).when(sections).get(user, sectionId);

    mockMvc.perform(get("/api/v1/sections/{sectionId}", sectionId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SECTION_NOT_FOUND"));
  }

  @Test
  @DisplayName("9.1-API-008 P1 unknown plant maps to 404 PLANT_NOT_FOUND")
  void unknownPlantNotFound() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    doThrow(new PlantNotFoundForSectionException()).when(sections).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"Machinery\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("9.1-API-009 P1 duplicate code maps to 400 DUPLICATE_SECTION_CODE")
  void duplicateCodeMapsToBadRequest() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    doThrow(new DuplicateSectionCodeException()).when(sections).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"Machinery\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SECTION_CODE"));
  }

  @Test
  @DisplayName("9.1-API-010 P1 duplicate name maps to 400 DUPLICATE_SECTION_NAME")
  void duplicateNameMapsToBadRequest() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();
    doThrow(new DuplicateSectionNameException()).when(sections).create(eq(user), any());

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"Machinery\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_SECTION_NAME"));
  }

  @Test
  @DisplayName("9.1-API-011 P1 deactivation with active groups maps to 409 SECTION_HAS_ACTIVE_MACHINE_GROUPS")
  void deactivateWithActiveGroupsMapsToConflict() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var sectionId = UUID.randomUUID();
    doThrow(new SectionHasActiveMachineGroupsException()).when(sections).update(eq(user), eq(sectionId), any());

    mockMvc.perform(put("/api/v1/sections/{sectionId}", sectionId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Machinery\",\"active\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SECTION_HAS_ACTIVE_MACHINE_GROUPS"));
  }

  @Test
  @DisplayName("9.1-API-012 P1 invalid section request returns VALIDATION_ERROR")
  void invalidRequestReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    var plantId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"MACHINERY\",\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  @Test
  @DisplayName("9.1-API-013 P1 malformed JSON returns MALFORMED_JSON")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(post("/api/v1/sections")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
        .andExpect(jsonPath("$.message").value("Request body is malformed."));
  }

  @Test
  @DisplayName("9.1-API-014 unauthenticated section requests are rejected")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/sections").param("plantId", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("9.1-API-015 invalid plant query maps to INVALID_QUERY_VALUE")
  void invalidPlantQueryValue() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/sections").param("plantId", "not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
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
