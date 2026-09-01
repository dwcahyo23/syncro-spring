package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
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
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
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
 * Workorder quality ratings (FR-124, blueprint C4-C6, story 17-5). After a maintenance
 * workorder is CLOSED, the PRODUCTION_LEADER of the affected line rates workorder quality
 * with per-dimension scores and per-technician scores via the pivot. One rating per
 * workorder ({@code uq_work_order_quality_ratings_work_order}); immutable after submission.
 * Unsubmitted ratings become EXPIRED when {@code due_at} passes.
 */
@Service
public class WorkOrderQualityRatingService {

  private final WorkOrderRepository workOrders;
  private final WorkOrderQualityRatingRepository ratings;
  private final WorkOrderQualityRatingTechnicianRepository technicianPivots;
  private final WorkOrderQualityRatingScoreRepository scoreRows;
  private final WorkOrderRatingCriterionRepository criteria;
  private final MachineRepository machines;
  private final PlantScopeService plantScope;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final WorkAssignmentRepository assignments;
  private final Clock clock;

  public WorkOrderQualityRatingService(WorkOrderRepository workOrders,
      WorkOrderQualityRatingRepository ratings,
      WorkOrderQualityRatingTechnicianRepository technicianPivots,
      WorkOrderQualityRatingScoreRepository scoreRows,
      WorkOrderRatingCriterionRepository criteria,
      MachineRepository machines, PlantScopeService plantScope,
      OperationalScopeService scopes, AuditLogWriter auditLog,
      WorkAssignmentRepository assignments, Clock clock) {
    this.workOrders = workOrders;
    this.ratings = ratings;
    this.technicianPivots = technicianPivots;
    this.scoreRows = scoreRows;
    this.criteria = criteria;
    this.machines = machines;
    this.plantScope = plantScope;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.assignments = assignments;
    this.clock = clock;
  }

  /**
   * Submits a quality rating for a CLOSED workorder (FR-124). Creates a PENDING rating
   * row if none exists, then submits it with per-criterion scores and per-technician
   * scores. One rating per workorder; immutable after submission.
   */
  @Transactional
  public QualityRatingView submit(AuthenticatedUser user, String workOrderId,
      SubmitQualityRatingCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireClosed(entity);
    requireProductionLeaderGate(user, machine);

    if (ratings.existsByWorkOrderId(workOrderId)) {
      throw new QualityRatingAlreadyExistsException();
    }
    var now = Instant.now(clock);
    var raterId = UUID.fromString(user.id());

    var validated = validateScores(command.scores());
    var technicianIds = validateTechnicians(workOrderId, command.technicianIds());

    var rating = new WorkOrderQualityRatingEntity(UUID.randomUUID(), workOrderId,
        WorkRatingStatus.PENDING, null, null, null, null, null, null, null, now, now);
    rating.submit(command.cleanlinessScore(), command.tidinessScore(), command.speedScore(),
        raterId, now, now);
    var saved = ratings.saveAndFlush(rating);

    var savedTechs = new ArrayList<WorkOrderQualityRatingTechnicianEntity>();
    for (var techId : technicianIds) {
      var pivot = new WorkOrderQualityRatingTechnicianEntity(UUID.randomUUID(),
          saved.getId(), null, techId);
      savedTechs.add(technicianPivots.saveAndFlush(pivot));
    }

    var savedScores = new ArrayList<WorkOrderQualityRatingScoreEntity>();
    for (var entry : validated.scores().entrySet()) {
      var score = new WorkOrderQualityRatingScoreEntity(UUID.randomUUID(),
          saved.getId(), entry.getKey(), entry.getValue());
      savedScores.add(scoreRows.saveAndFlush(score));
    }

    auditLog.record(user, new AuditRecord(AuditAction.CREATE,
        AuditEntityType.WORK_ORDER_QUALITY_RATING,
        saved.getId(), workOrderId, machine.getPlant().getId(), null,
        ratingValues(saved, savedScores, savedTechs), null));
    return toView(saved, savedScores, savedTechs, validated.criteria());
  }

  /**
   * Gets the current quality rating for a workorder (any authenticated user).
   * If the rating is PENDING and due_at has passed, transitions it to EXPIRED
   * and persists the change.
   */
  @Transactional
  public QualityRatingView get(String workOrderId) {
    loadWorkOrder(workOrderId);
    var rating = ratings.findByWorkOrderId(workOrderId)
        .orElseThrow(QualityRatingNotFoundException::new);
    expireIfPastDue(rating);
    var scores = scoreRows.findByQualityRatingId(rating.getId());
    var techs = technicianPivots.findByQualityRatingId(rating.getId());
    var allCriteria = criteria.findAll();
    return toView(rating, scores, techs, allCriteria);
  }

  /** Checks whether a quality rating exists for the workorder (read-only). */
  @Transactional(readOnly = true)
  public boolean exists(String workOrderId) {
    return ratings.existsByWorkOrderId(workOrderId);
  }

  /** Lists active quality rating criteria ordered by sort_order (any authenticated user). */
  @Transactional(readOnly = true)
  public List<WorkOrderRatingCriterionView> listCriteria() {
    return criteria.findAllByOrderBySortOrderAsc().stream()
        .map(this::toCriterionView)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Gates
  // -------------------------------------------------------------------------

  private void requireClosed(WorkOrderEntity entity) {
    if (entity.getStatus() != WorkOrderStatus.CLOSED) {
      throw new QualityRatingNotClosedException();
    }
  }

  /** FR-124: the rater must be PRODUCTION_LEADER with plant access (SUPER_ADMIN exempt). */
  private void requireProductionLeaderGate(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (user.applicationRole() != ApplicationRole.PRODUCTION_LEADER
        || !plantScope.canAccessPlant(user, machine.getPlant().getId())) {
      throw new QualityRatingForbiddenException();
    }
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  /**
   * Validates per-criterion scores against the criterion's min/max range.
   * Every key must be an existing criterion; every value must be within range.
   */
  private ValidatedScores validateScores(Map<UUID, Integer> requestedScores) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (requestedScores == null || requestedScores.isEmpty()) {
      fieldErrors.put("scores", "At least one criterion score is required.");
      throw new QualityRatingValidationException(fieldErrors);
    }
    var allCriteria = criteria.findAll();
    var criterionById = new HashMap<UUID, WorkOrderRatingCriterionEntity>();
    for (var c : allCriteria) {
      criterionById.put(c.getId(), c);
    }
    var validated = new LinkedHashMap<UUID, Integer>();
    for (var entry : requestedScores.entrySet()) {
      var criterion = criterionById.get(entry.getKey());
      if (criterion == null) {
        fieldErrors.put("scores." + entry.getKey(), "Unknown criterion.");
        continue;
      }
      var value = entry.getValue();
      if (value == null || value < criterion.getMinScore() || value > criterion.getMaxScore()) {
        fieldErrors.put("scores." + entry.getKey(),
            "Score must be between " + criterion.getMinScore() + " and " + criterion.getMaxScore() + ".");
        continue;
      }
      validated.put(criterion.getId(), value);
    }
    if (!fieldErrors.isEmpty()) {
      throw new QualityRatingValidationException(fieldErrors);
    }
    return new ValidatedScores(validated, allCriteria);
  }

  /** Validated criterion-id → score map plus the loaded criterion set (avoids a re-query). */
  private record ValidatedScores(Map<UUID, Integer> scores, List<WorkOrderRatingCriterionEntity> criteria) {
  }

  /**
   * Validates that every technician id is in the workorder's executor pool
   * (assigned technician + active assignments + repair-session technicians).
   */
  private List<UUID> validateTechnicians(String workOrderId, List<UUID> technicianIds) {
    if (technicianIds == null || technicianIds.isEmpty()) {
      throw new QualityRatingValidationException(
          Map.of("technicianIds", "At least one technician is required."));
    }
    var pool = executorPool(workOrderId);
    var deduped = new ArrayList<UUID>();
    for (var techId : technicianIds) {
      if (!pool.contains(techId)) {
        throw new QualityRatingUserNotExecutorException();
      }
      if (!deduped.contains(techId)) {
        deduped.add(techId);
      }
    }
    return deduped;
  }

  /** Executor pool: assigned technician + active assignment technicians + repair-session technicians. */
  private List<UUID> executorPool(String workOrderId) {
    var pool = new ArrayList<UUID>();
    var entity = workOrders.findById(workOrderId).orElse(null);
    if (entity == null) return pool;
    if (entity.getAssignedTechnicianId() != null) {
      pool.add(entity.getAssignedTechnicianId());
    }
    for (var a : assignments.findByWorkOrderIdOrderByAssignedAtAsc(workOrderId)) {
      if (a.isActive() && !pool.contains(a.getTechnicianId())) {
        pool.add(a.getTechnicianId());
      }
    }
    for (var id : workOrders.findSessionTechnicianIds(workOrderId)) {
      if (!pool.contains(id)) {
        pool.add(id);
      }
    }
    return pool;
  }

  // -------------------------------------------------------------------------
  // Expiry
  // -------------------------------------------------------------------------

  private void expireIfPastDue(WorkOrderQualityRatingEntity rating) {
    if (rating.getStatus() == WorkRatingStatus.PENDING
        && rating.getDueAt() != null
        && rating.getDueAt().isBefore(Instant.now(clock))) {
      rating.setStatus(WorkRatingStatus.EXPIRED);
      rating.setUpdatedAt(Instant.now(clock));
      ratings.saveAndFlush(rating);
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(QualityRatingWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(QualityRatingMachineNotFoundException::new);
  }

  private Map<String, Object> ratingValues(WorkOrderQualityRatingEntity rating,
      List<WorkOrderQualityRatingScoreEntity> scores,
      List<WorkOrderQualityRatingTechnicianEntity> techs) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", rating.getId());
    values.put("workOrderId", rating.getWorkOrderId());
    values.put("status", rating.getStatus().name());
    values.put("cleanlinessScore", rating.getCleanlinessScore());
    values.put("tidinessScore", rating.getTidinessScore());
    values.put("speedScore", rating.getSpeedScore());
    values.put("submittedBy", rating.getSubmittedBy());
    values.put("submittedAt", rating.getSubmittedAt() != null ? rating.getSubmittedAt().toString() : null);
    values.put("technicians", techs.stream().map(t -> t.getTechnicianId().toString()).toList());
    var scoreMap = new LinkedHashMap<UUID, Integer>();
    for (var s : scores) {
      scoreMap.put(s.getCriterionId(), s.getScore());
    }
    values.put("scores", scoreMap);
    return values;
  }

  private QualityRatingView toView(WorkOrderQualityRatingEntity rating,
      List<WorkOrderQualityRatingScoreEntity> scoreEntities,
      List<WorkOrderQualityRatingTechnicianEntity> techEntities,
      List<WorkOrderRatingCriterionEntity> allCriteria) {
    var nameById = new HashMap<UUID, String>();
    for (var c : allCriteria) {
      nameById.put(c.getId(), c.getName());
    }
    var scoreViews = new ArrayList<ScoreView>();
    for (var s : scoreEntities) {
      scoreViews.add(new ScoreView(s.getCriterionId(), nameById.get(s.getCriterionId()), s.getScore()));
    }
    var techIds = techEntities.stream().map(t -> t.getTechnicianId()).toList();
    return new QualityRatingView(rating.getId(), rating.getWorkOrderId(), rating.getStatus(),
        rating.getCleanlinessScore(), rating.getTidinessScore(), rating.getSpeedScore(),
        rating.getDueAt(), rating.getSubmittedAt(), rating.getSubmittedBy(),
        rating.getRemarks(), scoreViews, techIds);
  }

  private WorkOrderRatingCriterionView toCriterionView(WorkOrderRatingCriterionEntity criterion) {
    return new WorkOrderRatingCriterionView(criterion.getId(), criterion.getName(),
        criterion.getDescription(), criterion.getMinScore(), criterion.getMaxScore(),
        criterion.getPlantId(), criterion.isActive(), criterion.getSortOrder());
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record SubmitQualityRatingCommand(
      List<UUID> technicianIds,
      Map<UUID, Integer> scores,
      Integer cleanlinessScore,
      Integer tidinessScore,
      Integer speedScore) {
  }

  public record QualityRatingView(UUID id, String workOrderId, WorkRatingStatus status,
      Integer cleanlinessScore, Integer tidinessScore, Integer speedScore,
      Instant dueAt, Instant submittedAt, UUID submittedBy, String remarks,
      List<ScoreView> scores, List<UUID> technicianIds) {
  }

  public record ScoreView(UUID criterionId, String criterionName, int score) {
  }

  public record WorkOrderRatingCriterionView(UUID id, String name, String description,
      int minScore, int maxScore, UUID plantId, boolean active, int sortOrder) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class QualityRatingForbiddenException extends RuntimeException {}
  public static class QualityRatingWorkOrderNotFoundException extends RuntimeException {}
  public static class QualityRatingMachineNotFoundException extends RuntimeException {}
  public static class QualityRatingNotClosedException extends RuntimeException {}
  public static class QualityRatingAlreadyExistsException extends RuntimeException {}
  public static class QualityRatingNotFoundException extends RuntimeException {}
  public static class QualityRatingUserNotExecutorException extends RuntimeException {}

  public static class QualityRatingValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;
    public QualityRatingValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
  }
}