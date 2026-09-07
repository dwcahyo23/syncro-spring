package com.syncro.compliance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.domain.EcnStatus;
import com.syncro.compliance.infrastructure.db.EquipmentChangeNoticeEntity;
import com.syncro.compliance.infrastructure.db.EquipmentChangeNoticeRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipment change notice CRUD + approval lifecycle (story 21-2, blueprint H4).
 * Create/update/submit are gated to the 21-1 six-role set (rego
 * {@code compliance_ecn_paths}); approve/execute/close narrow to
 * SUPER_ADMIN/MANAGER_MAINTENANCE (rego {@code compliance_ecn_approval_paths})
 * — parity with the service gates. Reads filter by machine scope with the 21-1
 * {@code findScoped} predicate minus the null-machine clause ({@code machine_id}
 * is NOT NULL): plant OR machine-group from the single derived
 * {@link OperationalScope} (AD-2); SUPER_ADMIN sees all. An out-of-scope
 * detail/mutation target is 404 — existence is not leaked.
 *
 * <p>Transitions are forward-only: submit DRAFT→UNDER_REVIEW (stamps
 * submitted_by); approve UNDER_REVIEW→APPROVED (stamps reviewed_by/approved_by/
 * effective_date/sign_off_at with the server clock); execute APPROVED→EXECUTED
 * (optional executed_wo_id evidence link, validated against work_orders, plus
 * after_photo_url); close EXECUTED→CLOSED; anything else → 409
 * INVALID_STATE_TRANSITION. {@code ecn_number} is a client-supplied immutable
 * business id → duplicate → 409 DUPLICATE_IDENTIFIER. The entity carries
 * {@code @Version} (V17) — lost updates surface as 409 VERSION_CONFLICT. Every
 * mutation audit-logs previous/new values with the machine's plant dimension.
 */
@Service
public class EquipmentChangeNoticeService {

  static final String STATUS_NO_FILTER = "";

  private final EquipmentChangeNoticeRepository notices;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public EquipmentChangeNoticeService(EquipmentChangeNoticeRepository notices,
      OperationalScopeService scopes, AuditLogWriter auditLog, Clock clock) {
    this.notices = notices;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  public record CreateEcnCommand(String ecnNumber, UUID machineId, String title,
      String description, String changeType, String justification, String beforePhotoUrl) {
  }

  /** Partial update (null keeps the stored value); number and machine are immutable. */
  public record UpdateEcnCommand(String title, String description, String changeType,
      String justification, String beforePhotoUrl) {
  }

  public record ApproveEcnCommand(LocalDate effectiveDate) {
  }

  public record ExecuteEcnCommand(String executedWoId, String afterPhotoUrl) {
  }

  /** {@code version} exposed (review 21-2 L11) so clients can interpret VERSION_CONFLICT. */
  public record EcnView(UUID id, String ecnNumber, UUID machineId, String title,
      String description, String changeType, String justification, EcnStatus status,
      UUID submittedBy, UUID reviewedBy, UUID approvedBy, LocalDate effectiveDate,
      String executedWoId, Instant signOffAt, String beforePhotoUrl, String afterPhotoUrl,
      Instant createdAt, Instant updatedAt, long version) {
  }

  @Transactional(readOnly = true)
  public List<EcnView> list(AuthenticatedUser user, EcnStatus status) {
    var scope = scopes.derive(user);
    return notices.findScoped(scope.plantIds() == null, plantIds(scope), groupIds(scope),
            status == null ? STATUS_NO_FILTER : status.name()).stream()
        .map(EquipmentChangeNoticeService::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public EcnView get(AuthenticatedUser user, UUID id) {
    return toView(loadVisible(user, id));
  }

  @Transactional
  public EcnView create(AuthenticatedUser user, CreateEcnCommand command) {
    NonConformanceService.requireMutationRole(user);
    var scope = scopes.derive(user);
    // machine_id is NOT NULL — the reference must resolve, and a non-admin may
    // only file an ECN against a machine they can see (21-1 P3 parity).
    var machineScope = notices.findMachineScope(command.machineId())
        .orElseThrow(() -> new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
            "Referenced machine was not found."));
    if (scope.plantIds() != null && !machineInScope(scope, machineScope)) {
      throw new ComplianceForbiddenException();
    }
    if (notices.existsByEcnNumber(command.ecnNumber())) {
      throw new DuplicateIdentifierException();
    }
    var now = Instant.now(clock);
    var entity = new EquipmentChangeNoticeEntity(UUID.randomUUID(), command.ecnNumber(),
        command.machineId(), command.title(), command.description(), command.changeType(),
        command.justification(), EcnStatus.DRAFT, null, null, null, null, null, null,
        command.beforePhotoUrl(), null, now, now);
    try {
      var saved = notices.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE,
          AuditEntityType.EQUIPMENT_CHANGE_NOTICE, saved.getId(), saved.getEcnNumber(),
          machineScope.getPlantId(), null, auditValues(saved), null));
      return toView(saved);
    } catch (DataIntegrityViolationException race) {
      // Concurrent create won uq_equipment_change_notices_ecn_number (the only
      // constraint this insert can violate — the machine FK is ON DELETE RESTRICT
      // and pre-validated). Classify by constraint name in the cause chain (21-1 P5).
      if (NonConformanceService.causedBy(race, "uq_equipment_change_notices_ecn_number")) {
        throw new DuplicateIdentifierException();
      }
      throw race;
    }
  }

  @Transactional
  public EcnView update(AuthenticatedUser user, UUID id, UpdateEcnCommand command) {
    NonConformanceService.requireMutationRole(user);
    var entity = loadVisible(user, id);
    // Review 21-2 H2: content freeze once the ECN leaves DRAFT — the approved/
    // executed artifact must not diverge from what was signed off.
    if (entity.getStatus() != EcnStatus.DRAFT) {
      throw new InvalidStateTransitionException();
    }
    // Review 21-2 M5: blank strings are rejected (null keeps the stored value).
    requireNotBlankFields(command);
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    entity.updateContent(command.title(), command.description(), command.changeType(),
        command.justification(), command.beforePhotoUrl(), now);
    var saved = notices.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.EQUIPMENT_CHANGE_NOTICE, saved.getId(), saved.getEcnNumber(),
        resolvePlantId(saved), previous, auditValues(saved), null));
    return toView(saved);
  }

  /** DRAFT→UNDER_REVIEW; stamps the submitting actor with the server clock. */
  @Transactional
  public EcnView submit(AuthenticatedUser user, UUID id) {
    NonConformanceService.requireMutationRole(user);
    var entity = loadVisible(user, id);
    requireTransition(entity.getStatus(), EcnStatus.UNDER_REVIEW);
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    entity.submit(UUID.fromString(user.id()), now);
    var saved = saveTransition(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.EQUIPMENT_CHANGE_NOTICE, saved.getId(), saved.getEcnNumber(),
        resolvePlantId(saved), previous, auditValues(saved), null));
    return toView(saved);
  }

  /**
   * UNDER_REVIEW→APPROVED (SUPER_ADMIN/MANAGER_MAINTENANCE only): stamps
   * reviewed_by + approved_by (the acting approver), effective_date (request or
   * today), and sign_off_at with the server clock.
   */
  @Transactional
  public EcnView approve(AuthenticatedUser user, UUID id, ApproveEcnCommand command) {
    requireApprovalRole(user);
    var entity = loadVisible(user, id);
    requireTransition(entity.getStatus(), EcnStatus.APPROVED);
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    var approverId = UUID.fromString(user.id());
    var effectiveDate = command != null && command.effectiveDate() != null
        ? command.effectiveDate() : LocalDate.now(clock);
    entity.review(EcnStatus.APPROVED, approverId, approverId, effectiveDate, now, now);
    var saved = saveTransition(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.EQUIPMENT_CHANGE_NOTICE, saved.getId(), saved.getEcnNumber(),
        resolvePlantId(saved), previous, auditValues(saved), null));
    return toView(saved);
  }

  /**
   * APPROVED→EXECUTED (SUPER_ADMIN/MANAGER_MAINTENANCE only): links the
   * implementing workorder (optional, validated → 404 WORK_ORDER_NOT_FOUND) and
   * the after-change photo.
   */
  @Transactional
  public EcnView execute(AuthenticatedUser user, UUID id, ExecuteEcnCommand command) {
    requireApprovalRole(user);
    var entity = loadVisible(user, id);
    requireTransition(entity.getStatus(), EcnStatus.EXECUTED);
    var executedWoId = command != null ? command.executedWoId() : null;
    if (executedWoId != null && !executedWoId.isBlank()) {
      if (notices.countWorkOrder(executedWoId) == 0) {
        throw new ComplianceReferenceNotFoundException("WORK_ORDER_NOT_FOUND",
            "Referenced workorder was not found.");
      }
      // Review 21-2 M6: the evidence workorder must belong to the ECN's machine
      // plant — a cross-plant WO is not evidence for this change.
      var ecnPlantId = notices.findMachineScope(entity.getMachineId())
          .map(EquipmentChangeNoticeRepository.MachineScopeView::getPlantId)
          .orElse(null);
      var woPlantId = notices.findWorkOrderMachinePlant(executedWoId).orElse(null);
      if (ecnPlantId != null && !ecnPlantId.equals(woPlantId)) {
        throw new ComplianceReferenceNotFoundException("WORK_ORDER_NOT_FOUND",
            "Referenced workorder does not belong to this equipment's plant.");
      }
    } else {
      executedWoId = null;
    }
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    // Review 21-2 L13: a blank afterPhotoUrl is normalized to null (never stored blank).
    var afterPhotoUrl = command != null && command.afterPhotoUrl() != null
        && !command.afterPhotoUrl().isBlank() ? command.afterPhotoUrl() : null;
    entity.execute(executedWoId, afterPhotoUrl, now);
    var saved = saveTransition(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.EQUIPMENT_CHANGE_NOTICE, saved.getId(), saved.getEcnNumber(),
        resolvePlantId(saved), previous, auditValues(saved), null));
    return toView(saved);
  }

  /** EXECUTED→CLOSED (SUPER_ADMIN/MANAGER_MAINTENANCE only). */
  @Transactional
  public EcnView close(AuthenticatedUser user, UUID id) {
    requireApprovalRole(user);
    var entity = loadVisible(user, id);
    requireTransition(entity.getStatus(), EcnStatus.CLOSED);
    var previous = auditValues(entity);
    var now = Instant.now(clock);
    entity.close(now);
    var saved = saveTransition(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.EQUIPMENT_CHANGE_NOTICE, saved.getId(), saved.getEcnNumber(),
        resolvePlantId(saved), previous, auditValues(saved), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  /**
   * Persists a lifecycle transition, classifying actor-FK races (review 21-2 M8):
   * a deleted user's still-valid token can violate submitted_by/reviewed_by/
   * approved_by (FK ON DELETE SET NULL only clears existing rows; a hard-deleted
   * actor racing the UPDATE violates it). The actor is no longer a valid
   * principal → 403, never an unclassified 500 (21-1 P5 chain walk).
   */
  private EquipmentChangeNoticeEntity saveTransition(EquipmentChangeNoticeEntity entity) {
    try {
      return notices.saveAndFlush(entity);
    } catch (DataIntegrityViolationException race) {
      for (String constraint : List.of("fk_equipment_change_notices_submitted_by",
          "fk_equipment_change_notices_reviewed_by", "fk_equipment_change_notices_approved_by")) {
        if (NonConformanceService.causedBy(race, constraint)) {
          throw new ComplianceForbiddenException();
        }
      }
      throw race;
    }
  }

  /** Loads the ECN and enforces machine-scope visibility (404 when filtered out). */
  EquipmentChangeNoticeEntity loadVisible(AuthenticatedUser user, UUID id) {
    var entity = notices.findById(id).orElseThrow(EquipmentChangeNoticeNotFoundException::new);
    var scope = scopes.derive(user);
    if (notices.countVisible(id, scope.plantIds() == null, plantIds(scope), groupIds(scope)) == 0) {
      throw new EquipmentChangeNoticeNotFoundException();
    }
    return entity;
  }

  static List<UUID> plantIds(OperationalScope scope) {
    return scope.plantIds() == null ? List.of() : List.copyOf(scope.plantIds());
  }

  static List<UUID> groupIds(OperationalScope scope) {
    var merged = new LinkedHashSet<UUID>();
    if (scope.machineGroupIds() != null) {
      merged.addAll(scope.machineGroupIds());
    }
    if (scope.activeTeamIds() != null) {
      merged.addAll(scope.activeTeamIds());
    }
    return List.copyOf(merged);
  }

  private static boolean machineInScope(OperationalScope scope,
      EquipmentChangeNoticeRepository.MachineScopeView machine) {
    return scope.plantIds().contains(machine.getPlantId())
        || groupIds(scope).contains(machine.getGroupId());
  }

  /** Audit plant dimension (21-1 P2 parity): the linked machine's plant. */
  UUID resolvePlantId(EquipmentChangeNoticeEntity entity) {
    return notices.findMachineScope(entity.getMachineId())
        .map(EquipmentChangeNoticeRepository.MachineScopeView::getPlantId)
        .orElse(null);
  }

  /** Approve/execute/close role set (spec "Always"); rego mirrors it exactly. */
  static void requireApprovalRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new ComplianceForbiddenException();
    }
  }

  /** Review 21-2 M5 (21-1 P4 parity): provided-but-blank text fields are rejected; null keeps stored. */
  private static void requireNotBlankFields(UpdateEcnCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (command.title() != null && command.title().isBlank()) {
      fieldErrors.put("title", "Title must not be blank.");
    }
    if (command.description() != null && command.description().isBlank()) {
      fieldErrors.put("description", "Description must not be blank.");
    }
    if (command.changeType() != null && command.changeType().isBlank()) {
      fieldErrors.put("changeType", "Change type must not be blank.");
    }
    if (command.justification() != null && command.justification().isBlank()) {
      fieldErrors.put("justification", "Justification must not be blank.");
    }
    if (command.beforePhotoUrl() != null && command.beforePhotoUrl().isBlank()) {
      fieldErrors.put("beforePhotoUrl", "Before photo URL must not be blank.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ComplianceValidationException(fieldErrors);
    }
  }

  /** The only legal moves are the four forward edges; anything else → 409. */
  private static void requireTransition(EcnStatus from, EcnStatus to) {
    var legal = (from == EcnStatus.DRAFT && to == EcnStatus.UNDER_REVIEW)
        || (from == EcnStatus.UNDER_REVIEW && to == EcnStatus.APPROVED)
        || (from == EcnStatus.APPROVED && to == EcnStatus.EXECUTED)
        || (from == EcnStatus.EXECUTED && to == EcnStatus.CLOSED);
    if (!legal) {
      throw new InvalidStateTransitionException();
    }
  }

  private static Map<String, Object> auditValues(EquipmentChangeNoticeEntity e) {
    var values = new LinkedHashMap<String, Object>();
    values.put("ecnNumber", e.getEcnNumber());
    values.put("machineId", e.getMachineId().toString());
    values.put("title", e.getTitle());
    values.put("description", e.getDescription());
    values.put("changeType", e.getChangeType());
    values.put("justification", e.getJustification());
    values.put("status", e.getStatus().name());
    values.put("submittedBy", e.getSubmittedBy() != null ? e.getSubmittedBy().toString() : null);
    values.put("reviewedBy", e.getReviewedBy() != null ? e.getReviewedBy().toString() : null);
    values.put("approvedBy", e.getApprovedBy() != null ? e.getApprovedBy().toString() : null);
    values.put("effectiveDate", e.getEffectiveDate() != null ? e.getEffectiveDate().toString() : null);
    values.put("executedWoId", e.getExecutedWoId());
    values.put("signOffAt", e.getSignOffAt() != null ? e.getSignOffAt().toString() : null);
    values.put("beforePhotoUrl", e.getBeforePhotoUrl());
    values.put("afterPhotoUrl", e.getAfterPhotoUrl());
    return values;
  }

  static EcnView toView(EquipmentChangeNoticeEntity e) {
    return new EcnView(e.getId(), e.getEcnNumber(), e.getMachineId(), e.getTitle(),
        e.getDescription(), e.getChangeType(), e.getJustification(), e.getStatus(),
        e.getSubmittedBy(), e.getReviewedBy(), e.getApprovedBy(), e.getEffectiveDate(),
        e.getExecutedWoId(), e.getSignOffAt(), e.getBeforePhotoUrl(), e.getAfterPhotoUrl(),
        e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  /** Unknown or out-of-scope ECN. → 404 ECN_NOT_FOUND. */
  public static class EquipmentChangeNoticeNotFoundException extends RuntimeException {
  }
}
