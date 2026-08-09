package com.syncro.machine.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.application.MachineService.DuplicateMachineCodeException;
import com.syncro.machine.application.MachineService.MachineCommand;
import com.syncro.machine.application.MachineService.MachineDataIntegrityException;
import com.syncro.machine.application.MachineService.MachineGroupPlantMismatchException;
import com.syncro.machine.application.MachineService.MachineMutationForbiddenException;
import com.syncro.machine.application.MachineService.MachineNotFoundException;
import com.syncro.machine.application.MachineService.MachineListView;
import com.syncro.machine.application.MachineService.MachineView;
import com.syncro.machine.domain.MachineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

@WebMvcTest(MachineController.class)
@Import({SecurityConfig.class, MachineExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class MachineControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MachineService machines;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.3-API-001 P0 unauthenticated users cannot list machines")
  void listMachinesRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/machines"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.3-API-002 P1 SUPER_ADMIN can list machines with filters")
  void superAdminCanListMachinesWithFilters() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    when(machines.list(user, plantId, groupId, MachineStatus.ACTIVE, "BF", 0, 25, "code,asc"))
        .thenReturn(new MachineListView(List.of(view(machineId, plantId, groupId)), 1, 0, 25, "code,asc"));

    mockMvc.perform(get("/api/v1/machines")
        .param("plantId", plantId.toString())
        .param("machineGroupId", groupId.toString())
        .param("status", "ACTIVE")
        .param("search", "BF")
        .param("limit", "25")
        .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(machineId.toString()))
        .andExpect(jsonPath("$.items[0].code").value("BF-08410"))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].machineGroupName").value("Forming"));
  }

  @Test
  @DisplayName("2.3-API-003 P1 MANAGE can create machines")
  void manageCanCreateMachine() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    when(machines.create(eq(user), any())).thenReturn(view(machineId, plantId, groupId));

    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(plantId, groupId, "BF-08410", "ACTIVE")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(machineId.toString()))
        .andExpect(jsonPath("$.code").value("BF-08410"));
  }

  @ParameterizedTest
  @DisplayName("2.3-API-004 P1 invalid machine requests return field errors")
  @ValueSource(strings = {
      "{\"plantId\":null,\"machineGroupId\":\"00000000-0000-0000-0000-000000000002\",\"code\":\"BF-08410\",\"status\":\"ACTIVE\"}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"machineGroupId\":null,\"code\":\"BF-08410\",\"status\":\"ACTIVE\"}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"machineGroupId\":\"00000000-0000-0000-0000-000000000002\",\"code\":\"\",\"status\":\"ACTIVE\"}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"machineGroupId\":\"00000000-0000-0000-0000-000000000002\",\"code\":\"BAD/CODE\",\"status\":\"ACTIVE\"}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"machineGroupId\":\"00000000-0000-0000-0000-000000000002\",\"code\":\"BF-08410\",\"status\":null}"
  })
  void invalidMachineRequestReturnsFieldErrors(String payload) throws Exception {
    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user(ApplicationRole.MANAGE)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  @Test
  @DisplayName("2.3-API-005 P1 invalid status returns malformed safe error")
  void invalidStatusReturnsSafeError() throws Exception {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();

    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user(ApplicationRole.MANAGE)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(plantId, groupId, "BF-08410", "PAUSED")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
  }

  @Test
  @DisplayName("2.3-API-006 P1 duplicate same-plant code returns safe validation error")
  void duplicateMachineCodeReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    doThrow(new DuplicateMachineCodeException()).when(machines).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(plantId, groupId, "BF-08410", "ACTIVE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_MACHINE_CODE"));
  }

  @Test
  @DisplayName("2.3-API-007 P1 machine group plant mismatch returns safe error")
  void machineGroupPlantMismatchReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    doThrow(new MachineGroupPlantMismatchException()).when(machines).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(plantId, groupId, "BF-08410", "ACTIVE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MACHINE_GROUP_PLANT_MISMATCH"));
  }

  @Test
  @DisplayName("2.3-API-008 P0 VIEWER cannot update machines")
  void viewerCannotUpdateMachine() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    doThrow(new MachineMutationForbiddenException()).when(machines).update(eq(user), eq(machineId), any());

    mockMvc.perform(put("/api/v1/machines/{machineId}", machineId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload(plantId, groupId, "BF-08410", "ACTIVE")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.3-API-009 P0 out-of-scope list returns safe forbidden error")
  void outOfScopeListReturnsSafeForbiddenError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    doThrow(new PlantAccessDeniedException()).when(machines).list(user, plantId, null, null, null, 0, 100, "code,asc");

    mockMvc.perform(get("/api/v1/machines").param("plantId", plantId.toString()).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.3-API-010 P1 missing machine returns safe not-found error")
  void missingMachineReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var machineId = UUID.randomUUID();
    doThrow(new MachineNotFoundException()).when(machines).get(user, machineId);

    mockMvc.perform(get("/api/v1/machines/{machineId}", machineId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("2.3-API-011 P1 delete integrity conflict returns safe conflict error")
  void deleteIntegrityConflictReturnsSafeConflictError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var machineId = UUID.randomUUID();
    doThrow(new MachineDataIntegrityException()).when(machines).delete(user, machineId);

    mockMvc.perform(delete("/api/v1/machines/{machineId}", machineId).with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MACHINE_DATA_INTEGRITY_VIOLATION"));
  }

  @Test
  @DisplayName("2.R-API-001 P1 invalid machine query UUID returns safe query error")
  void invalidMachineQueryUuidReturnsSafeQueryError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/machines").param("plantId", "not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"))
        .andExpect(jsonPath("$.message").value("Query value is invalid."));
  }

  @Test
  @DisplayName("3.6-API-001 P1 optional telemetry fields map to machine command")
  void optionalTelemetryFieldsMapToMachineCommand() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var commandCaptor = ArgumentCaptor.forClass(MachineCommand.class);
    when(machines.create(eq(user), commandCaptor.capture())).thenReturn(view(machineId, plantId, groupId));

    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"machineGroupId\":\"" + groupId
            + "\",\"code\":\"BF-08410\",\"status\":\"ACTIVE\",\"optionalTelemetryFields\":[\"vibration\",\"rpm\"]}"))
        .andExpect(status().isCreated());

    assertThat(commandCaptor.getValue().optionalTelemetryFields()).containsExactly("vibration", "rpm");
  }

  @Test
  @DisplayName("3.6-API-002 P1 more than 10 optional telemetry fields return field errors")
  void overLimitOptionalTelemetryFieldsReturnFieldErrors() throws Exception {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    String overLimit = "[\"f01\",\"f02\",\"f03\",\"f04\",\"f05\",\"f06\",\"f07\",\"f08\",\"f09\",\"f10\",\"f11\"]";

    mockMvc.perform(post("/api/v1/machines")
        .with(auth(user(ApplicationRole.MANAGE)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"machineGroupId\":\"" + groupId
            + "\",\"code\":\"BF-08410\",\"status\":\"ACTIVE\",\"optionalTelemetryFields\":" + overLimit + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  private static MachineView view(UUID id, UUID plantId, UUID groupId) {
    return new MachineView(id, plantId, "GM1", "Plant GM1", groupId, "Forming", "BF-08410", "JBF19",
        MachineStatus.ACTIVE, "Juki", LocalDate.parse("2026-05-27"), "Pilot machine",
        Instant.parse("2026-05-27T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z"), List.of());
  }

  private static String payload(UUID plantId, UUID groupId, String code, String status) {
    return "{\"plantId\":\"" + plantId + "\",\"machineGroupId\":\"" + groupId + "\",\"code\":\"" + code
        + "\",\"name\":\"JBF19\",\"status\":\"" + status + "\",\"brand\":\"Juki\",\"installedAt\":\"2026-05-27\",\"notes\":\"Pilot machine\"}";
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
