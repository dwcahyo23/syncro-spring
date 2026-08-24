package com.syncro.shiftconfig.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.shiftconfig.application.ShiftConfigService;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineGroupNotFoundException;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineNotFoundException;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineGroupShiftConfigView;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineShiftConfigView;
import com.syncro.shiftconfig.application.ShiftConfigService.MutationForbiddenException;
import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowView;
import com.syncro.shiftconfig.application.ShiftConfigService.ValidationException;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ShiftConfigController.class)
@Import({SecurityConfig.class, ShiftConfigExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class ShiftConfigControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ShiftConfigService shiftConfigs;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("8.5-API-001 P0 PUT group config returns 200 with stored windows")
  void putGroupConfigReturnsView() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var groupId = UUID.randomUUID();
    when(shiftConfigs.setGroupConfig(eq(user), eq(groupId),
        eq(List.of(new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0)),
            new ShiftWindowCommand(LocalTime.of(23, 0), LocalTime.of(6, 0))))))
        .thenReturn(new MachineGroupShiftConfigView(List.of(
            new ShiftWindowView(1, LocalTime.of(7, 0), LocalTime.of(15, 0)),
            new ShiftWindowView(2, LocalTime.of(23, 0), LocalTime.of(6, 0)))));

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}/shift-config", groupId)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"07:00","endTime":"15:00"},{"startTime":"23:00","endTime":"06:00"}]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shifts.length()").value(2))
        .andExpect(jsonPath("$.shifts[0].shiftNumber").value(1))
        .andExpect(jsonPath("$.shifts[0].startTime").value("07:00"))
        .andExpect(jsonPath("$.shifts[1].shiftNumber").value(2))
        .andExpect(jsonPath("$.shifts[1].startTime").value("23:00"))
        .andExpect(jsonPath("$.shifts[1].endTime").value("06:00"));
  }

  @Test
  @DisplayName("8.5-API-002 P0 GET group config returns 200 with windows")
  void getGroupConfigReturnsView() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var groupId = UUID.randomUUID();
    when(shiftConfigs.getGroupConfig(eq(user), eq(groupId)))
        .thenReturn(new MachineGroupShiftConfigView(List.of(
            new ShiftWindowView(1, LocalTime.of(7, 0), LocalTime.of(15, 0)))));

    mockMvc.perform(get("/api/v1/machine-groups/{machineGroupId}/shift-config", groupId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shifts[0].startTime").value("07:00"));
  }

  @Test
  @DisplayName("8.5-API-003 P0 PUT machine config returns 200 with resolved view")
  void putMachineConfigReturnsResolvedView() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var machineId = UUID.randomUUID();
    when(shiftConfigs.setMachineConfig(eq(user), eq(machineId),
        eq(List.of(new ShiftWindowCommand(LocalTime.of(9, 0), LocalTime.of(17, 0))))))
        .thenReturn(new MachineShiftConfigView("MACHINE", false,
            List.of(new ShiftWindowView(1, LocalTime.of(9, 0), LocalTime.of(17, 0)))));

    mockMvc.perform(put("/api/v1/machines/{machineId}/shift-config", machineId)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"09:00","endTime":"17:00"}]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("MACHINE"))
        .andExpect(jsonPath("$.inheritedFromGroup").value(false))
        .andExpect(jsonPath("$.shifts[0].startTime").value("09:00"));
  }

  @Test
  @DisplayName("8.5-API-004 P0 GET machine config returns 200 with inherited source")
  void getMachineConfigReturnsInherited() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var machineId = UUID.randomUUID();
    when(shiftConfigs.getMachineConfig(eq(user), eq(machineId)))
        .thenReturn(new MachineShiftConfigView("MACHINE_GROUP", true,
            List.of(new ShiftWindowView(1, LocalTime.of(7, 0), LocalTime.of(15, 0)))));

    mockMvc.perform(get("/api/v1/machines/{machineId}/shift-config", machineId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("MACHINE_GROUP"))
        .andExpect(jsonPath("$.inheritedFromGroup").value(true));
  }

  @Test
  @DisplayName("8.5-API-005 P0 GET machine config returns 200 with source NONE when empty")
  void getMachineConfigReturnsNone() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    var machineId = UUID.randomUUID();
    when(shiftConfigs.getMachineConfig(eq(user), eq(machineId)))
        .thenReturn(new MachineShiftConfigView("NONE", false, List.of()));

    mockMvc.perform(get("/api/v1/machines/{machineId}/shift-config", machineId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("NONE"))
        .andExpect(jsonPath("$.shifts").isEmpty());
  }

  @Test
  @DisplayName("8.5-API-006 P0 DELETE machine config returns 204")
  void deleteMachineConfigReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(delete("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("8.5-API-007 P0 four shifts return 400 VALIDATION_ERROR with fieldErrors.shifts")
  void fourShiftsReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new ValidationException(Map.of("shifts", "At most 3 shifts are allowed.")))
        .when(shiftConfigs).setGroupConfig(eq(user), any(), any());

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"07:00","endTime":"15:00"},{"startTime":"15:00","endTime":"23:00"},{"startTime":"23:00","endTime":"06:00"},{"startTime":"06:00","endTime":"14:00"}]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.shifts").exists());
  }

  @Test
  @DisplayName("8.5-API-008 P0 below-LEADER job scope returns 403 JOB_SCOPE_REQUIRED with explanation")
  void belowLeaderJobScopeReturnsExplanation() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new JobScopeForbiddenException("LEADER")).when(shiftConfigs).setGroupConfig(eq(user), any(), any());

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"07:00","endTime":"15:00"}]}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("JOB_SCOPE_REQUIRED"))
        .andExpect(jsonPath("$.message").value("This action requires job scope LEADER or above."));
  }

  @Test
  @DisplayName("8.5-API-009 P0 VIEWER app-role denial returns 403 FORBIDDEN")
  void viewerForbiddenByAppRoleGate() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new MutationForbiddenException()).when(shiftConfigs).setGroupConfig(eq(user), any(), any());

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"07:00","endTime":"15:00"}]}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.5-API-010 P0 plant-access denial returns 403 FORBIDDEN")
  void plantAccessDenialReturnsForbidden() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new PlantAccessDeniedException()).when(shiftConfigs).getMachineConfig(eq(user), any());

    mockMvc.perform(get("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.5-API-011 P0 unknown machine group returns 404 MACHINE_GROUP_NOT_FOUND")
  void unknownGroupReturnsNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new MachineGroupNotFoundException()).when(shiftConfigs).setGroupConfig(eq(user), any(), any());

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"07:00","endTime":"15:00"}]}
                """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_GROUP_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.5-API-012 P0 unknown machine returns 404 MACHINE_NOT_FOUND on all endpoints")
  void unknownMachineReturnsNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new MachineNotFoundException()).when(shiftConfigs).setMachineConfig(eq(user), any(), any());
    doThrow(new MachineNotFoundException()).when(shiftConfigs).getMachineConfig(eq(user), any());
    doThrow(new MachineNotFoundException()).when(shiftConfigs).clearMachineConfig(eq(user), any());

    mockMvc.perform(put("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"07:00","endTime":"15:00"}]}
                """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));

    mockMvc.perform(get("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));

    mockMvc.perform(delete("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.5-API-013 P0 malformed time value returns 400 MALFORMED_JSON without calling the service")
  void malformedTimeReturnsMalformedJson() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(put("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"25:00","endTime":"15:00"}]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
  }

  @Test
  @DisplayName("8.5-API-014 P0 unauthenticated request is rejected")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/machine-groups/{machineGroupId}/shift-config", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("8.5-API-015 P1 group PUT with empty array clears the schedule")
  void groupPutEmptyArrayClears() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var groupId = UUID.randomUUID();
    when(shiftConfigs.setGroupConfig(eq(user), eq(groupId), eq(List.of())))
        .thenReturn(new MachineGroupShiftConfigView(List.of()));

    mockMvc.perform(put("/api/v1/machine-groups/{machineGroupId}/shift-config", groupId)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[]}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.shifts").isEmpty());
  }

  @Test
  @DisplayName("8.5-API-016 P1 machine PUT zero-length window returns 400 VALIDATION_ERROR")
  void machinePutZeroLengthWindowReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    when(shiftConfigs.setMachineConfig(eq(user), any(), any())).thenThrow(
        new ValidationException(Map.of("shifts", "Shift 1 must not be zero-length.")));

    mockMvc.perform(put("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"08:00","endTime":"08:00"}]}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.shifts").value("Shift 1 must not be zero-length."));
  }

  @Test
  @DisplayName("8.5-API-017 P1 null shifts payload returns 400 instead of clearing the schedule")
  void nullShiftsPayloadReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(put("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":null}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.shifts").value("Shifts payload is required."));
  }

  @Test
  @DisplayName("8.5-API-018 P1 VIEWER DELETE override returns 403 FORBIDDEN")
  void viewerDeleteOverrideForbidden() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new MutationForbiddenException()).when(shiftConfigs).clearMachineConfig(eq(user), any());

    mockMvc.perform(delete("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.5-API-019 P1 concurrent replace conflict returns 409 SHIFT_CONFIG_CONFLICT")
  void concurrentReplaceReturnsConflict() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new DataIntegrityViolationException("uq_machine_shift_windows_machine_shift_number"))
        .when(shiftConfigs).setMachineConfig(eq(user), any(), any());

    mockMvc.perform(put("/api/v1/machines/{machineId}/shift-config", UUID.randomUUID())
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"shifts":[{"startTime":"09:00","endTime":"17:00"}]}
                """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SHIFT_CONFIG_CONFLICT"));
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}