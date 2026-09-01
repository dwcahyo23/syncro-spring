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
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingAlreadyExistsException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingForbiddenException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingValidationException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatedUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.UserNotExecutorException;
import com.syncro.maintenance.application.WorkOrderRatingService.WorkorderNotClosedException;
import com.syncro.maintenance.domain.workorder.RatingType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.RatingDimensionEntity;
import com.syncro.maintenance.infrastructure.db.RatingDimensionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRatingRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingScoreEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingScoreRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class WorkOrderRatingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
  private static final String WORKORDER_ID = "WO-240900001";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkorderRatingRepository ratings;
  @Mock
  private WorkorderRatingScoreRepository scores;
  @Mock
  private RatingDimensionRepository dimensions;
  @Mock
  private MachineRepository machines;
  @Mock
  private AuthUserRepository users;
  @Mock
  private PlantScopeService plantScope;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private OperationalScopeService scopes;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID sectionLeaderId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();
  private final UUID productionLeaderId = UUID.randomUUID();

  private WorkOrderRatingService service;
  private MachineEntity machine;
  private RatingDimensionEntity dimSpeed;
  private RatingDimensionEntity dimQuality;

  @BeforeEach
  void setUp() {
    service = new WorkOrderRatingService(workOrders, ratings, scores, dimensions, machines, users,
        plantScope, auditLog, scopes, clock);
    machine = machineWithPlant(plantId, groupId, machineId);
    lenient().when(machines.findByIdWithPlantAndGroup(machineId)).thenReturn(Optional.of(machine));
    dimSpeed = new RatingDimensionEntity(UUID.randomUUID(), "SPEED", "Speed", 1, UUID.randomUUID(), NOW);
    dimQuality = new RatingDimensionEntity(UUID.randomUUID(), "WORK_QUALITY", "Work Quality", 2, UUID.randomUUID(), NOW);
    lenient().when(dimensions.findAll()).thenReturn(List.of(dimSpeed, dimQuality));
  }

  // -------------------------------------------------------------------------
  // Technician rating
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.8-SVC-001 P0 in-scope section leader rates a technician on a CLOSED workorder")
  void rateTechnicianOk() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(users.findById(technicianId)).thenReturn(Optional.of(new AuthUserEntity(technicianId, "tech@test", "hash",
        ApplicationRole.TECHNICIAN, true, NOW, NOW)));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());
    when(ratings.findByWorkorderIdAndRatingTypeAndRatedUserId(WORKORDER_ID, RatingType.TECHNICIAN, technicianId))
        .thenReturn(Optional.empty());
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scores.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    var result = service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 4, "WORK_QUALITY", 5));

    assertThat(result.ratingType()).isEqualTo(RatingType.TECHNICIAN);
    assertThat(result.ratedUserId()).isEqualTo(technicianId);
    assertThat(result.scores()).containsEntry("SPEED", 4).containsEntry("WORK_QUALITY", 5);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORKORDER_RATING));
  }

  @Test
  @DisplayName("10.8-SVC-002 P0 rateTechnician on a non-CLOSED workorder is 400 RATING_WORKORDER_NOT_CLOSED")
  void rateTechnicianNotClosed() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.IN_PROGRESS);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 3)))
        .isInstanceOf(WorkorderNotClosedException.class);
  }

  @Test
  @DisplayName("10.8-SVC-003 P0 rateTechnician by a non-section-leader is 403 FORBIDDEN")
  void rateTechnicianForbidden() {
    var user = technicianUser();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 3)))
        .isInstanceOf(RatingForbiddenException.class);
  }

  @Test
  @DisplayName("10.8-SVC-004 P0 rateTechnician with unknown ratedUserId is 404 USER_NOT_FOUND")
  void rateTechnicianUserNotFound() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(users.findById(technicianId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 3)))
        .isInstanceOf(RatedUserNotFoundException.class);
  }

  @Test
  @DisplayName("10.8-SVC-005 P0 rateTechnician with a user not in the executor pool is 400 RATING_USER_NOT_EXECUTOR")
  void rateTechnicianNotExecutor() {
    var user = sectionLeader();
    var entity = entity(null, WorkOrderStatus.CLOSED); // no assigned technician
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(users.findById(technicianId)).thenReturn(Optional.of(new AuthUserEntity(technicianId, "tech@test", "hash",
        ApplicationRole.TECHNICIAN, true, NOW, NOW)));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 3)))
        .isInstanceOf(UserNotExecutorException.class);
  }

  @Test
  @DisplayName("10.8-SVC-006 P0 duplicate technician rating is 409 RATING_ALREADY_EXISTS")
  void rateTechnicianDuplicate() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(users.findById(technicianId)).thenReturn(Optional.of(new AuthUserEntity(technicianId, "tech@test", "hash",
        ApplicationRole.TECHNICIAN, true, NOW, NOW)));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());
    when(ratings.findByWorkorderIdAndRatingTypeAndRatedUserId(WORKORDER_ID, RatingType.TECHNICIAN, technicianId))
        .thenReturn(Optional.of(new WorkorderRatingEntity(UUID.randomUUID(), WORKORDER_ID, RatingType.TECHNICIAN,
            technicianId, sectionLeaderId, NOW)));

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 3)))
        .isInstanceOf(RatingAlreadyExistsException.class);
  }

  @Test
  @DisplayName("10.8-SVC-007 P0 rateTechnician with unknown dimension is 400 VALIDATION_ERROR")
  void rateTechnicianUnknownDimension() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(users.findById(technicianId)).thenReturn(Optional.of(new AuthUserEntity(technicianId, "tech@test", "hash",
        ApplicationRole.TECHNICIAN, true, NOW, NOW)));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("BOGUS", 3)))
        .isInstanceOf(RatingValidationException.class);
  }

  @Test
  @DisplayName("10.8-SVC-008 P0 rateTechnician with an out-of-range score is 400 VALIDATION_ERROR")
  void rateTechnicianBadScore() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(users.findById(technicianId)).thenReturn(Optional.of(new AuthUserEntity(technicianId, "tech@test", "hash",
        ApplicationRole.TECHNICIAN, true, NOW, NOW)));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 6)))
        .isInstanceOf(RatingValidationException.class);
  }

  // -------------------------------------------------------------------------
  // Workorder rating
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.8-SVC-009 P0 PRODUCTION_LEADER rates a CLOSED workorder")
  void rateWorkorderOk() {
    var user = productionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkorderIdAndRatingType(WORKORDER_ID, RatingType.WORKORDER)).thenReturn(false);
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scores.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.rateWorkorder(user, WORKORDER_ID, Map.of("SPEED", 3, "WORK_QUALITY", 4));

    assertThat(result.ratingType()).isEqualTo(RatingType.WORKORDER);
    assertThat(result.ratedUserId()).isNull();
    assertThat(result.scores()).containsEntry("SPEED", 3);
    verify(auditLog).record(eq(user), argThat(r -> r.action() == AuditAction.CREATE
        && r.entityType() == AuditEntityType.WORKORDER_RATING));
  }

  @Test
  @DisplayName("10.8-SVC-010 P0 rateWorkorder by a non-PRODUCTION_LEADER is 403 FORBIDDEN")
  void rateWorkorderForbidden() {
    var user = sectionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.rateWorkorder(user, WORKORDER_ID, Map.of("SPEED", 3)))
        .isInstanceOf(RatingForbiddenException.class);
  }

  @Test
  @DisplayName("10.8-SVC-011 P0 duplicate workorder rating is 409 RATING_ALREADY_EXISTS")
  void rateWorkorderDuplicate() {
    var user = productionLeader();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(plantScope.canAccessPlant(user, plantId)).thenReturn(true);
    when(ratings.existsByWorkorderIdAndRatingType(WORKORDER_ID, RatingType.WORKORDER)).thenReturn(true);

    assertThatThrownBy(() -> service.rateWorkorder(user, WORKORDER_ID, Map.of("SPEED", 3)))
        .isInstanceOf(RatingAlreadyExistsException.class);
  }

  @Test
  @DisplayName("10.8-SVC-012 P0 SUPER_ADMIN can rate a technician on any closed workorder")
  void superAdminRateTechnician() {
    var user = superAdmin();
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity));
    when(users.findById(technicianId)).thenReturn(Optional.of(new AuthUserEntity(technicianId, "tech@test", "hash",
        ApplicationRole.TECHNICIAN, true, NOW, NOW)));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());
    when(ratings.findByWorkorderIdAndRatingTypeAndRatedUserId(WORKORDER_ID, RatingType.TECHNICIAN, technicianId))
        .thenReturn(Optional.empty());
    when(ratings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scores.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);

    var result = service.rateTechnician(user, WORKORDER_ID, technicianId, Map.of("SPEED", 5));

    assertThat(result.ratingType()).isEqualTo(RatingType.TECHNICIAN);
    assertThat(result.scores()).containsEntry("SPEED", 5);
  }

  // -------------------------------------------------------------------------
  // List ratings
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.8-SVC-013 P0 listRatings returns ratings with scores")
  void listRatingsOk() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(technicianId, WorkOrderStatus.CLOSED)));
    var ratingId = UUID.randomUUID();
    var ratingEntity = new WorkorderRatingEntity(ratingId, WORKORDER_ID, RatingType.TECHNICIAN, technicianId,
        sectionLeaderId, NOW);
    when(ratings.findByWorkorderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of(ratingEntity));
    var scoreEntity = new WorkorderRatingScoreEntity(ratingId, dimSpeed.getId(), (short) 4);
    when(scores.findByRatingId(ratingId)).thenReturn(List.of(scoreEntity));

    var result = service.listRatings(WORKORDER_ID);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().scores()).containsEntry("SPEED", 4);
  }

  @Test
  @DisplayName("10.8-SVC-014 P0 listRatings on a workorder with no ratings returns empty")
  void listRatingsEmpty() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(entity(technicianId, WorkOrderStatus.CLOSED)));
    when(ratings.findByWorkorderIdOrderByCreatedAtAsc(WORKORDER_ID)).thenReturn(List.of());

    var result = service.listRatings(WORKORDER_ID);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("10.8-SVC-015 P0 listRatings on unknown workorder is 404")
  void listRatingsWorkOrderNotFound() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.listRatings(WORKORDER_ID))
        .isInstanceOf(RatingWorkOrderNotFoundException.class);
  }

  // -------------------------------------------------------------------------
  // List rateable closed workorders
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("10.8-SVC-016 P0 listRateableClosed returns scope-filtered CLOSED workorders")
  void listRateableClosedOk() {
    var user = new AuthenticatedUser(sectionLeaderId.toString(), "sl@test", ApplicationRole.SECTION_LEADER);
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    var row = new WorkOrderRatingRow(entity, null);
    when(workOrders.findClosedForRating(eq(false), any(), any())).thenReturn(List.of(row));
    when(workOrders.findSessionTechnicianIds(WORKORDER_ID)).thenReturn(List.of());

    var result = service.listRateableClosed(user);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().id()).isEqualTo(WORKORDER_ID);
  }

  @Test
  @DisplayName("10.8-SVC-017 P0 listRateableClosed for SUPER_ADMIN is unrestricted")
  void listRateableClosedSuperAdmin() {
    var user = superAdmin();
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var entity = entity(technicianId, WorkOrderStatus.CLOSED);
    var row = new WorkOrderRatingRow(entity, null);
    when(workOrders.findClosedForRating(eq(true), any(), any())).thenReturn(List.of(row));

    var result = service.listRateableClosed(user);

    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("10.8-SVC-018 P0 listRateableClosed with no scope returns empty")
  void listRateableClosedEmpty() {
    var user = new AuthenticatedUser(UUID.randomUUID().toString(), "auditor@test", ApplicationRole.AUDITOR);
    var scope = new OperationalScope(Set.of(), Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(workOrders.findClosedForRating(eq(false), any(), any())).thenReturn(List.of());

    var result = service.listRateableClosed(user);

    assertThat(result).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity entity(UUID assignedTechnician, WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, UUID.randomUUID(),
        machineId, "desc", 0, null, assignedTechnician, UUID.randomUUID(), NOW, NOW);
  }

  private AuthenticatedUser sectionLeader() {
    return new AuthenticatedUser(sectionLeaderId.toString(), "sl@test", ApplicationRole.SECTION_LEADER);
  }

  private AuthenticatedUser productionLeader() {
    return new AuthenticatedUser(productionLeaderId.toString(), "pl@test", ApplicationRole.PRODUCTION_LEADER);
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