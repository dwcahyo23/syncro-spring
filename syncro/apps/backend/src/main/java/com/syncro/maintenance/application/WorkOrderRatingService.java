package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.domain.workorder.RatingType;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.domain.workorder.WorkorderRating;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder &amp; technician ratings (FR-121/FR-124, AD-14, story 10-8). A closed workorder
 * receives performance feedback: the in-scope section leader rates an executing
 * technician and the PRODUCTION_LEADER with plant access rates the workorder. Rating
 * dimensions are configuration data (SUPER_ADMIN-managed), shared by both rating types.
 * Ratings are immutable after submission — a duplicate hits the DB unique constraints
 * and is surfaced as {@code RATING_ALREADY_EXISTS}.
 */
@Service
public class WorkOrderRatingService {

  private final WorkOrderRepository workOrders;
  private final WorkorderRatingRepository ratings;
  private final WorkorderRatingScoreRepository scores;
  private final RatingDimensionRepository dimensions;
  private final MachineRepository machines;
  private final AuthUserRepository users;
  private final PlantScopeService plantScope;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public WorkOrderRatingService(WorkOrderRepository workOrders, WorkorderRatingRepository ratings,
      WorkorderRatingScoreRepository scores, RatingDimensionRepository dimensions, MachineRepository machines,
      AuthUserRepository users, PlantScopeService plantScope, AuditLogWriter auditLog,
      OperationalScopeService scopes, Clock clock) {
    this.workOrders = workOrders;
    this.ratings = ratings;
    this.scores = scores;
    this.dimensions = dimensions;
    this.machines = machines;
    this.users = users;
    this.plantScope = plantScope;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Rating submission
  // -------------------------------------------------------------------------

  /**
   * Technician rating (FR-121): the in-scope section leader rates a technician who
   * executed the workorder. The rated user must exist and belong to the workorder's
   * executor pool (assigned technician OR a user with a repair session on it).
   */
  @Transactional
  public WorkorderRating rateTechnician(AuthenticatedUser user, String workOrderId, UUID ratedUserId,
      Map<String, Integer> requestedScores) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireClosed(entity);
    requireSectionLeaderGate(user, machine);

    users.findById(ratedUserId).orElseThrow(RatedUserNotFoundException::new);
    if (!isExecutorPool(workOrderId, ratedUserId, entity)) {
      throw new UserNotExecutorException();
    }
    if (ratings.findByWorkorderIdAndRatingTypeAndRatedUserId(workOrderId, RatingType.TECHNICIAN, ratedUserId)
        .isPresent()) {
      throw new RatingAlreadyExistsException();
    }
    var validated = validateScores(requestedScores);
    var now = Instant.now(clock);
    var rating = new WorkorderRatingEntity(UUID.randomUUID(), workOrderId, RatingType.TECHNICIAN, ratedUserId,
        UUID.fromString(user.id()), now);
    var saved = ratings.saveAndFlush(rating);
    var savedScores = saveScores(saved.getId(), validated.scores());

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORKORDER_RATING,
        saved.getId(), workOrderId, machine.getPlant().getId(), null,
        ratingValues(saved, savedScores), null));
    return WorkorderRatingView.toDomain(saved, savedScores, validated.dimensions());
  }

  /**
   * Workorder rating (FR-124): the PRODUCTION_LEADER with plant access to the workorder's
   * machine rates the workorder itself — no rated user, bound to the workorder.
   */
  @Transactional
  public WorkorderRating rateWorkorder(AuthenticatedUser user, String workOrderId,
      Map<String, Integer> requestedScores) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireClosed(entity);
    requireProductionLeaderGate(user, machine);

    if (ratings.existsByWorkorderIdAndRatingType(workOrderId, RatingType.WORKORDER)) {
      throw new RatingAlreadyExistsException();
    }
    var validated = validateScores(requestedScores);
    var now = Instant.now(clock);
    var rating = new WorkorderRatingEntity(UUID.randomUUID(), workOrderId, RatingType.WORKORDER, null,
        UUID.fromString(user.id()), now);
    var saved = ratings.saveAndFlush(rating);
    var savedScores = saveScores(saved.getId(), validated.scores());

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORKORDER_RATING,
        saved.getId(), workOrderId, machine.getPlant().getId(), null,
        ratingValues(saved, savedScores), null));
    return WorkorderRatingView.toDomain(saved, savedScores, validated.dimensions());
  }

  /** Lists a workorder's ratings with their per-dimension scores (any authenticated user). */
  @Transactional(readOnly = true)
  public List<WorkorderRating> listRatings(String workOrderId) {
    loadWorkOrder(workOrderId);
    var ratingEntities = ratings.findByWorkorderIdOrderByCreatedAtAsc(workOrderId);
    if (ratingEntities.isEmpty()) {
      return List.of();
    }
    var allDimensions = dimensions.findAll();
    var result = new ArrayList<WorkorderRating>();
    for (var rating : ratingEntities) {
      result.add(WorkorderRatingView.toDomain(rating, scores.findByRatingId(rating.getId()), allDimensions));
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Ratings page read (FR-121/FR-124 list surface)
  // -------------------------------------------------------------------------

  /**
   * CLOSED workorders the user can rate, scope-filtered (SUPER_ADMIN unrestricted;
   * every other user sees workorders whose machine plant OR group is in their derived
   * scope). Mirrors the kanban read posture — one joined query, no N+1. Who can
   * actually rate each workorder (section leader vs production leader) is service-gated
   * at submit time; the page lists the user's rateable surface.
   */
  @Transactional(readOnly = true)
  public List<RateableWorkorder> listRateableClosed(AuthenticatedUser user) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var groupIds = new java.util.HashSet<UUID>();
    groupIds.addAll(scope.machineGroupIds());
    groupIds.addAll(scope.activeTeamIds());

    var rows = workOrders.findClosedForRating(
        unrestricted, scope.plantIds() == null ? List.of() : scope.plantIds(), groupIds);
    var result = new ArrayList<RateableWorkorder>();
    for (var row : rows) {
      var workOrder = row.workOrder();
      result.add(new RateableWorkorder(workOrder.getId(), workOrder.getSource(), workOrder.getStatus(),
          row.category() != null ? row.category().getCode() : null, workOrder.getMachineId(),
          workOrder.getDescription(), workOrder.getAssignedTechnicianId(), workOrder.getCreatedAt(),
          executorPool(workOrder)));
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // Dimension CRUD (SUPER_ADMIN)
  // -------------------------------------------------------------------------

  /** Creates a rating dimension (SUPER_ADMIN only, audit-logged). */
  @Transactional
  public RatingDimensionView createDimension(AuthenticatedUser user, CreateDimensionCommand command) {
    requireSuperAdmin(user);
    var now = Instant.now(clock);
    var code = command.code().trim().toUpperCase();
    if (dimensions.findByCode(code).isPresent()) {
      throw new DuplicateDimensionCodeException();
    }
    var dimension = new RatingDimensionEntity(UUID.randomUUID(), code,
        command.label().trim(), command.sortOrder(), UUID.fromString(user.id()), now);
    var saved = dimensions.saveAndFlush(dimension);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.RATING_DIMENSION,
        saved.getId(), saved.getCode(), null, null, dimensionValues(saved), null));
    return WorkorderRatingView.toDimensionView(saved);
  }

  /** Updates a rating dimension's label/sort_order (SUPER_ADMIN only; code is the identity). */
  @Transactional
  public RatingDimensionView updateDimension(AuthenticatedUser user, String code, UpdateDimensionCommand command) {
    requireSuperAdmin(user);
    var dimension = dimensions.findByCode(code).orElseThrow(DimensionNotFoundException::new);
    dimension.update(command.label().trim(), command.sortOrder(), Instant.now(clock));
    var saved = dimensions.saveAndFlush(dimension);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.RATING_DIMENSION,
        saved.getId(), saved.getCode(), null, null, dimensionValues(saved), null));
    return WorkorderRatingView.toDimensionView(saved);
  }

  /** Deletes a rating dimension (SUPER_ADMIN only; in-use dimensions are rejected). */
  @Transactional
  public void deleteDimension(AuthenticatedUser user, String code) {
    requireSuperAdmin(user);
    var dimension = dimensions.findByCode(code).orElseThrow(DimensionNotFoundException::new);
    if (scores.existsByDimensionId(dimension.getId())) {
      throw new DimensionInUseException();
    }
    dimensions.delete(dimension);

    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.RATING_DIMENSION,
        dimension.getId(), dimension.getCode(), null, dimensionValues(dimension), null, null));
  }

  /** Lists rating dimensions ordered by sort_order (any authenticated user). */
  @Transactional(readOnly = true)
  public List<RatingDimensionView> listDimensions() {
    return dimensions.findAllByOrderBySortOrderAsc().stream()
        .map(WorkorderRatingView::toDimensionView)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Gates
  // -------------------------------------------------------------------------

  private void requireClosed(WorkOrderEntity entity) {
    if (entity.getStatus() != WorkOrderStatus.CLOSED) {
      throw new WorkorderNotClosedException();
    }
  }

  /** FR-121: the rater must be the in-scope section leader of the workorder's machine group (SUPER_ADMIN exempt). */
  private void requireSectionLeaderGate(AuthenticatedUser user, MachineEntity machine) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        // exempt — may rate any closed workorder's technicians
      }
      case SECTION_LEADER -> {
        if (!groupInScope(scopes.derive(user), machine)) {
          throw new RatingForbiddenException();
        }
      }
      default -> throw new RatingForbiddenException();
    }
  }

  /** FR-124: the rater must be PRODUCTION_LEADER with plant access (SUPER_ADMIN exempt). */
  private void requireProductionLeaderGate(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (user.applicationRole() != ApplicationRole.PRODUCTION_LEADER
        || !plantScope.canAccessPlant(user, machine.getPlant().getId())) {
      throw new RatingForbiddenException();
    }
  }

  private void requireSuperAdmin(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new RatingForbiddenException();
    }
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  /** Executor pool: the assigned technician OR any user with a repair session on the workorder (FR-121). */
  private boolean isExecutorPool(String workOrderId, UUID ratedUserId, WorkOrderEntity entity) {
    if (entity.getAssignedTechnicianId() != null && entity.getAssignedTechnicianId().equals(ratedUserId)) {
      return true;
    }
    return workOrders.findSessionTechnicianIds(workOrderId).contains(ratedUserId);
  }

  private List<UUID> executorPool(WorkOrderEntity entity) {
    var pool = new ArrayList<UUID>();
    if (entity.getAssignedTechnicianId() != null) {
      pool.add(entity.getAssignedTechnicianId());
    }
    for (var id : workOrders.findSessionTechnicianIds(entity.getId())) {
      if (!pool.contains(id)) {
        pool.add(id);
      }
    }
    return pool;
  }

  /**
   * Validates the submitted scores: every key must be an existing dimension code, every
   * value an integer 1-5. Missing dimensions are allowed (a rater may score a subset).
   * The saved rating stores exactly the submitted scores.
   */
  private ValidatedScores validateScores(Map<String, Integer> requestedScores) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (requestedScores == null || requestedScores.isEmpty()) {
      fieldErrors.put("scores", "At least one dimension score is required.");
      throw new RatingValidationException(fieldErrors);
    }
    var allDimensions = dimensions.findAll();
    var dimensionByCode = new HashMap<String, RatingDimensionEntity>();
    for (var dimension : allDimensions) {
      dimensionByCode.put(dimension.getCode(), dimension);
    }
    var validated = new LinkedHashMap<UUID, Short>();
    for (var entry : requestedScores.entrySet()) {
      var code = entry.getKey();
      var dimension = dimensionByCode.get(code);
      if (dimension == null) {
        fieldErrors.put("scores." + code, "Unknown dimension.");
        continue;
      }
      var value = entry.getValue();
      if (value == null || value < 1 || value > 5) {
        fieldErrors.put("scores." + code, "Score must be an integer between 1 and 5.");
        continue;
      }
      validated.put(dimension.getId(), value.shortValue());
    }
    if (!fieldErrors.isEmpty()) {
      throw new RatingValidationException(fieldErrors);
    }
    return new ValidatedScores(validated, allDimensions);
  }

  private List<WorkorderRatingScoreEntity> saveScores(UUID ratingId, Map<UUID, Short> validated) {
    var saved = new ArrayList<WorkorderRatingScoreEntity>();
    for (var entry : validated.entrySet()) {
      saved.add(scores.saveAndFlush(new WorkorderRatingScoreEntity(ratingId, entry.getKey(), entry.getValue())));
    }
    return saved;
  }

  /** Validated dimension→score map plus the loaded dimension set (avoids a re-query). */
  private record ValidatedScores(Map<UUID, Short> scores, List<RatingDimensionEntity> dimensions) {
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(RatingWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(RatingMachineNotFoundException::new);
  }

  private Map<String, Object> ratingValues(WorkorderRatingEntity rating, List<WorkorderRatingScoreEntity> savedScores) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", rating.getId());
    values.put("workorderId", rating.getWorkorderId());
    values.put("ratingType", rating.getRatingType().name());
    values.put("ratedUserId", rating.getRatedUserId());
    values.put("raterUserId", rating.getRaterUserId());
    var scoreMap = new LinkedHashMap<String, Object>();
    for (var score : savedScores) {
      scoreMap.put(String.valueOf(score.getDimensionId()), (int) score.getScore());
    }
    values.put("scores", scoreMap);
    return values;
  }

  private Map<String, Object> dimensionValues(RatingDimensionEntity dimension) {
    var values = new HashMap<String, Object>();
    values.put("id", dimension.getId());
    values.put("code", dimension.getCode());
    values.put("label", dimension.getLabel());
    values.put("sortOrder", dimension.getSortOrder());
    return values;
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record RateTechnicianCommand(UUID ratedUserId, Map<String, Integer> scores) {
  }

  public record RateWorkorderCommand(Map<String, Integer> scores) {
  }

  public record CreateDimensionCommand(String code, String label, int sortOrder) {
  }

  public record UpdateDimensionCommand(String label, int sortOrder) {
  }

  public record RatingDimensionView(UUID id, String code, String label, int sortOrder) {
  }

  /** One rateable CLOSED workorder for the ratings page, with its executor pool. */
  public record RateableWorkorder(String id, String source, WorkOrderStatus status, String categoryCode,
      UUID machineId, String description, UUID assignedTechnicianId, Instant createdAt, List<UUID> executorPool) {
  }

  /** Domain/API view builder: entity + scores + dimension set → WorkorderRating. */
  private static final class WorkorderRatingView {
    private static WorkorderRating toDomain(WorkorderRatingEntity rating,
        List<WorkorderRatingScoreEntity> scoreEntities, List<RatingDimensionEntity> allDimensions) {
      return WorkOrderMapper.toDomain(rating, scoreEntities, allDimensions);
    }

    private static RatingDimensionView toDimensionView(RatingDimensionEntity dimension) {
      return new RatingDimensionView(dimension.getId(), dimension.getCode(), dimension.getLabel(),
          dimension.getSortOrder());
    }
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class RatingForbiddenException extends RuntimeException {
  }

  public static class RatingWorkOrderNotFoundException extends RuntimeException {
  }

  public static class RatingMachineNotFoundException extends RuntimeException {
  }

  public static class RatedUserNotFoundException extends RuntimeException {
  }

  public static class UserNotExecutorException extends RuntimeException {
  }

  public static class WorkorderNotClosedException extends RuntimeException {
  }

  public static class RatingAlreadyExistsException extends RuntimeException {
  }

  public static class RatingValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public RatingValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class DimensionNotFoundException extends RuntimeException {
  }

  public static class DimensionInUseException extends RuntimeException {
  }

  public static class DuplicateDimensionCodeException extends RuntimeException {
  }
}