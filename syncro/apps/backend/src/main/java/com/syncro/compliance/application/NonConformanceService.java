package com.syncro.compliance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.compliance.infrastructure.db.NonConformanceEntity;
import com.syncro.compliance.infrastructure.db.NonConformanceRepository;
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
 * Non-conformance CRUD + lifecycle (story 21-1, blueprint H1). Mutations are gated
 * to the workorder-create role set (spec "Always": SUPER_ADMIN, MANAGER_MAINTENANCE,
 * MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER — rego
 * {@code compliance_nc_paths} carries the identical coarse set) and audit-logged with
 * previous/new values and the linked machine's plant (review 21-1 P2: audit rows are
 * plant-scoped like every other module's, never null-visible-to-all). Reads filter by
 * machine scope through the single derived {@link OperationalScope} (AD-2) with the
 * WorkOrderRepository.findScopedPage predicate (review 21-1 P1): plant OR machine-group
 * — a plant-assigned manager without LEADER responsibility still sees their plant's
 * NCs; NCs without a machine link are visible to any authenticated user; SUPER_ADMIN
 * sees all. An out-of-scope detail/mutation target is reported as 404 — the row is
 * filtered from the caller's view, its existence is not leaked.
 *
 * <p>Transitions: OPEN→IN_PROGRESS→CLOSED→VERIFIED; CLOSED stamps {@code closed_at}
 * with the server clock and requires rootCause + correctiveAction (request or stored);
 * anything else → 409 INVALID_STATE_TRANSITION. {@code nc_number} is a client-supplied
 * immutable business id → duplicate → 409 DUPLICATE_IDENTIFIER. The entity carries
 * {@code @Version} (review 21-1 P6) — lost updates surface as 409 VERSION_CONFLICT.
 */
@Service
public class NonConformanceService {

  static final String STATUS_NO_FILTER = "";

  private final NonConformanceRepository nonConformances;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public NonConformanceService(NonConformanceRepository nonConformances,
      OperationalScopeService scopes, AuditLogWriter auditLog, Clock clock) {
    this.nonConformances = nonConformances;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  public record CreateNcCommand(String ncNumber, String description, NcSeverity severity,
      String projectId, String workOrderId, UUID machineId, UUID responsibleId,
      LocalDate targetCloseDate) {
  }

  /** Partial update (null keeps the stored value); {@code status} rides the transition gate. */
  public record UpdateNcCommand(NcStatus status, String description, NcSeverity severity,
      String rootCause, String correctiveAction, UUID responsibleId, LocalDate targetCloseDate) {
  }

  public record NcView(UUID id, String projectId, String workOrderId, UUID machineId,
      String ncNumber, String description, String rootCause, String correctiveAction,
      UUID responsibleId, NcStatus status, NcSeverity severity, LocalDate targetCloseDate,
      Instant closedAt, Instant createdAt, Instant updatedAt) {
  }

  @Transactional(readOnly = true)
  public List<NcView> list(AuthenticatedUser user, NcStatus status) {
    var scope = scopes.derive(user);
    return nonConformances
        .findScoped(scope.plantIds() == null, plantIds(scope), groupIds(scope),
            status == null ? STATUS_NO_FILTER : status.name())
        .stream()
        .map(NonConformanceService::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public NcView get(AuthenticatedUser user, UUID id) {
    return toView(loadVisible(user, id));
  }

  @Transactional
  public NcView create(AuthenticatedUser user, CreateNcCommand command) {
    requireMutationRole(user);
    var scope = scopes.derive(user);
    var machinePlantId = (UUID) null;
    if (command.machineId() != null) {
      var machineScope = nonConformances.findMachineScope(command.machineId())
          .orElseThrow(() -> new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
              "Referenced machine was not found."));
      // Review 21-1 P3: a non-admin may only link an NC to a machine they can see —
      // otherwise the created record would be invisible to its own creator.
      if (scope.plantIds() != null && !machineInScope(scope, machineScope)) {
        throw new ComplianceForbiddenException();
      }
      machinePlantId = machineScope.getPlantId();
    }
    if (command.workOrderId() != null
        && nonConformances.countWorkOrder(command.workOrderId()) == 0) {
      throw new ComplianceReferenceNotFoundException("WORK_ORDER_NOT_FOUND",
          "Referenced workorder was not found.");
    }
    if (command.responsibleId() != null && nonConformances.countUser(command.responsibleId()) == 0) {
      throw new ComplianceReferenceNotFoundException("USER_NOT_FOUND",
          "Referenced responsible user was not found.");
    }
    if (nonConformances.existsByNcNumber(command.ncNumber())) {
      throw new DuplicateIdentifierException();
    }
    var now = Instant.now(clock);
    var entity = new NonConformanceEntity(UUID.randomUUID(), command.projectId(),
        command.workOrderId(), command.machineId(), command.ncNumber(), command.description(),
        null, null, command.responsibleId(), NcStatus.OPEN, command.severity(),
        command.targetCloseDate(), null, now, now);
    try {
      var saved = nonConformances.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.NON_CONFORMANCE,
          saved.getId(), saved.getNcNumber(), machinePlantId, null, auditValues(saved), null));
      return toView(saved);
    } catch (DataIntegrityViolationException race) {
      // Concurrent create won the uq_non_conformances_nc_number constraint (the only
      // constraint this insert can violate — references are pre-validated). The
      // session is rollback-only here, so re-querying is unsafe; classify by the
      // constraint name anywhere in the cause chain (review 21-1 P5).
      if (causedBy(race, "uq_non_conformances_nc_number")) {
        throw new DuplicateIdentifierException();
      }
      throw race;
    }
  }

  @Transactional
  public NcView update(AuthenticatedUser user, UUID id, UpdateNcCommand command) {
    requireMutationRole(user);
    var entity = loadVisible(user, id);
    // Review 21-1 P3: the update path validates the responsible reference too —
    // create does, and a dangling FK would escape as an unclassified 500.
    if (command.responsibleId() != null && nonConformances.countUser(command.responsibleId()) == 0) {
      throw new ComplianceReferenceNotFoundException("USER_NOT_FOUND",
          "Referenced responsible user was not found.");
    }
    // Review 21-1 P4: blank strings are rejected (null keeps the stored value).
    requireNotBlankFields(command);
    var previous = auditValues(entity);
    var now = Instant.now(clock);

    if (command.status() != null) {
      requireTransition(entity.getStatus(), command.status());
      if (command.status() == NcStatus.CLOSED) {
        requireClosureAnalysis(entity, command);
      }
      entity.transitionTo(command.status(), now);
      if (command.status() == NcStatus.CLOSED) {
        // close() also persists the analysis fields — merged request/stored values.
        var rootCause = command.rootCause() != null ? command.rootCause() : entity.getRootCause();
        var corrective = command.correctiveAction() != null ? command.correctiveAction()
            : entity.getCorrectiveAction();
        entity.close(rootCause, corrective, now, now);
      }
    }
    entity.updateContent(command.description(), command.severity(),
        command.status() == NcStatus.CLOSED ? null : command.rootCause(),
        command.status() == NcStatus.CLOSED ? null : command.correctiveAction(),
        command.responsibleId(), command.targetCloseDate(), now);

    var saved = nonConformances.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.NON_CONFORMANCE,
        saved.getId(), saved.getNcNumber(), resolvePlantId(saved), previous, auditValues(saved),
        null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  /** Loads the NC and enforces machine-scope visibility (404 when filtered out). */
  NonConformanceEntity loadVisible(AuthenticatedUser user, UUID id) {
    var entity = nonConformances.findById(id)
        .orElseThrow(NonConformanceNotFoundException::new);
    var scope = scopes.derive(user);
    if (nonConformances.countVisible(id, scope.plantIds() == null, plantIds(scope),
        groupIds(scope)) == 0) {
      throw new NonConformanceNotFoundException();
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
      NonConformanceRepository.MachineScopeView machine) {
    return scope.plantIds().contains(machine.getPlantId())
        || groupIds(scope).contains(machine.getGroupId());
  }

  /** Audit plant dimension (review 21-1 P2): the linked machine's plant, else null. */
  UUID resolvePlantId(NonConformanceEntity entity) {
    if (entity.getMachineId() == null) {
      return null;
    }
    return nonConformances.findMachineScope(entity.getMachineId())
        .map(NonConformanceRepository.MachineScopeView::getPlantId)
        .orElse(null);
  }

  /** Workorder-create parity role set (spec "Always"); shared with the 8D service. */
  static void requireMutationRole(AuthenticatedUser user) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER,
          STAFF_MAINTENANCE, PRODUCTION_LEADER -> {
        // allowed (workorder-create parity)
      }
      case TECHNICIAN, AUDITOR, INVENTORY_MAINTENANCE, STOREKEEPER ->
          throw new ComplianceForbiddenException();
    }
  }

  /** The only legal moves are the three forward edges; anything else → 409. */
  private static void requireTransition(NcStatus from, NcStatus to) {
    var legal = (from == NcStatus.OPEN && to == NcStatus.IN_PROGRESS)
        || (from == NcStatus.IN_PROGRESS && to == NcStatus.CLOSED)
        || (from == NcStatus.CLOSED && to == NcStatus.VERIFIED);
    if (!legal) {
      throw new InvalidStateTransitionException();
    }
  }

  /** Review 21-1 P4: provided-but-blank text fields are rejected; null keeps stored. */
  private static void requireNotBlankFields(UpdateNcCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (command.description() != null && command.description().isBlank()) {
      fieldErrors.put("description", "Description must not be blank.");
    }
    if (command.rootCause() != null && command.rootCause().isBlank()) {
      fieldErrors.put("rootCause", "Root cause must not be blank.");
    }
    if (command.correctiveAction() != null && command.correctiveAction().isBlank()) {
      fieldErrors.put("correctiveAction", "Corrective action must not be blank.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ComplianceValidationException(fieldErrors);
    }
  }

  /** CLOSED requires rootCause + correctiveAction — from the request or already stored. */
  private static void requireClosureAnalysis(NonConformanceEntity entity, UpdateNcCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    var rootCause = command.rootCause() != null ? command.rootCause() : entity.getRootCause();
    var corrective = command.correctiveAction() != null ? command.correctiveAction()
        : entity.getCorrectiveAction();
    if (rootCause == null || rootCause.isBlank()) {
      fieldErrors.put("rootCause", "Root cause is required to close a non-conformance.");
    }
    if (corrective == null || corrective.isBlank()) {
      fieldErrors.put("correctiveAction",
          "Corrective action is required to close a non-conformance.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ComplianceValidationException(fieldErrors);
    }
  }

  /** Walks the full JDBC cause chain looking for a constraint name (review 21-1 P5). */
  static boolean causedBy(Throwable throwable, String constraintName) {
    for (var current = throwable; current != null; current = current.getCause()) {
      var message = current.getMessage();
      if (message != null && message.contains(constraintName)) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> auditValues(NonConformanceEntity n) {
    var values = new LinkedHashMap<String, Object>();
    values.put("ncNumber", n.getNcNumber());
    values.put("projectId", n.getProjectId());
    values.put("workOrderId", n.getWorkOrderId());
    values.put("machineId", n.getMachineId() != null ? n.getMachineId().toString() : null);
    values.put("description", n.getDescription());
    values.put("rootCause", n.getRootCause());
    values.put("correctiveAction", n.getCorrectiveAction());
    values.put("responsibleId", n.getResponsibleId() != null ? n.getResponsibleId().toString() : null);
    values.put("status", n.getStatus().name());
    values.put("severity", n.getSeverity() != null ? n.getSeverity().name() : null);
    values.put("targetCloseDate", n.getTargetCloseDate() != null ? n.getTargetCloseDate().toString() : null);
    values.put("closedAt", n.getClosedAt() != null ? n.getClosedAt().toString() : null);
    return values;
  }

  static NcView toView(NonConformanceEntity n) {
    return new NcView(n.getId(), n.getProjectId(), n.getWorkOrderId(), n.getMachineId(),
        n.getNcNumber(), n.getDescription(), n.getRootCause(), n.getCorrectiveAction(),
        n.getResponsibleId(), n.getStatus(), n.getSeverity(), n.getTargetCloseDate(),
        n.getClosedAt(), n.getCreatedAt(), n.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Exceptions (mapped by ComplianceExceptionHandler to stable codes)
  // -------------------------------------------------------------------------

  /** Role gate denial (service-side; rego mirrors the same role set). → 403 FORBIDDEN. */
  public static class ComplianceForbiddenException extends RuntimeException {
  }

  /** Business-rule validation failure with field errors. → 400 VALIDATION_ERROR. */
  public static class ComplianceValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ComplianceValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  /** Unknown or out-of-scope NC. → 404 NON_CONFORMANCE_NOT_FOUND. */
  public static class NonConformanceNotFoundException extends RuntimeException {
  }

  /** Duplicate client-supplied business id (nc_number / report_number). → 409 DUPLICATE_IDENTIFIER. */
  public static class DuplicateIdentifierException extends RuntimeException {
  }

  /** Illegal lifecycle move. → 409 INVALID_STATE_TRANSITION. */
  public static class InvalidStateTransitionException extends RuntimeException {
  }

  /** Create-time reference (machine/workorder/user) does not resolve. → 404 with stable code. */
  public static class ComplianceReferenceNotFoundException extends RuntimeException {
    private final String code;

    public ComplianceReferenceNotFoundException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String getCode() {
      return code;
    }
  }
}
