package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingAlreadyExistsException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingCriterionInUseException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingCriterionNotFoundException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingForbiddenException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingNotClosedException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingNotCompletedException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingValidationException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingWorkLogNotFoundException;
import com.syncro.maintenance.application.WorkLogRatingService.WorkLogRatingWorkOrderNotFoundException;
import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkLogEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingCriterionEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingCriterionRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class WorkLogRatingServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final String WORKORDER_ID = "WO-260900001";

  @Mock
  private WorkLogRepository workLogs;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkLogRatingRepository ratings;
  @Mock
  private WorkLogRatingCriterionRepository criteria;
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
  private final UUID sectionLeaderId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();
  private final UUID workLogId = UUID.randomUUID();
  private final UUID criterionId1 = UUID.randomUUID();
  private final UUID criterionId2 = UUID.randomUUID();

  private WorkLogRatingService service;
  private MachineEntity machine;
  private WorkLogRatingCriterionEntity criterionSpeed;
  private WorkLogRatingCriterionEntity criterionQuality;

  @BeforeEach
  void setUp() {
    service = new WorkLogRatingService(workLogs, workOrders, ratings, criteria,
        machines, scopes, auditLog, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    criterionSpeed = new WorkLogRatingCriterionEntity(criterionId1, "Speed", null, 1, 5, null, true, 1,
        UUID.randomUUID(), NOW, NOW);
    criterionQuality = new WorkLogRatingCriterionEntity(criterionId2, "Work Quality", null, 1, 5, null, true, 2,
        UUID.randomUUID(), NOW, NOW);
    lenient().when(criteria.findAll()).thenReturn(List.of(criterionSpeed, criterionQuality));
  }

  // -------------------------------------------------------------------------
  // Rate work log
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.4-SVC-001 P0 in-scope section leader rates a completed work log on a CLOSED workorder")
  void rateWorkLogOk() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    when(ratings.existsByWorkLogIdAndCriterionId(workLogId, criterionId1)).thenReturn(false);
    when(ratings.existsByWorkLogIdAndCriterionId(workLogId, criterionId2)).thenReturn(false);
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    var scores = new LinkedHashMap<UUID, Integer>();
    scores.put(criterionId1, 4);
    scores.put(criterionId2, 5);
    var result = service.rateWorkLog(user, WORKORDER_ID, workLogId, scores);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).score()).isEqualTo(4);
    assertThat(result.get(1).score()).isEqualTo(5);
    verify(auditLog, org.mockito.Mockito.times(2)).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORK_LOG_RATING));
  }

  @Test
  @DisplayName("17.4-SVC-002 P0 rateWorkLog on a non-CLOSED workorder is 400 RATING_WORKORDER_NOT_CLOSED")
  void rateWorkLogNotClosed() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingNotClosedException.class);
  }

  @Test
  @DisplayName("17.4-SVC-003 P0 rateWorkLog by a non-section-leader is 403 FORBIDDEN")
  void rateWorkLogForbidden() {
    var user = technicianUser();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingForbiddenException.class);
  }

  @Test
  @DisplayName("17.4-SVC-004 P0 rateWorkLog on an incomplete work log is 400 RATING_WORKLOG_NOT_COMPLETED")
  void rateWorkLogNotCompleted() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var incompleteLog = new WorkLogEntity(workLogId, UUID.randomUUID(), WORKORDER_ID, technicianId,
        NOW.minusSeconds(3600), null, null, "activity", null, null, NOW, NOW);
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(incompleteLog));

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingNotCompletedException.class);
  }

  @Test
  @DisplayName("17.4-SVC-005 P0 duplicate work log rating is 409 RATING_ALREADY_EXISTS")
  void rateWorkLogDuplicate() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(ratings.existsByWorkLogIdAndCriterionId(workLogId, criterionId1)).thenReturn(true);

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingAlreadyExistsException.class);
  }

  @Test
  @DisplayName("17.4-SVC-006 P0 rateWorkLog with unknown criterion is 400 VALIDATION_ERROR")
  void rateWorkLogUnknownCriterion() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var bogusId = UUID.randomUUID();

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(bogusId, 3)))
        .isInstanceOf(WorkLogRatingValidationException.class);
  }

  @Test
  @DisplayName("17.4-SVC-007 P0 rateWorkLog with out-of-range score is 400 VALIDATION_ERROR")
  void rateWorkLogBadScore() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 6)))
        .isInstanceOf(WorkLogRatingValidationException.class);
  }

  @Test
  @DisplayName("17.4-SVC-008 P0 SUPER_ADMIN can rate any work log on a closed workorder")
  void superAdminRateWorkLog() {
    var user = superAdmin();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    when(ratings.existsByWorkLogIdAndCriterionId(workLogId, criterionId1)).thenReturn(false);
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    var result = service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 5));

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().score()).isEqualTo(5);
  }

  @Test
  @DisplayName("17.4-SVC-009 P0 rateWorkLog on unknown workorder is 404 WORKORDER_NOT_FOUND")
  void rateWorkLogWorkOrderNotFound() {
    var user = sectionLeader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("17.4-SVC-010 P0 rateWorkLog on unknown work log is 404 WORKLOG_NOT_FOUND")
  void rateWorkLogWorkLogNotFound() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(workLogs.findById(workLogId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingWorkLogNotFoundException.class);
  }

  @Test
  @DisplayName("17.4-SVC-011 P0 rateWorkLog with work log not on this workorder is 404")
  void rateWorkLogWrongWorkOrder() {
    var user = sectionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var wrongLog = new WorkLogEntity(workLogId, UUID.randomUUID(), "WO-OTHER", technicianId,
        NOW.minusSeconds(3600), NOW, WorkLogStoppedReason.COMPLETED, "activity", null, null, NOW, NOW);
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(wrongLog));

    assertThatThrownBy(() -> service.rateWorkLog(user, WORKORDER_ID, workLogId, Map.of(criterionId1, 3)))
        .isInstanceOf(WorkLogRatingWorkLogNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // List ratings
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.4-SVC-012 P0 listRatings returns ratings with criterion names")
  void listRatingsOk() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.CLOSED)));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    var rating = new WorkLogRatingEntity(UUID.randomUUID(), workLogId, criterionId1, 4, sectionLeaderId, NOW, null);
    when(ratings.findByWorkLogId(workLogId)).thenReturn(List.of(rating));

    var result = service.listRatings(WORKORDER_ID, workLogId);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().criterionName()).isEqualTo("Speed");
    assertThat(result.getFirst().score()).isEqualTo(4);
  }

  @Test
  @DisplayName("17.4-SVC-013 P0 listRatings on a work log with no ratings returns empty")
  void listRatingsEmpty() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(WorkOrderStatus.CLOSED)));
    when(workLogs.findById(workLogId)).thenReturn(Optional.of(completedWorkLog()));
    when(ratings.findByWorkLogId(workLogId)).thenReturn(List.of());

    var result = service.listRatings(WORKORDER_ID, workLogId);

    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Criterion CRUD
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.4-SVC-014 P0 SUPER_ADMIN creates a criterion")
  void createCriterionOk() {
    var user = superAdmin();
    when(criteria.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.createCriterion(user,
        new WorkLogRatingService.CreateCriterionCommand("Speed", null, 1, 5, null, true, 1));

    assertThat(result.name()).isEqualTo("Speed");
    assertThat(result.minScore()).isEqualTo(1);
    assertThat(result.maxScore()).isEqualTo(5);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORK_LOG_RATING));
  }

  @Test
  @DisplayName("17.4-SVC-015 P0 createCriterion by non-SUPER_ADMIN is 403 FORBIDDEN")
  void createCriterionForbidden() {
    var user = sectionLeader();

    assertThatThrownBy(() -> service.createCriterion(user,
        new WorkLogRatingService.CreateCriterionCommand("Speed", null, 1, 5, null, true, 1)))
        .isInstanceOf(WorkLogRatingForbiddenException.class);
  }

  @Test
  @DisplayName("17.4-SVC-016 P0 createCriterion with invalid score range is 400 VALIDATION_ERROR")
  void createCriterionBadRange() {
    var user = superAdmin();

    assertThatThrownBy(() -> service.createCriterion(user,
        new WorkLogRatingService.CreateCriterionCommand("Bad", null, 5, 5, null, true, 1)))
        .isInstanceOf(WorkLogRatingValidationException.class);
  }

  @Test
  @DisplayName("17.4-SVC-017 P0 SUPER_ADMIN updates a criterion")
  void updateCriterionOk() {
    var user = superAdmin();
    var existing = new WorkLogRatingCriterionEntity(criterionId1, "Speed", null, 1, 5, null, true, 1,
        UUID.randomUUID(), NOW, NOW);
    when(criteria.findById(criterionId1)).thenReturn(Optional.of(existing));
    when(criteria.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.updateCriterion(user, criterionId1,
        new WorkLogRatingService.UpdateCriterionCommand("Quickness", null, 1, 5, null, true, 2));

    assertThat(result.name()).isEqualTo("Quickness");
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.UPDATE
        && r.entityType() == AuditEntityType.WORK_LOG_RATING));
  }

  @Test
  @DisplayName("17.4-SVC-018 P0 updateCriterion on unknown criterion is 404")
  void updateCriterionNotFound() {
    var user = superAdmin();
    when(criteria.findById(criterionId1)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateCriterion(user, criterionId1,
        new WorkLogRatingService.UpdateCriterionCommand("Nope", null, 1, 5, null, true, 1)))
        .isInstanceOf(WorkLogRatingCriterionNotFoundException.class);
  }

  @Test
  @DisplayName("17.4-SVC-019 P0 SUPER_ADMIN deletes a criterion that is not in use")
  void deleteCriterionOk() {
    var user = superAdmin();
    var existing = new WorkLogRatingCriterionEntity(criterionId1, "Speed", null, 1, 5, null, true, 1,
        UUID.randomUUID(), NOW, NOW);
    when(criteria.findById(criterionId1)).thenReturn(Optional.of(existing));
    when(ratings.existsByCriterionId(criterionId1)).thenReturn(false);

    service.deleteCriterion(user, criterionId1);

    verify(criteria).delete(existing);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.DELETE
        && r.entityType() == AuditEntityType.WORK_LOG_RATING));
  }

  @Test
  @DisplayName("17.4-SVC-020 P0 deleteCriterion when criterion has ratings is 400 CRITERION_IN_USE")
  void deleteCriterionInUse() {
    var user = superAdmin();
    var existing = new WorkLogRatingCriterionEntity(criterionId1, "Speed", null, 1, 5, null, true, 1,
        UUID.randomUUID(), NOW, NOW);
    when(criteria.findById(criterionId1)).thenReturn(Optional.of(existing));
    when(ratings.existsByCriterionId(criterionId1)).thenReturn(true);

    assertThatThrownBy(() -> service.deleteCriterion(user, criterionId1))
        .isInstanceOf(WorkLogRatingCriterionInUseException.class);
    verify(criteria, never()).delete(any());
  }

  @Test
  @DisplayName("17.4-SVC-021 P0 listCriteria returns ordered criteria")
  void listCriteriaOk() {
    when(criteria.findAllByOrderBySortOrderAsc()).thenReturn(List.of(criterionSpeed, criterionQuality));

    var result = service.listCriteria();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).name()).isEqualTo("Speed");
    assertThat(result.get(1).name()).isEqualTo("Work Quality");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity entity(WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, UUID.randomUUID(),
        machineId, "desc", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
  }

  private WorkLogEntity completedWorkLog() {
    return new WorkLogEntity(workLogId, UUID.randomUUID(), WORKORDER_ID, technicianId,
        NOW.minusSeconds(3600), NOW, WorkLogStoppedReason.COMPLETED, "activity", null, null, NOW, NOW);
  }

  private AuthenticatedUser sectionLeader() {
    return new AuthenticatedUser(sectionLeaderId.toString(), "sl@test", ApplicationRole.SECTION_LEADER);
  }

  private AuthenticatedUser technicianUser() {
    return new AuthenticatedUser(technicianId.toString(), "tech@test", ApplicationRole.TECHNICIAN);
  }

  private AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@test", ApplicationRole.SUPER_ADMIN);
  }

  private static MachineEntity machineWithPlant(UUID plantId, UUID groupId, UUID machineId) {
    var plant = new com.syncro.auth.infrastructure.PlantEntity(plantId, "P01", "Plant", NOW, NOW);
    var group = new com.syncro.masterdata.infrastructure.MachineGroupEntity(groupId, plant, "Group", NOW, NOW);
    return new MachineEntity(machineId, plant, group, "M-001", "Machine", MachineStatus.ACTIVE, null, null, null,
        List.of(), NOW, NOW);
  }
}