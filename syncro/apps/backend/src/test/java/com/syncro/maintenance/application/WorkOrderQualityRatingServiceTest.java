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
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingAlreadyExistsException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingForbiddenException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingNotClosedException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingNotFoundException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingUserNotExecutorException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingValidationException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.QualityRatingWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderQualityRatingService.SubmitQualityRatingCommand;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.domain.workorder.WorkRatingStatus;
import com.syncro.maintenance.infrastructure.db.WorkAssignmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderQualityRatingEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderQualityRatingRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderQualityRatingScoreEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderQualityRatingScoreRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderQualityRatingTechnicianEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderQualityRatingTechnicianRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRatingCriterionEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRatingCriterionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class WorkOrderQualityRatingServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final String WORKORDER_ID = "WO-260900001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderQualityRatingRepository ratings;
  @Mock
  private WorkOrderQualityRatingTechnicianRepository technicianPivots;
  @Mock
  private WorkOrderQualityRatingScoreRepository scoreRows;
  @Mock
  private WorkOrderRatingCriterionRepository criteria;
  @Mock
  private MachineRepository machines;
  @Mock
  private PlantScopeService plantScope;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private WorkAssignmentRepository assignments;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID raterId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();
  private final UUID criterionId1 = UUID.randomUUID();
  private final UUID criterionId2 = UUID.randomUUID();

  private WorkOrderQualityRatingService service;
  private MachineEntity machine;
  private WorkOrderRatingCriterionEntity criterionSpeed;
  private WorkOrderRatingCriterionEntity criterionQuality;

  @BeforeEach
  void setUp() {
    service = new WorkOrderQualityRatingService(workOrders, ratings, technicianPivots, scoreRows,
        criteria, machines, plantScope, auditLog, assignments, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    lenient().when(assignments.findByWorkOrderIdOrderByAssignedAtAsc(WORKORDER_ID)).thenReturn(List.of());
    lenient().when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());
    criterionSpeed = new WorkOrderRatingCriterionEntity(criterionId1, "Speed", null, 1, 5, null, true, 1,
        UUID.randomUUID(), NOW, NOW);
    criterionQuality = new WorkOrderRatingCriterionEntity(criterionId2, "Work Quality", null, 1, 5, null, true, 2,
        UUID.randomUUID(), NOW, NOW);
    lenient().when(criteria.findAll()).thenReturn(List.of(criterionSpeed, criterionQuality));
  }

  // -------------------------------------------------------------------------
  // Submit quality rating
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.5-SVC-001 P0 PRODUCTION_LEADER submits a quality rating on a CLOSED workorder")
  void submitQualityRatingOk() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(technicianPivots.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scoreRows.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4, criterionId2, 5), 5, 4, 3));

    assertThat(result.status()).isEqualTo(WorkRatingStatus.SUBMITTED);
    assertThat(result.workOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(result.cleanlinessScore()).isEqualTo(5);
    assertThat(result.tidinessScore()).isEqualTo(4);
    assertThat(result.speedScore()).isEqualTo(3);
    assertThat(result.submittedBy()).isEqualTo(raterId);
    assertThat(result.technicianIds()).containsExactly(technicianId);
    assertThat(result.scores()).hasSize(2);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORK_ORDER_QUALITY_RATING));
  }

  @Test
  @DisplayName("17.5-SVC-002 P0 submit on a non-CLOSED workorder is rejected (RATING_WORKORDER_NOT_CLOSED)")
  void submitQualityRatingNotClosed() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingNotClosedException.class);
  }

  @Test
  @DisplayName("17.5-SVC-003 P0 duplicate quality rating is 409 RATING_ALREADY_EXISTS")
  void submitQualityRatingDuplicate() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(true);

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingAlreadyExistsException.class);
  }

  @Test
  @DisplayName("17.5-SVC-004 P0 submit by a non-PRODUCTION_LEADER is 403 FORBIDDEN")
  void submitQualityRatingForbidden() {
    var user = technicianUser();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingForbiddenException.class);
  }

  @Test
  @DisplayName("17.5-SVC-005 P0 PRODUCTION_LEADER without plant access is 403 FORBIDDEN")
  void submitQualityRatingForbiddenNoPlantAccess() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(false);

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingForbiddenException.class);
  }

  @Test
  @DisplayName("17.5-SVC-006 P0 SUPER_ADMIN can submit a quality rating without plant scope")
  void superAdminSubmitQualityRating() {
    var user = superAdmin();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(technicianPivots.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scoreRows.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3));

    assertThat(result.status()).isEqualTo(WorkRatingStatus.SUBMITTED);
    assertThat(result.technicianIds()).containsExactly(technicianId);
  }

  @Test
  @DisplayName("17.5-SVC-007 P0 submit with an unknown criterion is 400 VALIDATION_ERROR")
  void submitQualityRatingUnknownCriterion() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    var bogusId = UUID.randomUUID();

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(bogusId, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingValidationException.class);
  }

  @Test
  @DisplayName("17.5-SVC-008 P0 submit with an out-of-range score is 400 VALIDATION_ERROR")
  void submitQualityRatingOutOfRangeScore() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 6), 5, 4, 3)))
        .isInstanceOf(QualityRatingValidationException.class);
  }

  @Test
  @DisplayName("17.5-SVC-009 P0 submit with no technicians is rejected (RATING_VALIDATION_ERROR)")
  void submitQualityRatingNoTechnicians() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingValidationException.class);
  }

  @Test
  @DisplayName("17.5-SVC-010 P0 submit with a technician outside the executor pool is RATING_USER_NOT_EXECUTOR")
  void submitQualityRatingUnknownTechnician() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    var outsiderId = UUID.randomUUID();

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(outsiderId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingUserNotExecutorException.class);
  }

  @Test
  @DisplayName("17.5-SVC-011 P0 submit on an unknown workorder is 404 WORKORDER_NOT_FOUND")
  void submitQualityRatingWorkOrderNotFound() {
    var user = productionLeader();
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("17.5-SVC-012 P0 submit on a workorder with an unknown machine is 404 MACHINE_NOT_FOUND")
  void submitQualityRatingMachineNotFound() {
    var user = productionLeader();
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submit(user, WORKORDER_ID, command(List.of(technicianId),
        Map.of(criterionId1, 4), 5, 4, 3)))
        .isInstanceOf(QualityRatingMachineNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Get / expiry
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.5-SVC-013 P0 get transitions a past-due PENDING rating to EXPIRED and persists it")
  void getExpiresPendingRating() {
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var ratingId = UUID.randomUUID();
    var pastDue = new WorkOrderQualityRatingEntity(ratingId, WORKORDER_ID, WorkRatingStatus.PENDING,
        NOW.minusSeconds(3600), null, null, null, null, null, null, NOW.minusSeconds(7200), NOW);
    when(ratings.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.of(pastDue));
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scoreRows.findByQualityRatingId(ratingId)).thenReturn(List.of());
    when(technicianPivots.findByQualityRatingId(ratingId)).thenReturn(List.of());

    var result = service.get(WORKORDER_ID);

    assertThat(result.status()).isEqualTo(WorkRatingStatus.EXPIRED);
    verify(ratings).saveAndFlush(argThat(r -> r.getStatus() == WorkRatingStatus.EXPIRED));
  }

  @Test
  @DisplayName("17.5-SVC-014 P0 get returns a SUBMITTED rating with criterion names and technicians")
  void getRatingOk() {
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var ratingId = UUID.randomUUID();
    var rating = new WorkOrderQualityRatingEntity(ratingId, WORKORDER_ID, WorkRatingStatus.SUBMITTED,
        null, NOW, raterId, 5, 4, 3, null, NOW, NOW);
    when(ratings.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.of(rating));
    when(scoreRows.findByQualityRatingId(ratingId))
        .thenReturn(List.of(new WorkOrderQualityRatingScoreEntity(UUID.randomUUID(), ratingId, criterionId1, 4)));
    when(technicianPivots.findByQualityRatingId(ratingId))
        .thenReturn(List.of(new WorkOrderQualityRatingTechnicianEntity(UUID.randomUUID(), ratingId, null, technicianId)));

    var result = service.get(WORKORDER_ID);

    assertThat(result.status()).isEqualTo(WorkRatingStatus.SUBMITTED);
    assertThat(result.cleanlinessScore()).isEqualTo(5);
    assertThat(result.scores()).hasSize(1);
    assertThat(result.scores().getFirst().criterionName()).isEqualTo("Speed");
    assertThat(result.scores().getFirst().score()).isEqualTo(4);
    assertThat(result.technicianIds()).containsExactly(technicianId);
  }

  @Test
  @DisplayName("17.5-SVC-015 P0 get on a workorder without a rating is 404 RATING_NOT_FOUND")
  void getRatingNotFound() {
    var entity = entity(WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(ratings.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(WORKORDER_ID))
        .isInstanceOf(QualityRatingNotFoundException.class);
  }

  @Test
  @DisplayName("17.5-SVC-016 P0 get on an unknown workorder is 404 WORKORDER_NOT_FOUND")
  void getWorkOrderNotFound() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(WORKORDER_ID))
        .isInstanceOf(QualityRatingWorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // Exists / list criteria
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("17.5-SVC-017 P0 exists returns true when a quality rating row is present")
  void existsRating() {
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(true);

    assertThat(service.exists(WORKORDER_ID)).isTrue();
    when(ratings.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    assertThat(service.exists(WORKORDER_ID)).isFalse();
  }

  @Test
  @DisplayName("17.5-SVC-018 P0 listCriteria returns ordered criteria")
  void listCriteriaOk() {
    when(criteria.findAllByOrderBySortOrderAsc()).thenReturn(List.of(criterionSpeed, criterionQuality));

    var result = service.listCriteria();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).name()).isEqualTo("Speed");
    assertThat(result.get(0).minScore()).isEqualTo(1);
    assertThat(result.get(0).maxScore()).isEqualTo(5);
    assertThat(result.get(1).name()).isEqualTo("Work Quality");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private SubmitQualityRatingCommand command(List<UUID> technicianIds, Map<UUID, Integer> scores,
      Integer cleanliness, Integer tidiness, Integer speed) {
    return new SubmitQualityRatingCommand(technicianIds, scores, cleanliness, tidiness, speed);
  }

  private WorkOrderEntity entity(WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, UUID.randomUUID(),
        machineId, "desc", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
  }

  private AuthenticatedUser productionLeader() {
    return new AuthenticatedUser(raterId.toString(), "pl@test", ApplicationRole.PRODUCTION_LEADER);
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
