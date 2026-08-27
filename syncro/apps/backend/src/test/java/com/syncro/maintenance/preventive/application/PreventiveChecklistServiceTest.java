package com.syncro.maintenance.preventive.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ApproveCommand;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistCommand;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.InvalidStateTransitionException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ItemCommand;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ScheduleNotFoundException;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveChecklistResultRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveProgramRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.math.BigDecimal;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreventiveChecklistServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  @Mock
  private PreventiveScheduleRepository schedules;
  @Mock
  private PreventiveProgramRepository programs;
  @Mock
  private MachineRepository machines;
  @Mock
  private PreventiveChecklistResultRepository results;
  @Mock
  private PreventiveChecklistItemRepository items;
  @Mock
  private PreventiveProgramService programService;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private WorkOrderService workOrders;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID scheduleId = UUID.randomUUID();
  private final UUID programId = UUID.randomUUID();
  private final UUID staffId = UUID.randomUUID();
  private final UUID leaderId = UUID.randomUUID();

  private PreventiveChecklistService service;
  private MachineEntity machine;
  private PreventiveScheduleEntity schedule;
  private PreventiveProgramEntity program;

  @BeforeEach
  void setUp() {
    service = new PreventiveChecklistService(schedules, programs, machines, results, items, programService, auditLog,
        scopes, workOrders, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    program = new PreventiveProgramEntity(programId, machineId, PreventiveCategory.MECHANICAL, ScheduleType.MONTHLY,
        (short) 15, null, "Monthly lube", null, true, true, staffId, NOW, NOW);
    lenient().when(programs.findById(programId)).thenReturn(Optional.of(program));
    schedule = new PreventiveScheduleEntity(scheduleId, programId, machineId, LocalDate.of(2026, 9, 15),
        ScheduleStatus.SCHEDULED, null, null, NOW, NOW);
    lenient().when(schedules.findById(scheduleId)).thenReturn(Optional.of(schedule));
    lenient().when(schedules.findByIdForUpdate(scheduleId)).thenReturn(Optional.of(schedule));
  }

  @Test
  @DisplayName("11.2-SVC-001 P0 staff with plant access submits a checklist (SCHEDULED → IN_PROGRESS)")
  void submitOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(results.existsByScheduleId(scheduleId)).thenReturn(false);
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.submit(user, scheduleId.toString(), command());

    assertThat(view.scheduleId()).isEqualTo(scheduleId);
    assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.IN_PROGRESS);
    verify(schedules).saveAndFlush(schedule);
  }

  @Test
  @DisplayName("11.2-SVC-002 P0 out-of-scope user is forbidden")
  void submitForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "auditor@test", ApplicationRole.AUDITOR);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.submit(user, scheduleId.toString(), command()))
        .isInstanceOf(ChecklistForbiddenException.class);
  }

  @Test
  @DisplayName("11.2-SVC-003 P0 unknown schedule is SCHEDULE_NOT_FOUND")
  void submitUnknownSchedule() {
    var user = staffUser();
    when(schedules.findByIdForUpdate(scheduleId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submit(user, scheduleId.toString(), command()))
        .isInstanceOf(ScheduleNotFoundException.class);
  }

  @Test
  @DisplayName("11.2-SVC-004 P0 leader approves → PERFORMED + rolls forward")
  void approveRollsForward() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "notes",
        null, null, null, null, null, NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.findByResultIdOrderByPositionAsc(any())).thenReturn(List.of());

    service.approve(user, scheduleId.toString(), new ApproveCommand("preventive/sig.png", "Leader", "ok"));

    assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PERFORMED);
    assertThat(result.getLeaderId()).isEqualTo(leaderId);
    assertThat(result.getApprovedAt()).isNotNull();
    verify(programService).rollForwardNext(programId, NOW);
  }

  @Test
  @DisplayName("11.2-SVC-005 P0 approve without signature is rejected")
  void approveMissingSignature() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);

    assertThatThrownBy(() -> service.approve(user, scheduleId.toString(), new ApproveCommand(" ", "Leader", null)))
        .isInstanceOf(com.syncro.maintenance.preventive.application.PreventiveChecklistService.MissingSignatureException.class);
  }

  @Test
  @DisplayName("11.2-SVC-006 P0 approve on SCHEDULED (no checklist) is INVALID_STATE_TRANSITION")
  void approveBadState() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.approve(user, scheduleId.toString(),
        new ApproveCommand("preventive/sig.png", "Leader", null)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  @DisplayName("11.2-SVC-007 P0 leader skip → SKIPPED, no roll-forward")
  void skipOk() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    service.skip(user, scheduleId.toString());

    assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SKIPPED);
  }

  @Test
  @DisplayName("11.2-SVC-008 P0 amend before approval replaces items")
  void amendOk() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "notes",
        null, null, null, null, null, NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.amend(user, scheduleId.toString(), command());

    verify(items).deleteByResultId(result.getId());
    verify(items).saveAndFlush(any(PreventiveChecklistItemEntity.class));
  }

  @Test
  @DisplayName("11.2-SVC-009 P0 LSL > USL validation is rejected")
  void lslExceedsUsl() {
    var user = staffUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    var bad = new ChecklistCommand("x", List.of(new ItemCommand("t", "v",
        new BigDecimal("10"), new BigDecimal("5"), null)));

    assertThatThrownBy(() -> service.submit(user, scheduleId.toString(), bad))
        .isInstanceOf(com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistValidationException.class);
  }

  @Test
  @DisplayName("11.3-SVC-001 P0 approve with autoWorkorder creates an internal preventive workorder")
  void approveAutoWorkorder() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "notes",
        null, null, null, null, null, NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.findByResultIdOrderByPositionAsc(any())).thenReturn(List.of());
    when(workOrders.existsByPreventiveScheduleId(scheduleId)).thenReturn(false);
    when(workOrders.createSystem(eq(machineId), eq(WorkOrderService.PREVENTIVE_CATEGORY_CODE),
        eq("Preventive: Monthly lube due 2026-09-15"), eq(scheduleId)))
        .thenReturn("WO-2609-00001");

    service.approve(user, scheduleId.toString(), new ApproveCommand("preventive/sig.png", "Leader", "ok"));

    verify(workOrders).createSystem(eq(machineId), eq(WorkOrderService.PREVENTIVE_CATEGORY_CODE),
        eq("Preventive: Monthly lube due 2026-09-15"), eq(scheduleId));
  }

  @Test
  @DisplayName("11.3-SVC-002 P0 approve is idempotent when a workorder already exists for the schedule")
  void approveAutoWorkorderIdempotent() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "notes",
        null, null, null, null, null, NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.findByResultIdOrderByPositionAsc(any())).thenReturn(List.of());
    when(workOrders.existsByPreventiveScheduleId(scheduleId)).thenReturn(true);

    service.approve(user, scheduleId.toString(), new ApproveCommand("preventive/sig.png", "Leader", "ok"));

    verify(workOrders, org.mockito.Mockito.never()).createSystem(any(), any(), any(), any());
  }

  @Test
  @DisplayName("11.3-SVC-003 P0 approve with autoWorkorder=false does not create a workorder")
  void approveNoAutoWorkorder() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);
    var noAutoProgram = new PreventiveProgramEntity(programId, machineId, PreventiveCategory.MECHANICAL,
        ScheduleType.MONTHLY, (short) 15, null, "Monthly lube", null, true, false, staffId, NOW, NOW);
    when(programs.findById(programId)).thenReturn(Optional.of(noAutoProgram));
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "notes",
        null, null, null, null, null, NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.findByResultIdOrderByPositionAsc(any())).thenReturn(List.of());

    service.approve(user, scheduleId.toString(), new ApproveCommand("preventive/sig.png", "Leader", "ok"));

    verify(workOrders, org.mockito.Mockito.never()).createSystem(any(), any(), any(), any());
  }

  @Test
  @DisplayName("11.3-SVC-004 P0 workorder creation failure does not roll back PERFORMED")
  void approveAutoWorkorderFailureNonFatal() {
    var user = leaderUser();
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    schedule.transition(ScheduleStatus.IN_PROGRESS, NOW, staffId);
    var result = new PreventiveChecklistResultEntity(UUID.randomUUID(), scheduleId, staffId, NOW, "notes",
        null, null, null, null, null, NOW, NOW);
    when(results.findByScheduleId(scheduleId)).thenReturn(Optional.of(result));
    when(results.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(items.findByResultIdOrderByPositionAsc(any())).thenReturn(List.of());
    when(workOrders.existsByPreventiveScheduleId(scheduleId)).thenReturn(false);
    when(workOrders.createSystem(any(), any(), any(), any()))
        .thenThrow(new com.syncro.maintenance.application.WorkOrderService.WorkOrderCategoryNotFoundException());

    var view = service.approve(user, scheduleId.toString(), new ApproveCommand("preventive/sig.png", "Leader", "ok"));

    assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PERFORMED);
    assertThat(view.scheduleId()).isEqualTo(scheduleId);
  }

  private ChecklistCommand command() {
    return new ChecklistCommand("all good", List.of(new ItemCommand("lube", "ok", null, null, null)));
  }

  private AuthenticatedUser staffUser() {
    return new AuthenticatedUser(staffId.toString(), "staff@test", ApplicationRole.STAFF_MAINTENANCE);
  }

  private AuthenticatedUser leaderUser() {
    return new AuthenticatedUser(leaderId.toString(), "leader@test", ApplicationRole.MAINTENANCE_LEADER);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}
