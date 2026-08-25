package com.syncro.machine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.application.MachineService.MachineNotFoundException;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit coverage for the 9.2 team-scope split in {@link MachineService}: the list
 * path passes leaderGroupIds and teamGroupIds separately, and the detail path grants
 * a machine when its group is in the user's active team scope (additive relaxation).
 */
@ExtendWith(MockitoExtension.class)
class MachineServiceTest {

  @Mock private MachineRepository machines;
  @Mock private PlantRepository plants;
  @Mock private MachineGroupRepository machineGroups;
  @Mock private PlantScopeService plantScopes;
  @Mock private AuthUserPlantAssignmentRepository assignments;
  @Mock private AuditLogWriter auditLog;
  @Mock private OperationalScopeService operationalScopes;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

  private MachineService service;

  @BeforeEach
  void setup() {
    service = new MachineService(machines, plants, machineGroups, plantScopes, assignments, auditLog,
        operationalScopes, clock);
  }

  @Test
  void list_teamUser_passesLeaderAndTeamGroupParamsSeparately() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var leaderGroupId = UUID.randomUUID();
    var teamGroupId = UUID.randomUUID();
    var user = manageUser(userId);

    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, Instant.now(clock))));
    when(operationalScopes.derive(any(AuthenticatedUser.class)))
        .thenReturn(new OperationalScope(Set.of(plantId), Set.of(leaderGroupId), Set.of(teamGroupId)));
    when(machines.findAllScoped(eq(List.of(plantId)), eq(List.of(leaderGroupId)), eq(List.of(teamGroupId)),
        isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    var result = service.list(user, null, null, null);

    assertThat(result.items()).isEmpty();
  }

  @Test
  void get_teamGroupGrantsDetailWithoutPlantAccess() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var user = manageUser(userId);

    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine(plantId, groupId, machineId)));
    when(operationalScopes.derive(any(AuthenticatedUser.class)))
        .thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of(groupId)));

    var view = service.get(user, machineId);

    assertThat(view.id()).isEqualTo(machineId);
    assertThat(view.machineGroupId()).isEqualTo(groupId);
  }

  @Test
  void get_notInTeamScope_requiresPlantAccess() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var user = manageUser(userId);

    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine(plantId, groupId, machineId)));
    when(operationalScopes.derive(any(AuthenticatedUser.class)))
        .thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));
    doThrow(new PlantAccessDeniedException()).when(plantScopes).requirePlantAccess(user, plantId);

    assertThatThrownBy(() -> service.get(user, machineId))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  void get_unknownMachine_throws() {
    var machineId = UUID.randomUUID();
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(superAdmin(), machineId))
        .isInstanceOf(MachineNotFoundException.class);
  }

  private MachineEntity machine(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new PlantEntity(plantId, "P1", "Plant 1", Instant.now(clock), Instant.now(clock));
    var group = new MachineGroupEntity(groupId, plant, "Group 1", Instant.now(clock), Instant.now(clock));
    return new MachineEntity(machineId, plant, group, "M1", "Machine 1", MachineStatus.ACTIVE, null, null, null,
        null, Instant.now(clock), Instant.now(clock));
  }

  private AuthenticatedUser manageUser(UUID userId) {
    return new AuthenticatedUser(userId.toString(), "manager", ApplicationRole.MANAGE);
  }

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin", ApplicationRole.SUPER_ADMIN);
  }
}
