package com.syncro.org.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.syncro.org.application.MachineAreaService;
import com.syncro.org.application.MachineAreaService.CreateMachineAreaCommand;
import com.syncro.org.application.MachineAreaService.DuplicateMachineAreaNameException;
import com.syncro.org.application.MachineAreaService.MachineAreaHasMachinesException;
import com.syncro.org.application.MachineAreaService.MachineAreaMutationForbiddenException;
import com.syncro.org.application.MachineAreaService.MachineAreaNotFoundException;
import com.syncro.org.application.MachineAreaService.MachineAreaView;
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

@WebMvcTest(MachineAreaController.class)
@Import({SecurityConfig.class, MachineAreaExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class MachineAreaControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MachineAreaService areas;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("16-1-API-001 P0 unauthenticated cannot list machine areas")
  void listRequiresAuth() throws Exception {
    mockMvc.perform(get("/api/v1/machine-areas"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("16-1-API-002 P1 SUPER_ADMIN can list machine areas")
  void superAdminCanList() throws Exception {
    var plantId = UUID.randomUUID();
    var areaId = UUID.randomUUID();
    when(areas.list(any(), any(), anyBoolean())).thenReturn(
        new MachineAreaService.MachineAreaListView(List.of(
            new MachineAreaView(areaId, plantId, "GM1", "Plant GM1", "FLOOR-1", "Production Floor 1",
                null, true, Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")))));

    mockMvc.perform(get("/api/v1/machine-areas")
        .param("plantId", plantId.toString())
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Production Floor 1"))
        .andExpect(jsonPath("$.items[0].code").value("FLOOR-1"));
  }

  @Test
  @DisplayName("16-1-API-003 P1 MANAGER_MAINTENANCE can create area")
  void manageCanCreate() throws Exception {
    var plantId = UUID.randomUUID();
    var areaId = UUID.randomUUID();
    when(areas.create(any(), any(CreateMachineAreaCommand.class))).thenReturn(
        new MachineAreaView(areaId, plantId, "GM1", "Plant GM1", "FLOOR-1", "Production Floor 1",
            null, true, Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")));

    mockMvc.perform(post("/api/v1/machine-areas")
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"code\":\"FLOOR-1\",\"name\":\"Production Floor 1\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("FLOOR-1"));
  }

  @Test
  @DisplayName("16-1-API-004 P1 duplicate name returns field error")
  void duplicateNameReturnsFieldError() throws Exception {
    when(areas.create(any(), any())).thenThrow(new DuplicateMachineAreaNameException());

    mockMvc.perform(post("/api/v1/machine-areas")
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + UUID.randomUUID() + "\",\"name\":\"Duplicate\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("16-1-API-005 P1 delete with machines returns 409")
  void deleteWithMachinesReturnsConflict() throws Exception {
    doThrow(new MachineAreaHasMachinesException()).when(areas).delete(any(), any());

    mockMvc.perform(delete("/api/v1/machine-areas/{id}", UUID.randomUUID())
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MACHINE_AREA_HAS_MACHINES"));
  }

  @Test
  @DisplayName("16-1-API-006 P1 forbidden for TECHNICIAN")
  void technicianCannotCreate() throws Exception {
    doThrow(new MachineAreaMutationForbiddenException()).when(areas).create(any(), any());

    mockMvc.perform(post("/api/v1/machine-areas")
        .with(auth(user(ApplicationRole.TECHNICIAN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + UUID.randomUUID() + "\",\"name\":\"Any\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("16-1-API-007 P1 not found returns 404")
  void notFoundReturns404() throws Exception {
    when(areas.get(any(), any())).thenThrow(new MachineAreaNotFoundException());

    mockMvc.perform(get("/api/v1/machine-areas/{id}", UUID.randomUUID())
        .with(auth(user(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_AREA_NOT_FOUND"));
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
