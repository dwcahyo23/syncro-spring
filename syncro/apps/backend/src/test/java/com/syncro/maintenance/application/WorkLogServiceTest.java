package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkLogService.CreateWorkLogCommand;
import com.syncro.maintenance.application.WorkLogService.UpdateWorkLogCommand;
import com.syncro.maintenance.application.WorkLogService.WorkLogAssignmentInactiveException;
import com.syncro.maintenance.application.WorkLogService.WorkLogAssignmentNotFoundException;
import com.syncro.maintenance.application.WorkLogService.WorkLogBackdateBeforeWorkorderException;
import com.syncro.maintenance.application.WorkLogService.WorkLogNotFoundException;
import com.syncro.maintenance.application.WorkLogService.WorkLogValidationException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import com.syncro.maintenance.infrastructure.db.RepairSessionRepository;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.domain.workorder.WorkAssignmentParentType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkLogServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final String WORKORDER_ID = "WO-260900001";

  @Mock
  private WorkLogRepository workLogs;
  @Mock
  private WorkAssignmentRepository workAssignments;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private RepairSessionRepository repairSessions;
  @Mock
  private MachineRepository machines;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID assignmentId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();
  private final UUID actorId = UUID.randomUUID();

  private WorkLogService service;
  private MachineEntity machine;
  private WorkOrderEntity workOrder;
  private WorkAssignmentEntity activeAssignment;

  @BeforeEach
  void setUp() {
    service = new WorkLogService(workLogs, workAssignments, workOrders, repairSessions,
        machines, scopes, auditLog, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    workOrder = new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS,
        UUID.randomUUID(), machineId, "desc", 0, null, technicianId, actorId,
        NOW.minus(java.time.Duration.ofHours(1)), NOW);
    activeAssignment = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        WORKORDER_ID, technicianId, actorId, NOW, null, null, true, NOW, NOW);
  }

  // -------------------------------------------------------------------------
  // Create — I/O Matrix
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.2-SVC-001 P0 create on active assignment by executor succeeds with audit and MTTR recompute")
  void createOk() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));
    when(workLogs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(workOrders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW.minus(java.time.Duration.ofMinutes(10)), NOW,
        WorkLogStoppedReason.COMPLETED, "Replaced bearing", "Done", null));

    assertThat(view.workOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(view.technicianId()).isEqualTo(technicianId);
    assertThat(view.activityNote()).isEqualTo("Replaced bearing");
    assertThat(view.stoppedReason()).isEqualTo(WorkLogStoppedReason.COMPLETED);
    assertThat(view.endTime()).isEqualTo(NOW);
    verify(workLogs).saveAndFlush(any(WorkLogEntity.class));
    // MTTR recomputed because endTime is set.
    verify(workOrders).saveAndFlush(workOrder);
    verify(auditLog).record(eq(user), argThat(r ->
        r.action() == AuditAction.CREATE && r.entityType() == AuditEntityType.WORK_LOG));
  }

  @Test
  @DisplayName("17.2-SVC-002 P0 backdate before workorder created_at is rejected")
  void backdateBeforeWorkorderRejected() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, workOrder.getCreatedAt().minus(java.time.Duration.ofMinutes(1)), null,
        null, "note", null, null)))
        .isInstanceOf(WorkLogBackdateBeforeWorkorderException.class);
  }

  @Test
  @DisplayName("17.2-SVC-003 P0 create against a non-active assignment is rejected")
  void createInactiveAssignment() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    var inactive = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        WORKORDER_ID, technicianId, actorId, NOW, NOW, actorId, false, NOW, NOW);
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(inactive));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "note", null, null)))
        .isInstanceOf(WorkLogAssignmentInactiveException.class);
  }

  @Test
  @DisplayName("17.2-SVC-004 P0 create against a wrong workorder assignment is rejected")
  void createWrongWorkorderAssignment() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    var wrong = new WorkAssignmentEntity(assignmentId, WorkAssignmentParentType.CORRECTIVE_WO,
        "WO-OTHER", technicianId, actorId, NOW, null, null, true, NOW, NOW);
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(wrong));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "note", null, null)))
        .isInstanceOf(WorkLogAssignmentNotFoundException.class);
  }

  @Test
  @DisplayName("17.2-SVC-005 P0 create on EXTERNAL workorder is forbidden")
  void createExternalWorkorder() {
    var user = executor(technicianId);
    var external = new WorkOrderEntity("EXT-001", "EXTERNAL", null, WorkOrderStatus.IN_PROGRESS,
        UUID.randomUUID(), machineId, "desc", 0, null, null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate("EXT-001")).thenReturn(Optional.of(external));

    assertThatThrownBy(() -> service.create(user, "EXT-001", new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "note", null, null)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.2-SVC-006 P0 non-executor non-leader is forbidden to create")
  void createNonExecutorForbidden() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "auditor@syncro.dev", ApplicationRole.AUDITOR);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "note", null, null)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.2-SVC-007 P0 create without end_time (open log) does not recompute MTTR")
  void createOpenLogDoesNotRecomputeMttr() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));
    when(workLogs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "Started work", null, null));

    assertThat(view.endTime()).isNull();
    assertThat(view.stoppedReason()).isNull();
    // MTTR not recomputed — no endTime means no completion.
    verify(workOrders, org.mockito.Mockito.never()).saveAndFlush(any(WorkOrderEntity.class));
  }

  @Test
  @DisplayName("17.2-SVC-008 P0 create by in-scope SECTION_LEADER succeeds")
  void createBySectionLeader() {
    var user = new AuthenticatedUser(actorId.toString(), "leader@syncro.dev", ApplicationRole.SECTION_LEADER);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of()));
    when(workLogs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "Leader note", null, null));

    assertThat(view.workOrderId()).isEqualTo(WORKORDER_ID);
    verify(workLogs).saveAndFlush(any(WorkLogEntity.class));
  }

  @Test
  @DisplayName("17.2-SVC-009 P0 create by in-scope MAINTENANCE_LEADER succeeds")
  void createByMaintenanceLeader() {
    var user = new AuthenticatedUser(actorId.toString(), "mlead@syncro.dev", ApplicationRole.MAINTENANCE_LEADER);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workLogs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "ML note", null, null));

    assertThat(view.workOrderId()).isEqualTo(WORKORDER_ID);
    verify(workLogs).saveAndFlush(any(WorkLogEntity.class));
  }

  @Test
  @DisplayName("17.2-SVC-010 P0 workorder not found throws WORKORDER_NOT_FOUND")
  void createWorkOrderNotFound() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate("WO-NONE")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user, "WO-NONE", new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "note", null, null)))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("17.2-SVC-018 P0 end_time not after start_time is rejected as VALIDATION_ERROR")
  void endBeforeStartRejected() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, NOW, WorkLogStoppedReason.COMPLETED, "note", null, null)))
        .isInstanceOf(WorkLogValidationException.class);
    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, NOW.minus(java.time.Duration.ofMinutes(1)), WorkLogStoppedReason.COMPLETED,
        "note", null, null)))
        .isInstanceOf(WorkLogValidationException.class);
    verify(workLogs, org.mockito.Mockito.never()).saveAndFlush(any(WorkLogEntity.class));
  }

  @Test
  @DisplayName("17.2-SVC-019 P0 blank activity note is rejected as VALIDATION_ERROR")
  void blankActivityNoteRejected() {
    var user = executor(technicianId);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workAssignments.findById(assignmentId)).thenReturn(Optional.of(activeAssignment));

    assertThatThrownBy(() -> service.create(user, WORKORDER_ID, new CreateWorkLogCommand(
        assignmentId, NOW, null, null, "   ", null, null)))
        .isInstanceOf(WorkLogValidationException.class);
    verify(workLogs, org.mockito.Mockito.never()).saveAndFlush(any(WorkLogEntity.class));
  }

  @Test
  @DisplayName("17.2-SVC-020 P0 update with end_time not after the log's startTime is rejected")
  void updateEndBeforeStartRejected() {
    var user = executor(technicianId);
    var log = new WorkLogEntity(UUID.randomUUID(), assignmentId, WORKORDER_ID, technicianId,
        NOW, null, null, "Started", null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workLogs.findById(log.getId())).thenReturn(Optional.of(log));

    assertThatThrownBy(() -> service.update(user, WORKORDER_ID, log.getId(), new UpdateWorkLogCommand(
        NOW, WorkLogStoppedReason.COMPLETED, "nope", null)))
        .isInstanceOf(WorkLogValidationException.class);
    verify(workLogs, org.mockito.Mockito.never()).saveAndFlush(any(WorkLogEntity.class));
  }

  // -------------------------------------------------------------------------
  // Update
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.2-SVC-011 P0 update own log by executor succeeds")
  void updateOwnLogOk() {
    var user = executor(technicianId);
    var log = new WorkLogEntity(UUID.randomUUID(), assignmentId, WORKORDER_ID, technicianId,
        NOW.minus(java.time.Duration.ofMinutes(30)), null, null, "Started", null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workLogs.findById(log.getId())).thenReturn(Optional.of(log));
    when(workLogs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service.update(user, WORKORDER_ID, log.getId(), new UpdateWorkLogCommand(
        NOW, WorkLogStoppedReason.COMPLETED, "All done", null));

    assertThat(view.endTime()).isEqualTo(NOW);
    assertThat(view.stoppedReason()).isEqualTo(WorkLogStoppedReason.COMPLETED);
    assertThat(view.completionNote()).isEqualTo("All done");
    verify(auditLog).record(eq(user), argThat(r ->
        r.action() == AuditAction.UPDATE && r.entityType() == AuditEntityType.WORK_LOG));
  }

  @Test
  @DisplayName("17.2-SVC-012 P0 update someone else's log by non-leader is forbidden")
  void updateOtherLogForbidden() {
    var otherTechId = UUID.randomUUID();
    var user = new AuthenticatedUser(otherTechId.toString(), "other@syncro.dev", ApplicationRole.TECHNICIAN);
    var log = new WorkLogEntity(UUID.randomUUID(), assignmentId, WORKORDER_ID, technicianId,
        NOW, null, null, "Started", null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workLogs.findById(log.getId())).thenReturn(Optional.of(log));

    assertThatThrownBy(() -> service.update(user, WORKORDER_ID, log.getId(), new UpdateWorkLogCommand(
        NOW, WorkLogStoppedReason.COMPLETED, "nope", null)))
        .isInstanceOf(WorkorderForbiddenException.class);
  }

  @Test
  @DisplayName("17.2-SVC-013 P0 update unknown log throws WORKLOG_NOT_FOUND")
  void updateLogNotFound() {
    var user = executor(technicianId);
    var logId = UUID.randomUUID();
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workLogs.findById(logId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(user, WORKORDER_ID, logId, new UpdateWorkLogCommand(
        NOW, WorkLogStoppedReason.COMPLETED, "nope", null)))
        .isInstanceOf(WorkLogNotFoundException.class);
  }

  @Test
  @DisplayName("17.2-SVC-014 P0 update log from wrong workorder throws WORKLOG_NOT_FOUND")
  void updateLogWrongWorkOrder() {
    var user = executor(technicianId);
    var log = new WorkLogEntity(UUID.randomUUID(), assignmentId, "WO-OTHER", technicianId,
        NOW, null, null, "Started", null, null, NOW, NOW);
    when(workOrders.findByIdForUpdate(WORKORDER_ID)).thenReturn(Optional.of(workOrder));
    when(workLogs.findById(log.getId())).thenReturn(Optional.of(log));

    assertThatThrownBy(() -> service.update(user, WORKORDER_ID, log.getId(), new UpdateWorkLogCommand(
        NOW, WorkLogStoppedReason.COMPLETED, "nope", null)))
        .isInstanceOf(WorkLogNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // List
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.2-SVC-015 P0 list returns logs ordered by start_time")
  void listOk() {
    var log1 = new WorkLogEntity(UUID.randomUUID(), assignmentId, WORKORDER_ID, technicianId,
        NOW.minus(java.time.Duration.ofMinutes(20)), null, null, "First", null, null, NOW, NOW);
    var log2 = new WorkLogEntity(UUID.randomUUID(), assignmentId, WORKORDER_ID, technicianId,
        NOW.minus(java.time.Duration.ofMinutes(10)), NOW, WorkLogStoppedReason.COMPLETED, "Second", "Done", null, NOW, NOW);
    when(workOrders.existsById(WORKORDER_ID)).thenReturn(true);
    when(workLogs.findByWorkOrderIdOrderByStartTimeAsc(WORKORDER_ID)).thenReturn(List.of(log1, log2));

    var views = service.list(WORKORDER_ID);

    assertThat(views).hasSize(2);
    assertThat(views.get(0).activityNote()).isEqualTo("First");
    assertThat(views.get(1).activityNote()).isEqualTo("Second");
  }

  @Test
  @DisplayName("17.2-SVC-016 P0 list on unknown workorder throws WORKORDER_NOT_FOUND")
  void listWorkOrderNotFound() {
    when(workOrders.existsById("WO-NONE")).thenReturn(false);

    assertThatThrownBy(() -> service.list("WO-NONE"))
        .isInstanceOf(WorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("17.2-SVC-017 P0 list returns empty list when no work logs exist")
  void listEmpty() {
    when(workOrders.existsById(WORKORDER_ID)).thenReturn(true);
    when(workLogs.findByWorkOrderIdOrderByStartTimeAsc(WORKORDER_ID)).thenReturn(List.of());

    var views = service.list(WORKORDER_ID);

    assertThat(views).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private AuthenticatedUser executor(UUID id) {
    return new AuthenticatedUser(id.toString(), "tech@syncro.dev", ApplicationRole.TECHNICIAN);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null, List.of(), NOW, NOW);
  }
}