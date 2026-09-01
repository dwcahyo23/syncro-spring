package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkLogEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingCriterionEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingCriterionRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingEntity;
import com.syncro.maintenance.infrastructure.db.WorkLogRatingRepository;
import com.syncro.maintenance.infrastructure.db.WorkLogRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
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
 * Work-log ratings (FR-121, blueprint C2, story 17-4). After a workorder is CLOSED, the
 * in-scope section leader rates a technician's completed work log (end_time set) 1-5 per
 * criterion. The rated technician is always derived from the work log's
 * {@code technician_id} — never from the request. Ratings are immutable after submission:
 * the {@code uq_work_log_ratings_log_criterion} unique constraint backstops duplicates and
 * is surfaced as {@code RATING_ALREADY_EXISTS}. Rating criteria are configuration data
 * (AD-14) managed by SUPER_ADMIN, following the {@link WorkOrderRatingService} dimension
 * pattern (create/update/list/delete with an in-use guard).
 */
@Service
public class WorkLogRatingService {

  private final WorkLogRepository workLogs;
  private final WorkOrderRepository workOrders;
  private final WorkLogRatingRepository ratings;
  private final WorkLogRatingCriterionRepository criteria;
  private final MachineRepository machines;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkLogRatingService(WorkLogRepository workLogs, WorkOrderRepository workOrders,
      WorkLogRatingRepository ratings, WorkLogRatingCriterionRepository criteria,
      MachineRepository machines, OperationalScopeService scopes,
      AuditLogWriter auditLog, Clock clock) {
    this.workLogs = workLogs;
    this.workOrders = workOrders;
    this.ratings = ratings;
    this.criteria = criteria;
    this.machines = machines;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Rating submission
  // -------------------------------------------------------------------------

  /**
   * Rates a completed work log (FR-121, AD-18): the in-scope section leader scores the
   * work log's technician per criterion. The workorder must be CLOSED and the work log
   * completed (end_time set). Scores are validated against each criterion's
   * min_score/max_score. One {@code work_log_ratings} row is persisted per criterion and
   * audit-logged; the unique (work_log_id, criterion_id) constraint backstops duplicates.
   */
  @Transactional
  public List<WorkLogRatingView> rateWorkLog(AuthenticatedUser user, String workOrderId, UUID workLogId,
      Map<UUID, Integer> requestedScores) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireClosed(entity);
    requireSectionLeaderGate(user, machine);

    var workLog = loadWorkLog(workOrderId, workLogId);
    requireCompleted(workLog);

    // Duplicate check first (parity with WorkOrderRatingService.rateTechnician): a
    // re-scored criterion is rejected before score validation.
    if (requestedScores != null) {
      for (var criterionId : requestedScores.keySet()) {
        if (ratings.existsByWorkLogIdAndCriterionId(workLogId, criterionId)) {
          throw new WorkLogRatingAlreadyExistsException();
        }
      }
    }
    var validated = validateScores(requestedScores);

    var now = Instant.now(clock);
    var raterId = UUID.fromString(user.id());
    var saved = new ArrayList<WorkLogRatingEntity>();
    for (var entry : validated.scores().entrySet()) {
      var rating = new WorkLogRatingEntity(UUID.randomUUID(), workLogId, entry.getKey(), entry.getValue(),
          raterId, now, null);
      var persisted = ratings.saveAndFlush(rating);
      saved.add(persisted);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_LOG_RATING,
          persisted.getId(), workOrderId, machine.getPlant().getId(), null,
          ratingValues(persisted), null));
    }
    return toViews(workLog, saved, validated.criteria());
  }

  /** Lists a work log's ratings with their criterion names (any authenticated user). */
  @Transactional(readOnly = true)
  public List<WorkLogRatingView> listRatings(String workOrderId, UUID workLogId) {
    loadWorkOrder(workOrderId);
    var workLog = loadWorkLog(workOrderId, workLogId);
    var entities = ratings.findByWorkLogId(workLogId);
    if (entities.isEmpty()) {
      return List.of();
    }
    return toViews(workLog, entities, criteria.findAll());
  }

  // -------------------------------------------------------------------------
  // Criterion CRUD (SUPER_ADMIN)
  // -------------------------------------------------------------------------

  /**
   * Creates a work-log rating criterion (SUPER_ADMIN only, audit-logged). The score range
   * must satisfy {@code min_score < max_score} (DB CHECK backstop) and both bounds must be
   * positive.
   */
  @Transactional
  public WorkLogRatingCriterionView createCriterion(AuthenticatedUser user, CreateCriterionCommand command) {
    requireSuperAdmin(user);
    requireScoreRange(command.minScore(), command.maxScore());
    var now = Instant.now(clock);
    var criterion = new WorkLogRatingCriterionEntity(UUID.randomUUID(), command.name().trim(),
        command.description(), command.minScore(), command.maxScore(), command.plantId(),
        command.active(), command.sortOrder(), UUID.fromString(user.id()), now, now);
    var saved = criteria.saveAndFlush(criterion);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_LOG_RATING,
        saved.getId(), saved.getName(), saved.getPlantId(), null, criterionValues(saved), null));
    return toCriterionView(saved);
  }

  /**
   * Updates a work-log rating criterion's mutable fields (SUPER_ADMIN only). The entity is
   * rebuilt in place (same id, preserved created_by/created_at) — the 15-2 entity is
   * intentionally left without mutation methods (schema/entity parity with 15-2).
   */
  @Transactional
  public WorkLogRatingCriterionView updateCriterion(AuthenticatedUser user, UUID criterionId,
      UpdateCriterionCommand command) {
    requireSuperAdmin(user);
    requireScoreRange(command.minScore(), command.maxScore());
    var existing = criteria.findById(criterionId)
        .orElseThrow(WorkLogRatingCriterionNotFoundException::new);
    var now = Instant.now(clock);
    var updated = new WorkLogRatingCriterionEntity(existing.getId(), command.name().trim(),
        command.description(), command.minScore(), command.maxScore(), command.plantId(),
        command.active(), command.sortOrder(), existing.getCreatedBy(), existing.getCreatedAt(), now);
    var saved = criteria.saveAndFlush(updated);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_LOG_RATING,
        saved.getId(), saved.getName(), saved.getPlantId(),
        criterionValues(existing), criterionValues(saved), null));
    return toCriterionView(saved);
  }

  /** Deletes a work-log rating criterion (SUPER_ADMIN only; in-use criteria are rejected). */
  @Transactional
  public void deleteCriterion(AuthenticatedUser user, UUID criterionId) {
    requireSuperAdmin(user);
    var criterion = criteria.findById(criterionId)
        .orElseThrow(WorkLogRatingCriterionNotFoundException::new);
    if (ratings.existsByCriterionId(criterionId)) {
      throw new WorkLogRatingCriterionInUseException();
    }
    // The criterion_category pivot rows cascade on delete (V1 ON DELETE CASCADE).
    criteria.delete(criterion);

    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.WORK_LOG_RATING,
        criterion.getId(), criterion.getName(), criterion.getPlantId(),
        criterionValues(criterion), null, null));
  }

  /** Lists work-log rating criteria ordered by sort_order (any authenticated user). */
  @Transactional(readOnly = true)
  public List<WorkLogRatingCriterionView> listCriteria() {
    return criteria.findAllByOrderBySortOrderAsc().stream()
        .map(this::toCriterionView)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Gates & validation
  // -------------------------------------------------------------------------

  private void requireClosed(WorkOrderEntity entity) {
    if (entity.getStatus() != WorkOrderStatus.CLOSED) {
      throw new WorkLogRatingNotClosedException();
    }
  }

  /** FR-121: the rater must be the in-scope section leader of the workorder's machine group (SUPER_ADMIN exempt). */
  private void requireSectionLeaderGate(AuthenticatedUser user, MachineEntity machine) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        // exempt — may rate any closed workorder's work logs
      }
      case SECTION_LEADER -> {
        if (!groupInScope(scopes.derive(user), machine)) {
          throw new WorkLogRatingForbiddenException();
        }
      }
      default -> throw new WorkLogRatingForbiddenException();
    }
  }

  private void requireSuperAdmin(AuthenticatedUser user) {
    if (user.applicationRole() != com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN) {
      throw new WorkLogRatingForbiddenException();
    }
  }

  private void requireCompleted(WorkLogEntity workLog) {
    if (workLog.getEndTime() == null) {
      throw new WorkLogRatingNotCompletedException();
    }
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  private void requireScoreRange(int minScore, int maxScore) {
    if (minScore < 1 || maxScore < 1 || minScore >= maxScore) {
      throw new WorkLogRatingValidationException(Map.of(
          "minScore", "minScore must be positive and strictly less than maxScore.",
          "maxScore", "minScore must be positive and strictly less than maxScore."));
    }
  }

  /**
   * Validates the submitted scores: every key must be an existing criterion id and every
   * value an integer within that criterion's min_score/max_score. Missing criteria are
   * allowed (a rater may score a subset); the persisted rows store exactly the submitted
   * scores.
   */
  private ValidatedScores validateScores(Map<UUID, Integer> requestedScores) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (requestedScores == null || requestedScores.isEmpty()) {
      fieldErrors.put("scores", "At least one criterion score is required.");
      throw new WorkLogRatingValidationException(fieldErrors);
    }
    var allCriteria = criteria.findAll();
    var criterionById = new HashMap<UUID, WorkLogRatingCriterionEntity>();
    for (var criterion : allCriteria) {
      criterionById.put(criterion.getId(), criterion);
    }
    var validated = new LinkedHashMap<UUID, Integer>();
    for (var entry : requestedScores.entrySet()) {
      var criterionId = entry.getKey();
      var criterion = criterionById.get(criterionId);
      if (criterion == null) {
        fieldErrors.put("scores." + criterionId, "Unknown criterion.");
        continue;
      }
      var value = entry.getValue();
      if (value == null || value < criterion.getMinScore() || value > criterion.getMaxScore()) {
        fieldErrors.put("scores." + criterionId, "Score must be an integer between "
            + criterion.getMinScore() + " and " + criterion.getMaxScore() + ".");
        continue;
      }
      validated.put(criterionId, value);
    }
    if (!fieldErrors.isEmpty()) {
      throw new WorkLogRatingValidationException(fieldErrors);
    }
    return new ValidatedScores(validated, allCriteria);
  }

  /** Validated criterion-id → score map plus the loaded criterion set (avoids a re-query). */
  private record ValidatedScores(Map<UUID, Integer> scores, List<WorkLogRatingCriterionEntity> criteria) {
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(WorkLogRatingWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(WorkLogRatingMachineNotFoundException::new);
  }

  private WorkLogEntity loadWorkLog(String workOrderId, UUID workLogId) {
    var workLog = workLogs.findById(workLogId).orElseThrow(WorkLogRatingWorkLogNotFoundException::new);
    if (!workLog.getWorkOrderId().equals(workOrderId)) {
      throw new WorkLogRatingWorkLogNotFoundException();
    }
    return workLog;
  }

  private Map<String, Object> ratingValues(WorkLogRatingEntity rating) {
    var values = new HashMap<String, Object>();
    values.put("id", rating.getId());
    values.put("workLogId", rating.getWorkLogId());
    values.put("criterionId", rating.getCriterionId());
    values.put("score", rating.getScore());
    values.put("ratedBy", rating.getRatedBy());
    values.put("ratedAt", rating.getRatedAt().toString());
    values.put("remarks", rating.getRemarks());
    return values;
  }

  private Map<String, Object> criterionValues(WorkLogRatingCriterionEntity criterion) {
    var values = new HashMap<String, Object>();
    values.put("id", criterion.getId());
    values.put("name", criterion.getName());
    values.put("description", criterion.getDescription());
    values.put("minScore", criterion.getMinScore());
    values.put("maxScore", criterion.getMaxScore());
    values.put("plantId", criterion.getPlantId());
    values.put("active", criterion.isActive());
    values.put("sortOrder", criterion.getSortOrder());
    return values;
  }

  private List<WorkLogRatingView> toViews(WorkLogEntity workLog, List<WorkLogRatingEntity> ratingEntities,
      List<WorkLogRatingCriterionEntity> criteriaEntities) {
    var nameById = new HashMap<UUID, String>();
    for (var criterion : criteriaEntities) {
      nameById.put(criterion.getId(), criterion.getName());
    }
    var views = new ArrayList<WorkLogRatingView>();
    for (var rating : ratingEntities) {
      views.add(new WorkLogRatingView(rating.getId(), rating.getWorkLogId(), workLog.getWorkOrderId(),
          rating.getCriterionId(), nameById.get(rating.getCriterionId()), rating.getScore(),
          rating.getRatedBy(), rating.getRatedAt()));
    }
    return views;
  }

  private WorkLogRatingCriterionView toCriterionView(WorkLogRatingCriterionEntity criterion) {
    return new WorkLogRatingCriterionView(criterion.getId(), criterion.getName(), criterion.getDescription(),
        criterion.getMinScore(), criterion.getMaxScore(), criterion.getPlantId(), criterion.isActive(),
        criterion.getSortOrder(), criterion.getCreatedAt(), criterion.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  public record RateWorkLogCommand(Map<UUID, Integer> scores) {
  }

  public record CreateCriterionCommand(String name, String description, int minScore, int maxScore,
      UUID plantId, boolean active, int sortOrder) {
  }

  public record UpdateCriterionCommand(String name, String description, int minScore, int maxScore,
      UUID plantId, boolean active, int sortOrder) {
  }

  public record WorkLogRatingView(UUID id, UUID workLogId, String workOrderId, UUID criterionId,
      String criterionName, int score, UUID ratedBy, Instant ratedAt) {
  }

  public record WorkLogRatingCriterionView(UUID id, String name, String description, int minScore,
      int maxScore, UUID plantId, boolean active, int sortOrder, Instant createdAt, Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class WorkLogRatingForbiddenException extends RuntimeException {
  }

  public static class WorkLogRatingWorkOrderNotFoundException extends RuntimeException {
  }

  public static class WorkLogRatingMachineNotFoundException extends RuntimeException {
  }

  public static class WorkLogRatingNotClosedException extends RuntimeException {
  }

  public static class WorkLogRatingAlreadyExistsException extends RuntimeException {
  }

  public static class WorkLogRatingWorkLogNotFoundException extends RuntimeException {
  }

  public static class WorkLogRatingNotCompletedException extends RuntimeException {
  }

  public static class WorkLogRatingCriterionNotFoundException extends RuntimeException {
  }

  public static class WorkLogRatingCriterionInUseException extends RuntimeException {
  }

  /** Field-level validation failure (unknown criterion, out-of-range score, bad score range). */
  public static class WorkLogRatingValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public WorkLogRatingValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
