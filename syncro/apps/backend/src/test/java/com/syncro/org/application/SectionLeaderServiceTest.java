package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.infrastructure.SectionEntity;
import com.syncro.org.infrastructure.SectionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SectionLeaderServiceTest {
  @Mock
  private SectionRepository sections;
  @Mock
  private PlantRepository plants;
  @Mock
  private PlantScopeService plantScopes;
  @Mock
  private SectionActiveMachineGroupReader activeMachineGroupReader;
  @Mock
  private AuthUserRepository users;
  @Mock
  private MachineGroupRepository machineGroups;
  @Mock
  private MachineRepository machines;
  @Mock
  private MachineResponsibilityRepository responsibilities;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void assignLeaderSetsLeaderUserIdAndCreatesResponsibilities() {
    var sectionId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var leaderId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var section = section(sectionId, plantId);
    when(sections.findByIdForUpdate(sectionId)).thenReturn(Optional.of(section));
    when(users.findById(leaderId)).thenReturn(Optional.of(activeUser(leaderId)));
    when(machineGroups.findIdsBySectionId(sectionId)).thenReturn(List.of(groupId));
    when(machines.findIdsByMachineGroupIdIn(List.of(groupId))).thenReturn(List.of(machineId));
    when(responsibilities.findAssignments(List.of(machineId), leaderId, ResponsibilityLevel.LEADER))
        .thenReturn(List.of());
    when(sections.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new SectionService(sections, plants, plantScopes, activeMachineGroupReader, users,
        machineGroups, machines, responsibilities, auditLog, clock);
    var view = service.assignLeader(user(ApplicationRole.MANAGER_MAINTENANCE), sectionId, leaderId);

    assertThat(view.leaderUserId()).isEqualTo(leaderId);
    assertThat(section.getLeaderUserId()).isEqualTo(leaderId);
    verify(responsibilities).save(any());
  }

  @Test
  void assignLeaderRejectsInactiveUser() {
    var sectionId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var leaderId = UUID.randomUUID();
    var section = section(sectionId, plantId);
    when(sections.findByIdForUpdate(sectionId)).thenReturn(Optional.of(section));
    var now = Instant.parse("2026-08-27T00:00:00Z");
    var inactive = new AuthUserEntity(leaderId, "leader@syncro.dev", "hash", ApplicationRole.TECHNICIAN, false, now, now);
    when(users.findById(leaderId)).thenReturn(Optional.of(inactive));

    var service = new SectionService(sections, plants, plantScopes, activeMachineGroupReader, users,
        machineGroups, machines, responsibilities, auditLog, clock);
    assertThatThrownBy(() -> service.assignLeader(user(ApplicationRole.MANAGER_MAINTENANCE), sectionId, leaderId))
        .isInstanceOf(SectionService.SectionLeaderUserInactiveException.class);
  }

  @Test
  void assignLeaderRejectsUnknownUser() {
    var sectionId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var leaderId = UUID.randomUUID();
    var section = section(sectionId, plantId);
    when(sections.findByIdForUpdate(sectionId)).thenReturn(Optional.of(section));
    when(users.findById(leaderId)).thenReturn(Optional.empty());

    var service = new SectionService(sections, plants, plantScopes, activeMachineGroupReader, users,
        machineGroups, machines, responsibilities, auditLog, clock);
    assertThatThrownBy(() -> service.assignLeader(user(ApplicationRole.MANAGER_MAINTENANCE), sectionId, leaderId))
        .isInstanceOf(SectionService.SectionLeaderUserNotFoundException.class);
  }

  @Test
  void clearLeaderRemovesResponsibilities() {
    var sectionId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var leaderId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var section = section(sectionId, plantId);
    section.assignLeader(leaderId, Instant.now(clock));
    when(sections.findByIdForUpdate(sectionId)).thenReturn(Optional.of(section));
    when(machineGroups.findIdsBySectionId(sectionId)).thenReturn(List.of(groupId));
    when(machines.findIdsByMachineGroupIdIn(List.of(groupId))).thenReturn(List.of(machineId));
    when(sections.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new SectionService(sections, plants, plantScopes, activeMachineGroupReader, users,
        machineGroups, machines, responsibilities, auditLog, clock);
    service.clearLeader(user(ApplicationRole.MANAGER_MAINTENANCE), sectionId);

    assertThat(section.getLeaderUserId()).isNull();
    verify(responsibilities).deleteByMachineIdInAndUserId(List.of(machineId), leaderId);
  }

  @Test
  void clearLeaderNoOpWhenNoLeader() {
    var sectionId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var section = section(sectionId, plantId);
    when(sections.findByIdForUpdate(sectionId)).thenReturn(Optional.of(section));

    var service = new SectionService(sections, plants, plantScopes, activeMachineGroupReader, users,
        machineGroups, machines, responsibilities, auditLog, clock);
    service.clearLeader(user(ApplicationRole.MANAGER_MAINTENANCE), sectionId);

    verify(responsibilities, never()).deleteByMachineIdInAndUserId(any(), any());
  }

  private static SectionEntity section(UUID id, UUID plantId) {
    return new SectionEntity(id, new PlantEntity(plantId, "GM1", "Plant GM1",
        Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z")),
        "MACHINERY", "Machinery", true,
        Instant.parse("2026-08-27T00:00:00Z"), Instant.parse("2026-08-27T00:00:00Z"));
  }

  private static AuthUserEntity activeUser(UUID id) {
    var now = Instant.parse("2026-08-27T00:00:00Z");
    return new AuthUserEntity(id, "user-" + id + "@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true, now, now);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
