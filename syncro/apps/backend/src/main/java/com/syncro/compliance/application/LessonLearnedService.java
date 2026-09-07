package com.syncro.compliance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.infrastructure.db.LessonLearnedEntity;
import com.syncro.compliance.infrastructure.db.LessonLearnedRepository;
import com.syncro.common.LikePattern;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lesson-learned CRUD + scoped search (story 21-3, blueprint H6). Mutations are
 * gated to the 21-1 six-role set (rego {@code compliance_lesson_paths} carries
 * the identical coarse set) and audit-logged with previous/new values and the
 * machine's plant dimension. Reads filter by machine scope INCLUDING the
 * null-machine clause — a lesson without a machine link is visible to every
 * authenticated user (the NC-unlinked precedent); SUPER_ADMIN sees all. An
 * out-of-scope detail/mutation target is 404 — existence is not leaked.
 *
 * <p>{@code project_id} is the client-supplied unique business key (one lesson
 * per project id — {@code uq_lesson_learned_project} retained) → duplicate →
 * 409 DUPLICATE_IDENTIFIER. Optional event links (nc_id/eight_d_id/work_order_id)
 * are validated to exist → 404 with stable codes; the FK is ON DELETE SET NULL,
 * so the lesson survives its source event. {@code evidence} is a JSONB array of
 * Garage {@code {objectKey, filename}} references (8D pdfArtifactUrl passthrough
 * posture — no attachment table, no Garage coupling this story). Search:
 * {@code q} is a case-insensitive substring over title/problem_summary,
 * {@code tag} matches the tags JSONB array via {@code tags::text} containment —
 * both through {@link LikePattern} escaping. The entity carries {@code @Version}
 * (V18) — lost updates surface as 409 VERSION_CONFLICT.
 */
@Service
public class LessonLearnedService {

  static final String SEARCH_NO_FILTER = "";

  /** Native IN cannot render an empty collection — this id never matches a row. */
  private static final UUID NO_MATCH = new UUID(0L, 0L);

  private final LessonLearnedRepository lessons;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public LessonLearnedService(LessonLearnedRepository lessons, OperationalScopeService scopes,
      AuditLogWriter auditLog, Clock clock) {
    this.lessons = lessons;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  public record CreateLessonCommand(String projectId, UUID machineId, String projectType,
      String title, String problemSummary, String rootCause, String solution,
      Map<String, Object> sparepartsUsed, Integer durationDays, Integer reCycleCount,
      List<String> tags, UUID ncId, UUID eightDId, String workOrderId,
      List<Map<String, Object>> evidence) {
  }

  /** Partial update (null keeps the stored value); project id and machine are immutable. */
  public record UpdateLessonCommand(String title, String problemSummary, String rootCause,
      String solution, Map<String, Object> sparepartsUsed, Integer durationDays,
      Integer reCycleCount, List<String> tags, List<Map<String, Object>> evidence,
      UUID ncId, UUID eightDId, String workOrderId) {
  }

  /** {@code version} exposed (21-2 L11 precedent) so clients can interpret VERSION_CONFLICT. */
  public record LessonView(UUID id, String projectId, UUID machineId, String projectType,
      String title, String problemSummary, String rootCause, String solution,
      Map<String, Object> sparepartsUsed, Integer durationDays, Integer reCycleCount,
      List<String> tags, UUID ncId, UUID eightDId, String workOrderId,
      List<Map<String, Object>> evidence, Instant createdAt, Instant updatedAt, long version) {
  }

  @Transactional(readOnly = true)
  public List<LessonView> search(AuthenticatedUser user, String q, String tag) {
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    // Native IN cannot render an empty list — a never-matching sentinel keeps the
    // predicate valid (the unrestricted branch short-circuits before it matters).
    var plantIds = NonConformanceService.plantIds(scope);
    var groupIds = NonConformanceService.groupIds(scope);
    return lessons.findScoped(unrestricted,
            plantIds.isEmpty() ? List.of(NO_MATCH) : plantIds,
            groupIds.isEmpty() ? List.of(NO_MATCH) : groupIds,
            q == null || q.isBlank() ? SEARCH_NO_FILTER : LikePattern.containsLower(q),
            tag == null || tag.isBlank() ? SEARCH_NO_FILTER : LikePattern.contains(tag))
        .stream()
        .map(LessonLearnedService::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public LessonView get(AuthenticatedUser user, UUID id) {
    return toView(loadVisible(user, id));
  }

  @Transactional
  public LessonView create(AuthenticatedUser user, CreateLessonCommand command) {
    NonConformanceService.requireMutationRole(user);
    var scope = scopes.derive(user);
    var machinePlantId = (UUID) null;
    if (command.machineId() != null) {
      var machineScope = lessons.findMachineScope(command.machineId())
          .orElseThrow(() -> new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
              "Referenced machine was not found."));
      // 21-1 P3 parity: a non-admin may not link a lesson to a machine they
      // cannot see — the row would be invisible to its own creator.
      if (scope.plantIds() != null && !machineInScope(scope, machineScope)) {
        throw new ComplianceForbiddenException();
      }
      machinePlantId = machineScope.getPlantId();
    }
    requireEventLinksResolve(command.ncId(), command.eightDId(),
        normalizeBlank(command.workOrderId()));
    if (lessons.existsByProjectId(command.projectId())) {
      throw new DuplicateIdentifierException();
    }
    var now = Instant.now(clock);
    var entity = new LessonLearnedEntity(UUID.randomUUID(), command.projectId(),
        command.machineId(), command.projectType(), command.title(), command.problemSummary(),
        command.rootCause(), command.solution(), command.sparepartsUsed(),
        command.durationDays(), command.reCycleCount(), command.tags(), command.ncId(),
        command.eightDId(), normalizeBlank(command.workOrderId()), command.evidence(), now, now);
    try {
      var saved = lessons.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.LESSON_LEARNED,
          saved.getId(), saved.getProjectId(), machinePlantId, null, auditValues(saved), null));
      return toView(saved);
    } catch (DataIntegrityViolationException race) {
      throw classifyWriteRace(race);
    }
  }

  /**
   * Write-race classification (review 21-3 F2/L5, 21-1 P5 chain walk): a
   * concurrent create won {@code uq_lesson_learned_project} → 409
   * DUPLICATE_IDENTIFIER; a source event deleted between existence validation
   * and the write (TOCTOU) violates an event-link FK → the link's stable 404
   * code, never an unclassified 500.
   */
  private static RuntimeException classifyWriteRace(DataIntegrityViolationException race) {
    if (NonConformanceService.causedBy(race, "uq_lesson_learned_project")) {
      return new DuplicateIdentifierException();
    }
    if (NonConformanceService.causedBy(race, "fk_lesson_learned_nc")) {
      return new ComplianceReferenceNotFoundException("NON_CONFORMANCE_NOT_FOUND",
          "Referenced non-conformance was not found.");
    }
    if (NonConformanceService.causedBy(race, "fk_lesson_learned_eight_d")) {
      return new ComplianceReferenceNotFoundException("EIGHT_D_REPORT_NOT_FOUND",
          "Referenced 8D report was not found.");
    }
    if (NonConformanceService.causedBy(race, "fk_lesson_learned_work_order")) {
      return new ComplianceReferenceNotFoundException("WORK_ORDER_NOT_FOUND",
          "Referenced workorder was not found.");
    }
    return race;
  }

  @Transactional
  public LessonView update(AuthenticatedUser user, UUID id, UpdateLessonCommand command) {
    NonConformanceService.requireMutationRole(user);
    var entity = loadVisible(user, id);
    requireNotBlankFields(command);
    requireEventLinksResolve(command.ncId(), command.eightDId(),
        normalizeBlank(command.workOrderId()));
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    entity.updateContent(command.title(), command.problemSummary(), command.rootCause(),
        command.solution(), command.sparepartsUsed(), command.durationDays(),
        command.reCycleCount(), command.tags(), command.evidence(), now);
    entity.linkEvents(command.ncId(), command.eightDId(), normalizeBlank(command.workOrderId()),
        now);
    LessonLearnedEntity saved;
    try {
      saved = lessons.saveAndFlush(entity);
    } catch (DataIntegrityViolationException race) {
      // Review 21-3 F2: the same TOCTOU FK classification as create — an event
      // deleted between validation and this UPDATE surfaces as its 404 code.
      throw classifyWriteRace(race);
    }
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.LESSON_LEARNED,
        saved.getId(), saved.getProjectId(), resolvePlantId(saved), previous,
        auditValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID id) {
    NonConformanceService.requireMutationRole(user);
    var entity = loadVisible(user, id);
    var previous = auditValues(entity);
    lessons.delete(entity);
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.LESSON_LEARNED,
        id, entity.getProjectId(), resolvePlantId(entity), previous, null, null));
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  /** Loads the lesson and enforces machine-scope visibility (404 when filtered out). */
  LessonLearnedEntity loadVisible(AuthenticatedUser user, UUID id) {
    var entity = lessons.findById(id).orElseThrow(LessonNotFoundException::new);
    var scope = scopes.derive(user);
    if (lessons.countVisible(id, scope.plantIds() == null,
        NonConformanceService.plantIds(scope), NonConformanceService.groupIds(scope)) == 0) {
      throw new LessonNotFoundException();
    }
    return entity;
  }

  /** Event-link existence validation (spec AC2): unknown reference → 404 stable code. */
  private void requireEventLinksResolve(UUID ncId, UUID eightDId, String workOrderId) {
    if (ncId != null && lessons.countNonConformance(ncId) == 0) {
      throw new ComplianceReferenceNotFoundException("NON_CONFORMANCE_NOT_FOUND",
          "Referenced non-conformance was not found.");
    }
    if (eightDId != null && lessons.countEightD(eightDId) == 0) {
      throw new ComplianceReferenceNotFoundException("EIGHT_D_REPORT_NOT_FOUND",
          "Referenced 8D report was not found.");
    }
    if (workOrderId != null && lessons.countWorkOrder(workOrderId) == 0) {
      throw new ComplianceReferenceNotFoundException("WORK_ORDER_NOT_FOUND",
          "Referenced workorder was not found.");
    }
  }

  /** Blank links normalize to null (21-2 L13 precedent) — never stored, never FK-violated. */
  private static String normalizeBlank(String value) {
    return value != null && value.isBlank() ? null : value;
  }

  private static boolean machineInScope(OperationalScope scope,
      LessonLearnedRepository.MachineScopeView machine) {
    return scope.plantIds().contains(machine.getPlantId())
        || NonConformanceService.groupIds(scope).contains(machine.getGroupId());
  }

  /** Audit plant dimension (21-1 P2 parity): the linked machine's plant, else null. */
  UUID resolvePlantId(LessonLearnedEntity entity) {
    if (entity.getMachineId() == null) {
      return null;
    }
    return lessons.findMachineScope(entity.getMachineId())
        .map(LessonLearnedRepository.MachineScopeView::getPlantId)
        .orElse(null);
  }

  /** 21-1 P4 parity: provided-but-blank text fields are rejected; null keeps stored. */
  private static void requireNotBlankFields(UpdateLessonCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (command.title() != null && command.title().isBlank()) {
      fieldErrors.put("title", "Title must not be blank.");
    }
    if (command.problemSummary() != null && command.problemSummary().isBlank()) {
      fieldErrors.put("problemSummary", "Problem summary must not be blank.");
    }
    if (command.rootCause() != null && command.rootCause().isBlank()) {
      fieldErrors.put("rootCause", "Root cause must not be blank.");
    }
    if (command.solution() != null && command.solution().isBlank()) {
      fieldErrors.put("solution", "Solution must not be blank.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ComplianceValidationException(fieldErrors);
    }
  }

  private static Map<String, Object> auditValues(LessonLearnedEntity l) {
    var values = new LinkedHashMap<String, Object>();
    values.put("projectId", l.getProjectId());
    values.put("machineId", l.getMachineId() != null ? l.getMachineId().toString() : null);
    values.put("projectType", l.getProjectType());
    values.put("title", l.getTitle());
    values.put("problemSummary", l.getProblemSummary());
    values.put("rootCause", l.getRootCause());
    values.put("solution", l.getSolution());
    values.put("sparepartsUsed", l.getSparepartsUsed());
    values.put("durationDays", l.getDurationDays());
    values.put("reCycleCount", l.getReCycleCount());
    values.put("tags", l.getTags());
    values.put("ncId", l.getNcId() != null ? l.getNcId().toString() : null);
    values.put("eightDId", l.getEightDId() != null ? l.getEightDId().toString() : null);
    values.put("workOrderId", l.getWorkOrderId());
    values.put("evidence", l.getEvidence());
    return values;
  }

  static LessonView toView(LessonLearnedEntity l) {
    return new LessonView(l.getId(), l.getProjectId(), l.getMachineId(), l.getProjectType(),
        l.getTitle(), l.getProblemSummary(), l.getRootCause(), l.getSolution(),
        l.getSparepartsUsed(), l.getDurationDays(), l.getReCycleCount(), l.getTags(),
        l.getNcId(), l.getEightDId(), l.getWorkOrderId(), l.getEvidence(), l.getCreatedAt(),
        l.getUpdatedAt(), l.getVersion());
  }

  // -------------------------------------------------------------------------
  // Exceptions (mapped by ComplianceExceptionHandler to stable codes)
  // -------------------------------------------------------------------------

  /** Unknown or out-of-scope lesson. → 404 LESSON_NOT_FOUND. */
  public static class LessonNotFoundException extends RuntimeException {
  }
}
