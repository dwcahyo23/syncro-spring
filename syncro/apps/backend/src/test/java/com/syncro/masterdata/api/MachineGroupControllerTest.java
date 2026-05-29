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
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.masterdata.application.MachineGroupService;
import com.syncro.masterdata.application.MachineGroupService.DuplicateMachineGroupNameException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupDataIntegrityException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupMutationForbiddenException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupNotFoundException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupListView;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupView;
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

@WebMvcTest(MachineGroupController.class)
@Import({SecurityConfig.class, MachineGroupExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class MachineGroupControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private MachineGroupService machineGroups;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("2.2-API-001 P0 unauthenticated users cannot list machine groups")
  void listMachineGroupsRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/machine-groups").param("plantId", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.2-API-002 P1 SUPER_ADMIN can list machine groups by plant")
  void superAdminCanListMachineGroupsByPlant() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    when(machineGroups.list(user, plantId, null, 0, 100, "name,asc")).thenReturn(new MachineGroupListView(List.of(new MachineGroupView(
        groupId,
        plantId,
        "GM1",
        "Plant GM1",
        "Forming",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z"))), 1, 0, 100, "name,asc"));

    mockMvc.perform(get("/api/v1/machine-groups").param("plantId", plantId.toString()).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(groupId.toString()))
        .andExpect(jsonPath("$.items[0].plantId").value(plantId.toString()))
        .andExpect(jsonPath("$.items[0].plantCode").value("GM1"))
        .andExpect(jsonPath("$.items[0].plantName").value("Plant GM1"))
        .andExpect(jsonPath("$.items[0].name").value("Forming"));
  }

  @Test
  @DisplayName("2.2-API-003 P1 MANAGE can create machine groups")
  void manageCanCreateMachineGroup() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    when(machineGroups.create(eq(user), any())).thenReturn(new MachineGroupView(
        groupId,
        plantId,
        "GM1",
        "Plant GM1",
        "Forming",
        Instant.parse("2026-05-27T00:00:00Z"),
        Instant.parse("2026-05-27T00:00:00Z")));

    mockMvc.perform(post("/api/v1/machine-groups")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(groupId.toString()))
        .andExpect(jsonPath("$.plantCode").value("GM1"))
        .andExpect(jsonPath("$.name").value("Forming"));
  }

  @Test
  @DisplayName("2.2-API-004 P0 VIEWER cannot create machine groups")
  void viewerCannotCreateMachineGroup() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var plantId = UUID.randomUUID();
    doThrow(new MachineGroupMutationForbiddenException()).when(machineGroups).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machine-groups")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @ParameterizedTest
  @DisplayName("2.2-API-005 P1 invalid machine group requests return field errors")
  @ValueSource(strings = {
      "{\"plantId\":null,\"name\":\"Forming\"}",
      "{\"name\":\"Forming\"}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"\"}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"name\":null}",
      "{\"plantId\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ\"}"
  })
  void invalidMachineGroupRequestReturnsFieldErrors(String payload) throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/machine-groups")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.2-API-006 P1 malformed JSON returns safe error")
  void malformedJsonReturnsSafeError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(post("/api/v1/machine-groups")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
        .andExpect(jsonPath("$.message").value("Request body is malformed."))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.2-API-007 P1 duplicate same-plant name returns safe validation error")
  void duplicateMachineGroupNameReturnsSafeValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    doThrow(new DuplicateMachineGroupNameException()).when(machineGroups).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machine-groups")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_MACHINE_GROUP_NAME"))
        .andExpect(jsonPath("$.message").value("Machine group name already exists for this plant."));
  }

  @Test
  @DisplayName("2.2-API-008 P0 MANAGE out-of-scope list returns safe forbidden error")
  void outOfScopeListReturnsSafeForbiddenError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    doThrow(new PlantAccessDeniedException()).when(machineGroups).list(user, plantId, null, 0, 100, "name,asc");

    mockMvc.perform(get("/api/v1/machine-groups").param("plantId", plantId.toString()).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("2.2-API-009 P1 missing machine group returns safe not-found error")
  void missingMachineGroupReturnsSafeNotFoundError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    var groupId = UUID.randomUUID();
    doThrow(new MachineGroupNotFoundException()).when(machineGroups).get(user, groupId);

    mockMvc.perform(get("/api/v1/machine-groups/{machineGroupId}", groupId).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_GROUP_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Machine group was not found."));
  }

  @Test
  @DisplayName("2.2-API-014 P0 unauthenticated users cannot get machine groups")
  void getMachineGroupRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/machine-groups/{machineGroupId}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.2-API-015 P1 invalid plant filter returns safe query error")
  void invalidPlantFilterReturnsSafeQueryError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/machine-groups").param("plantId", "not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"))
        .andExpect(jsonPath("$.message").value("Query value is invalid."));
  }

  @Test
  @DisplayName("2.2-API-016 P0 MANAGE cannot create machine group for out-of-scope plant")
  void manageCannotCreateMachineGroupForOutOfScopePlant() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var plantId = UUID.randomUUID();
    doThrow(new PlantAccessDeniedException()).when(machineGroups).create(eq(user), any());

    mockMvc.perform(post("/api/v1/machine-groups")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @ParameterizedTest
  @DisplayName("2.2-API-010 P0 unauthenticated users cannot mutate machine groups")
  @ValueSource(strings = {"POST", "PUT", "DELETE"})
  void mutateMachineGroupsRequiresAuthentication(String method) throws Exception {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var request = switch (method) {
      case "POST" -> post("/api/v1/machine-groups")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}");
      case "PUT" -> put("/api/v1/machine-groups/{machineGroupId}", groupId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}");
      case "DELETE" -> delete("/api/v1/machine-groups/{machineGroupId}", groupId);
      default -> throw new IllegalArgumentException(method);
    };

    mockMvc.perform(request)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("2.2-API-011 P0 VIEWER cannot update machine groups")
  void viewerCannotUpdateMachineGroup() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    doThrow(new MachineGroupMutationForbiddenException()).when(machineGroups).update(eq(user), eq(groupId), any());

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}", groupId)
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"plantId\":\"" + plantId + "\",\"name\":\"Forming\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.2-API-012 P0 VIEWER cannot delete machine groups")
  void viewerCannotDeleteMachineGroup() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var groupId = UUID.randomUUID();
    doThrow(new MachineGroupMutationForbiddenException()).when(machineGroups).delete(user, groupId);

    mockMvc.perform(delete("/api/v1/machine-groups/{machineGroupId}", groupId).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("2.2-API-013 P1 invalid machine group id returns safe path error")
  void invalidMachineGroupIdReturnsSafePathError() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/machine-groups/not-a-uuid").with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"))
        .andExpect(jsonPath("$.message").value("Path value is invalid."));
  }

  @Test
  @DisplayName("2.2-API-017 P1 delete integrity conflict returns safe conflict error")
  void deleteIntegrityConflictReturnsSafeConflictError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var groupId = UUID.randomUUID();
    doThrow(new MachineGroupDataIntegrityException()).when(machineGroups).delete(user, groupId);

    mockMvc.perform(delete("/api/v1/machine-groups/{machineGroupId}", groupId).with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("MACHINE_GROUP_DATA_INTEGRITY_VIOLATION"))
        .andExpect(jsonPath("$.message").value("Machine group data conflicts with existing records."));
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
