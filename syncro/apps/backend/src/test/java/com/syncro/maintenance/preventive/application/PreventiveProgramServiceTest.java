package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.CreateProgramCommand;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveValidationException;
import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreventiveProgramServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

  @Mock
  private PreventiveProgramRepository programs;
  @Mock
  private PreventiveScheduleRepository schedules;
  @Mock
  private MachineRepository machines;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private OperationalScopeService scopes;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID staffId = UUID.randomUUID();

  private PreventiveProgramService service;
  private MachineEntity machine;

  @BeforeEach
  void setUp() {
    service = new PreventiveProgramService(programs, schedules, machines, auditLog, scopes, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
  }

  @Test
  @DisplayName("11.1-SVC-001 P0 staff with plant access creates a program and generates a schedule window")
  void createProgramOk() {
    var user = staffUser();
    var scope = new OperationalScope(Set.of(plantId), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(programs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(schedules.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(schedules.existsByProgramIdAndDueDate(any(), any())).thenReturn(false);

    var created = service.create(user, new CreateProgramCommand(machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, 15, null, "Monthly lube", null, false));

    assertThat(created.title()).isEqualTo("Monthly lube");
    assertThat(created.category()).isEqualTo(PreventiveCategory.MECHANICAL);
    assertThat(created.scheduleType()).isEqualTo(ScheduleType.MONTHLY);
    // Window of 12 SCHEDULED instances materialized.
    var captor = ArgumentCaptor.forClass(PreventiveScheduleEntity.class);
    verify(schedules, org.mockito.Mockito.atLeast(12)).saveAndFlush(captor.capture());
    assertThat(captor.getAllValues().getFirst().getStatus()).isEqualTo(ScheduleStatus.SCHEDULED);
    verify(auditLog).record(eq(user), org.mockito.ArgumentMatchers.argThat(r ->
        r.action() == AuditAction.CREATE && r.entityType() == AuditEntityType.PREVENTIVE_PROGRAM));
  }

  @Test
  @DisplayName("11.1-SVC-002 P0 MONTHLY program with monthOfYear is rejected")
  void createMonthlyWithMonthRejected() {
    var user = staffUser();
    var scope = new OperationalScope(Set.of(plantId), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    assertThatThrownBy(() -> service.create(user, new CreateProgramCommand(machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, 15, 3, "Bad", null, false)))
        .isInstanceOf(PreventiveValidationException.class);
  }

  @Test
  @DisplayName("11.1-SVC-003 P0 ANNUAL program without monthOfYear is rejected")
  void createAnnualWithoutMonthRejected() {
    var user = staffUser();
    var scope = new OperationalScope(Set.of(plantId), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    assertThatThrownBy(() -> service.create(user, new CreateProgramCommand(machineId, PreventiveCategory.ELECTRICAL,
        ScheduleType.ANNUAL, 15, null, "Bad", null, false)))
        .isInstanceOf(PreventiveValidationException.class);
  }

  @Test
  @DisplayName("11.1-SVC-004 P0 user without plant/group access is forbidden")
  void createForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "auditor@test", ApplicationRole.AUDITOR);
    var scope = new OperationalScope(Set.of(), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    assertThatThrownBy(() -> service.create(user, new CreateProgramCommand(machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, 15, null, "Nope", null, false)))
        .isInstanceOf(PreventiveForbiddenException.class);
  }

  @Test
  @DisplayName("11.1-SVC-005 P0 unknown machine is MACHINE_NOT_FOUND")
  void createUnknownMachine() {
    var user = staffUser();
    var scope = new OperationalScope(Set.of(plantId), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, new CreateProgramCommand(machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, 15, null, "Nope", null, false)))
        .isInstanceOf(PreventiveProgramService.MachineNotFoundException.class);
  }

  @Test
  @DisplayName("11.1-SVC-006 P0 nextAnchor clamps Feb 30 for a MONTHLY program")
  void nextAnchorClampsShortMonth() {
    var program = new PreventiveProgramEntity(UUID.randomUUID(), machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, (short) 30, null, "P", null, true, false, staffId, NOW, NOW);

    var anchor = PreventiveProgramService.nextAnchor(program, LocalDate.of(2026, 2, 1));

    assertThat(anchor).isEqualTo(LocalDate.of(2026, 2, 28));
  }

  @Test
  @DisplayName("11.1-SVC-007 P0 nextAnchor for MONTHLY advances month by month")
  void nextAnchorMonthlyAdvances() {
    var program = new PreventiveProgramEntity(UUID.randomUUID(), machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, (short) 15, null, "P", null, true, false, staffId, NOW, NOW);

    var anchor = PreventiveProgramService.nextAnchor(program, LocalDate.of(2026, 1, 16));

    assertThat(anchor).isEqualTo(LocalDate.of(2026, 2, 15));
  }

  @Test
  @DisplayName("11.1-SVC-008 P0 nextAnchor for ANNUAL advances year by year")
  void nextAnchorAnnualAdvances() {
    var program = new PreventiveProgramEntity(UUID.randomUUID(), machineId, PreventiveCategory.ELECTRICAL,
        ScheduleType.ANNUAL, (short) 10, (short) 3, "P", null, true, false, staffId, NOW, NOW);

    var anchor = PreventiveProgramService.nextAnchor(program, LocalDate.of(2026, 4, 1));

    assertThat(anchor).isEqualTo(LocalDate.of(2027, 3, 10));
  }

  @Test
  @DisplayName("11.1-SVC-009 P0 rollForwardNext materializes the first anchor after completion once")
  void rollForwardNextOk() {
    var programId = UUID.randomUUID();
    var program = new PreventiveProgramEntity(programId, machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, (short) 15, null, "P", null, true, false, staffId, NOW, NOW);
    when(programs.findById(programId)).thenReturn(Optional.of(program));
    when(schedules.existsByProgramIdAndDueDate(programId, LocalDate.of(2026, 9, 15))).thenReturn(false);
    when(schedules.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var schedule = service.rollForwardNext(programId, Instant.parse("2026-08-20T00:00:00Z"));

    assertThat(schedule.dueDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(schedule.status()).isEqualTo(ScheduleStatus.SCHEDULED);
  }

  @Test
  @DisplayName("11.1-SVC-010 P0 rollForwardNext on an existing anchor does not duplicate")
  void rollForwardNextDuplicate() {
    var programId = UUID.randomUUID();
    var program = new PreventiveProgramEntity(programId, machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, (short) 15, null, "P", null, true, false, staffId, NOW, NOW);
    when(programs.findById(programId)).thenReturn(Optional.of(program));
    when(schedules.existsByProgramIdAndDueDate(programId, LocalDate.of(2026, 9, 15))).thenReturn(true);
    var existing = new PreventiveScheduleEntity(UUID.randomUUID(), programId, machineId, LocalDate.of(2026, 9, 15),
        ScheduleStatus.SCHEDULED, null, null, NOW, NOW);
    when(schedules.findByProgramIdOrderByDueDateAsc(programId)).thenReturn(List.of(existing));

    var schedule = service.rollForwardNext(programId, Instant.parse("2026-08-20T00:00:00Z"));

    assertThat(schedule.dueDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(schedule.id()).isEqualTo(existing.getId());
  }

  private AuthenticatedUser staffUser() {
    return new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.STAFF_MAINTENANCE);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}