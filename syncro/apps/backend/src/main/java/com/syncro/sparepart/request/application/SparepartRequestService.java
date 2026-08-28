package com.syncro.sparepart.request.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.authz.application.PolicyDecisionPoint;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.request.domain.SparepartRequest;
import com.syncro.sparepart.request.domain.SparepartRequestStateMachine;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestTimelineEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestTimelineRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sparepart request creation (FR-140/FR-143/FR-144, story 12-1) and state machine
 * (FR-141/FR-145/AD-5, story 12-2). Enforces the type rules, scopes, role gates, valid
 * transitions, MRE recording, and ON_PROCUREMENT recomputation via WorkOrderService.
 */
@Service
public class SparepartRequestService {

  private static final int MAX_URL_LENGTH = 2048;
  private static final int MAX_MATERIAL_CODE_LENGTH = 64;
  private static final int MAX_QUANTITY = Short.MAX_VALUE;
  private static final int MAX_EST_PRICE_SCALE = 2;
  private static final int MAX_EST_PRICE_PRECISION = 18;
  private static final int MAX_NOTE_LENGTH = 500;
  private static final int MAX_MRE_CODE_LENGTH = 64;
  private static final String TIMELINE_ACTION_TRANSITION = "TRANSITION";
  private static final String TIMELINE_ACTION_MRE = "MRE_RECORDED";
  private static final Set<String> SPAREPART_CATEGORY_CODES = Set.of("ELECTRIC", "MECHANIC");

  private final SparepartRequestRepository requests;
  private final SparepartRequestTimelineRepository timelines;
  private final WorkOrderRepository workOrders;
  private final WorkOrderService workOrderService;
  private final MachineRepository machines;
  private final SparepartRepository spareparts;
  private final SparepartPriceEntryRepository priceEntries;
  private final EscalationConfigService escalationConfigs;
  private final NotificationJobRepository notificationJobs;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public SparepartRequestService(SparepartRequestRepository requests,
      SparepartRequestTimelineRepository timelines, WorkOrderRepository workOrders,
      WorkOrderService workOrderService, MachineRepository machines,
      SparepartRepository spareparts, SparepartPriceEntryRepository priceEntries,
      EscalationConfigService escalationConfigs, NotificationJobRepository notificationJobs,
      AuditLogWriter auditLog, OperationalScopeService scopes, Clock clock) {
    this.requests = requests;
    this.timelines = timelines;
    this.workOrders = workOrders;
    this.workOrderService = workOrderService;
    this.machines = machines;
    this.spareparts = spareparts;
    this.priceEntries = priceEntries;
    this.escalationConfigs = escalationConfigs;
    this.notificationJobs = notificationJobs;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public RequestPage list(AuthenticatedUser user, int page, int size) {
    validatePageAndSize(page, size);
    var scope = scopes.derive(user);
    var unrestricted = scope.plantIds() == null;
    var hasPlantScope = scope.plantIds() != null && !scope.plantIds().isEmpty();
    var groupIds = new java.util.HashSet<UUID>();
    groupIds.addAll(scope.machineGroupIds());
    groupIds.addAll(scope.activeTeamIds());

    var rows = requests.findScopedPage(
        unrestricted,
        hasPlantScope,
        scope.plantIds() == null ? List.of() : scope.plantIds(),
        groupIds,
        PageRequest.of(page, size));
    var total = requests.countScoped(
        unrestricted,
        hasPlantScope,
        scope.plantIds() == null ? List.of() : scope.plantIds(),
        groupIds);
    var items = rows.stream().map(SparepartRequestMapper::toDomain).toList();
    return new RequestPage(items, total, page, size);
  }

  private static void validatePageAndSize(int page, int size) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (page < 0) {
      fieldErrors.put("page", "Page must be 0 or greater.");
    }
    if (size < 1 || size > 200) {
      fieldErrors.put("size", "Size must be between 1 and 200.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new RequestValidationException(fieldErrors);
    }
  }

  @Transactional
  public SparepartRequest create(AuthenticatedUser user, CreateRequestCommand command) {
    validate(command);
    var resolved = resolveTarget(user, command);
    requireCreateAccess(user, resolved);

    var now = Instant.now(clock);
    var materialCode = normalizeMaterialCode(command.materialCode());
    var sparepart = resolveSparepart(materialCode, command.sparepartId(), command.requestType(), resolved.machineId());
    var status = resolveStatus(command.requestType(), materialCode);
    validatePriceEntry(command.estPriceId());
    var entity = new SparepartRequestEntity(UUID.randomUUID(), command.requestType(), resolved.workOrderId(),
        resolved.machineId(), sparepart == null ? null : sparepart.getId(), materialCode, command.quantity().shortValue(),
        command.estPriceId(), command.estUnitPrice(), normalizeUrl(command.purchaseReferenceUrl()),
        status, UUID.fromString(user.id()), now, normalize(command.notes()), now, now);
    var saved = requests.saveAndFlush(entity);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_REQUEST,
        saved.getId(), entityLabel(saved), resolved.plantId(), null, requestValues(saved), null));
    return SparepartRequestMapper.toDomain(saved);
  }

  /**
   * Request status transition (FR-141, story 12-2). Valid edges come from
   * {@link SparepartRequestStateMachine}; the actor gate is authoritative (in-scope
   * leader OR inventory/stores for ACK/PROCESSING/READY/PURCHASE_REQUESTED/PART_RECEIVED;
   * workorder section leader or SUPER_ADMIN for PICKED_UP/CLOSED — requester may not
   * close their own unless also a leader, SoD spirit AD-16). Writes a timeline row +
   * audit, then recomputes the workorder's derived ON_PROCUREMENT state (AD-5).
   */
  @Transactional
  public SparepartRequest transition(AuthenticatedUser user, UUID requestId, TransitionCommand command) {
    var entity = requests.findByIdForUpdate(requestId).orElseThrow(RequestNotFoundException::new);
    var fromStatus = entity.getStatus();
    var toStatus = command.toStatus();
    if (toStatus == null || !SparepartRequestStateMachine.can(fromStatus, toStatus)) {
      throw new InvalidRequestStateTransitionException();
    }
    var machine = machineForRequest(entity);
    requireTransitionAccess(user, entity, toStatus, machine);
    var requesterId = entity.getRequestedBy();
    var isRequester = requesterId != null && requesterId.equals(UUID.fromString(user.id()));
    if (!isInScopeLeader(user, machine) && isRequester
        && (toStatus == SparepartRequestStatus.PICKED_UP || toStatus == SparepartRequestStatus.CLOSED)) {
      // SoD (AD-16 spirit): the requester may not pick up/close their own request
      // unless they are also an in-scope leader (leaders pass the gate above).
      throw new RequestForbiddenException();
    }
    var previous = requestValues(entity);
    var now = Instant.now(clock);
    entity.transitionTo(toStatus, now);
    var saved = requests.saveAndFlush(entity);

    // Ack-stop (FR-147, story 12-3): reaching ACKED or CLOSED cancels every non-terminal
    // escalation job for this request in the same transaction (idempotency-key prefix).
    if (toStatus == SparepartRequestStatus.ACKED || toStatus == SparepartRequestStatus.CLOSED) {
      notificationJobs.cancelActiveForRequest(
          "SPAREPART_REQUEST:" + saved.getId() + ":%",
          List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT,
              NotificationJobStatus.RATE_LIMITED),
          NotificationJobStatus.CANCELLED,
          now);
    }

    var traceId = PolicyDecisionPoint.currentTraceId();
    timelines.saveAndFlush(new SparepartRequestTimelineEntity(UUID.randomUUID(), saved.getId(), fromStatus,
        toStatus, UUID.fromString(user.id()), TIMELINE_ACTION_TRANSITION, null,
        normalizeNote(command.note()), traceId, now));

    var newValue = requestValues(saved);
    if (command.note() != null && !command.note().isBlank()) {
      newValue.put("note", command.note().trim());
    }
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART_REQUEST,
        saved.getId(), entityLabel(saved), plantIdOf(machine), previous, newValue, null));

    recomputeProcurement(entity);
    return SparepartRequestMapper.toDomain(saved);
  }

  /**
   * Manual MRE code recording (FR-145, story 12-2). Only valid in PURCHASE_REQUESTED;
   * the code is trimmed (≤64 chars) and never auto-generated. Writes an MRE_RECORDED
   * timeline row + audit. MRE does not change readiness, so no recompute is needed.
   */
  @Transactional
  public SparepartRequest recordMre(AuthenticatedUser user, UUID requestId, MreCommand command) {
    var mreCode = normalizeMreCode(command.mreCode());
    if (mreCode == null || mreCode.length() > MAX_MRE_CODE_LENGTH) {
      throw new RequestValidationException(Map.of("mreCode",
          "MRE code must be between 1 and " + MAX_MRE_CODE_LENGTH + " characters."));
    }
    var entity = requests.findByIdForUpdate(requestId).orElseThrow(RequestNotFoundException::new);
    if (entity.getStatus() != SparepartRequestStatus.PURCHASE_REQUESTED) {
      throw new InvalidRequestStateTransitionException();
    }
    var machine = machineForRequest(entity);
    requireInventoryAccess(user, machine);
    var now = Instant.now(clock);

    timelines.saveAndFlush(new SparepartRequestTimelineEntity(UUID.randomUUID(), entity.getId(),
        entity.getStatus(), entity.getStatus(), UUID.fromString(user.id()), TIMELINE_ACTION_MRE,
        mreCode, normalizeNote(command.note()), PolicyDecisionPoint.currentTraceId(), now));

    var newValue = new LinkedHashMap<>(requestValues(entity));
    newValue.put("mreCode", mreCode);
    if (command.note() != null && !command.note().isBlank()) {
      newValue.put("note", command.note().trim());
    }
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART_REQUEST,
        entity.getId(), entityLabel(entity), plantIdOf(machine),
        requestValues(entity), newValue, null));
    return SparepartRequestMapper.toDomain(entity);
  }

  /**
   * Approve a sparepart request (story 12-3, FR-142/AD-16). Approval is NOT a new status —
   * it performs the REQUESTED/PENDING_COMPLETION → ACKED transition after the approval
   * gates pass. Gate order (authoritative server-side):
   * <ol>
   *   <li><b>SoD:</b> requester may never approve their own request → 403 SELF_APPROVAL_FORBIDDEN
   *       (before OPA and before any state-machine check).</li>
   *   <li><b>State:</b> current status must allow ACKED (else 409 INVALID_STATE_TRANSITION).</li>
   *   <li><b>Cost tier:</b> required role resolved from escalation_configs by estimatedCost
   *       (qty × unit price); no price → SECTION_LEADER.</li>
   *   <li><b>Role + scope:</b> user's application role must have authority ≥ the required
   *       role AND be an in-scope leader for the request's machine. SUPER_ADMIN bypasses.</li>
   * </ol>
   * On success the internal {@link #transition} machinery runs (timeline, audit,
   * ON_PROCUREMENT recompute, ack-stop).
   */
  @Transactional
  public SparepartRequest approve(AuthenticatedUser user, UUID requestId, ApproveCommand command) {
    var entity = requests.findByIdForUpdate(requestId).orElseThrow(RequestNotFoundException::new);
    var requesterId = entity.getRequestedBy();
    var actorId = UUID.fromString(user.id());
    if (requesterId != null && requesterId.equals(actorId)) {
      // SoD (AD-16, NFR-P2-5): enforced before OPA and before any state-machine check.
      throw new SelfApprovalForbiddenException();
    }
    if (!SparepartRequestStateMachine.can(entity.getStatus(), SparepartRequestStatus.ACKED)) {
      throw new InvalidRequestStateTransitionException();
    }

    var machine = machineForRequest(entity);
    var requiredRole = requiredApprovalRole(entity, machine);
    requireApprovalAccess(user, entity, machine, requiredRole);

    var note = command != null && command.note() != null && !command.note().isBlank()
        ? "Approved " + requiredRole + ": " + command.note().trim()
        : "Approved " + requiredRole;
    return transition(user, requestId, new TransitionCommand(SparepartRequestStatus.ACKED, note));
  }

  /**
   * Resolves the required approval role from escalation_configs tiers. The estimated cost
   * is qty × unit price in IDR; unit price = estUnitPrice when set, else the linked price
   * entry's idrAmount. No price at all → SECTION_LEADER (AD-16).
   */
  private String requiredApprovalRole(SparepartRequestEntity entity, MachineEntity machine) {
    BigDecimal unitPrice = entity.getEstUnitPrice();
    if (unitPrice == null && entity.getEstPriceId() != null) {
      unitPrice = priceEntries.findById(entity.getEstPriceId())
          .map(entry -> entry.getIdrAmount())
          .orElse(null);
    }
    boolean hasPrice = unitPrice != null;
    BigDecimal estimatedCost = hasPrice
        ? unitPrice.multiply(BigDecimal.valueOf(entity.getQuantity()))
        : null;
    return escalationConfigs.requiredApprovalRole(estimatedCost, hasPrice);
  }

  /**
   * Approval gate: SUPER_ADMIN bypasses. Otherwise the user must (a) hold an application
   * role with authority ≥ the required role (SECTION_LEADER < MAINTENANCE_LEADER <
   * MANAGER_MAINTENANCE — monotone) and (b) be an in-scope leader for the request's
   * machine (SECTION_LEADER group-in-scope; MAINTENANCE_LEADER/MANAGER plant-or-group).
   */
  private void requireApprovalAccess(AuthenticatedUser user, SparepartRequestEntity entity,
      MachineEntity machine, String requiredRole) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var role = user.applicationRole().name();
    if (!hasApprovalAuthority(role, requiredRole)) {
      throw new RequestForbiddenException();
    }
    if (!isInScopeLeader(user, machine)) {
      throw new RequestForbiddenException();
    }
  }

  /** Authority order: SECTION_LEADER < MAINTENANCE_LEADER < MANAGER_MAINTENANCE. */
  private static boolean hasApprovalAuthority(String actorRole, String requiredRole) {
    int actorRank = approvalRank(actorRole);
    int requiredRank = approvalRank(requiredRole);
    return actorRank >= requiredRank && actorRank > 0;
  }

  private static int approvalRank(String role) {
    return switch (role) {
      case "SECTION_LEADER" -> 1;
      case "MAINTENANCE_LEADER" -> 2;
      case "MANAGER_MAINTENANCE" -> 3;
      default -> 0;
    };
  }

  /**
   * Per-user allowed actions for a request (story 12-3). Computed server-side; the
   * frontend renders from this, never from its own role logic. {@code approve} is present
   * only when the current user can approve (not requester, approvable status,
   * role/scope sufficient). {@code requiredApprovalRole} is the tier's required role or
   * null when not approvable/approved.
   */
  public RequestAllowedActions allowedActionsFor(AuthenticatedUser user, SparepartRequest request) {
    var allowed = new java.util.LinkedHashSet<String>();
    String requiredRole = null;
    var status = request.status();

    if (SparepartRequestStateMachine.can(status, SparepartRequestStatus.ACKED)) {
      var isRequester = request.requestedBy() != null
          && request.requestedBy().equals(UUID.fromString(user.id()));
      if (!isRequester) {
        var entity = SparepartRequestMapper.toEntity(request);
        var machine = machineForRequest(entity);
        var required = requiredApprovalRole(entity, machine);
        if (canApprove(user, machine, required)) {
          allowed.add("approve");
          requiredRole = required;
        }
      }
    }
    return new RequestAllowedActions(allowed, requiredRole);
  }

  private boolean canApprove(AuthenticatedUser user, MachineEntity machine, String requiredRole) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return true;
    }
    if (!hasApprovalAuthority(user.applicationRole().name(), requiredRole)) {
      return false;
    }
    return isInScopeLeader(user, machine);
  }

  public record RequestAllowedActions(java.util.Set<String> allowedActions, String requiredApprovalRole) {
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void validate(CreateRequestCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    if (command.requestType() == null) {
      fieldErrors.put("requestType", "Request type must be SPAREPART, CONSUMABLE or SERVICE_EXTERNAL.");
    }
    if (command.quantity() == null || command.quantity() <= 0) {
      fieldErrors.put("quantity", "Quantity must be greater than zero.");
    } else if (command.quantity() > MAX_QUANTITY) {
      fieldErrors.put("quantity", "Quantity must be at most " + MAX_QUANTITY + ".");
    }
    var url = normalizeUrl(command.purchaseReferenceUrl());
    if (url != null && url.length() > MAX_URL_LENGTH) {
      fieldErrors.put("purchaseReferenceUrl", "Purchase reference URL must be at most 2048 characters.");
    }
    var materialCode = normalizeMaterialCode(command.materialCode());
    if (materialCode != null && materialCode.length() > MAX_MATERIAL_CODE_LENGTH) {
      fieldErrors.put("materialCode", "Material code must be at most 64 characters.");
    }
    if (command.estUnitPrice() != null) {
      if (command.estUnitPrice().signum() < 0) {
        fieldErrors.put("estUnitPrice", "Estimated unit price must be non-negative.");
      } else if (command.estUnitPrice().scale() > MAX_EST_PRICE_SCALE
          || command.estUnitPrice().precision() > MAX_EST_PRICE_PRECISION) {
        fieldErrors.put("estUnitPrice",
            "Estimated unit price must have at most 2 decimal places and 18 digits.");
      }
    }
    if (!fieldErrors.isEmpty()) {
      throw new RequestValidationException(fieldErrors);
    }
  }

  private record ResolvedTarget(String workOrderId, UUID machineId, UUID plantId) {
  }

  /** Type-rule resolution (FR-140): SERVICE_EXTERNAL requires a workorder; SPAREPART a machine; CONSUMABLE neither. */
  private ResolvedTarget resolveTarget(AuthenticatedUser user, CreateRequestCommand command) {
    switch (command.requestType()) {
      case SERVICE_EXTERNAL -> {
        if (command.workOrderId() == null) {
          throw new RequestValidationException(Map.of("requestType", "SERVICE_EXTERNAL requires a workorder."));
        }
        var workOrder = loadWorkOrder(command.workOrderId());
        return new ResolvedTarget(workOrder.getId(), workOrder.getMachineId(), machine(workOrder.getMachineId()).getPlant().getId());
      }
      case SPAREPART -> {
        if (command.machineId() == null) {
          throw new RequestValidationException(Map.of("requestType", "SPAREPART requires a machine."));
        }
        var machine = loadMachine(command.machineId());
        return new ResolvedTarget(null, machine.getId(), machine.getPlant().getId());
      }
      case CONSUMABLE -> {
        if (command.workOrderId() != null) {
          var workOrder = loadWorkOrder(command.workOrderId());
          return new ResolvedTarget(workOrder.getId(), null, machine(workOrder.getMachineId()).getPlant().getId());
        }
        return new ResolvedTarget(null, null, null);
      }
      default -> throw new RequestValidationException(Map.of("requestType", "Unknown request type."));
    }
  }

  /**
   * Access gate (spec): SUPER_ADMIN exempt; in-scope leader (group/team in scope) OR the
   * workorder's assigned executor/technician OR STAFF_MAINTENANCE with plant access to the
   * target machine (or the workorder's machine when bound). TECHNICIAN may only act as the
   * assigned executor on a bound workorder — a standalone SPAREPART targets a machine and
   * technicians are not plant-scoped for request creation. SECTION_LEADER is group-in-scope
   * only (FR-103).
   */
  private void requireCreateAccess(AuthenticatedUser user, ResolvedTarget resolved) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (resolved.workOrderId() != null) {
      var workOrder = loadWorkOrder(resolved.workOrderId());
      if (isExecutor(user, workOrder)) {
        return;
      }
      var machine = machine(workOrder.getMachineId());
      if (isInScopeLeader(user, machine)) {
        return;
      }
      // Bound workorder: STAFF_MAINTENANCE with plant access to the workorder's machine is
      // in scope (spec gate); other non-leader roles must be the assigned executor.
      if (user.applicationRole() == ApplicationRole.STAFF_MAINTENANCE
          && plantInScope(scopes.derive(user), machine)) {
        return;
      }
      throw new RequestForbiddenException();
    }
    if (resolved.machineId() != null) {
      var machine = machine(resolved.machineId());
      var scope = scopes.derive(user);
      switch (user.applicationRole()) {
        case SECTION_LEADER -> {
          if (groupInScope(scope, machine)) {
            return;
          }
        }
        case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
          if (plantInScope(scope, machine) || groupInScope(scope, machine)) {
            return;
          }
        }
        case STAFF_MAINTENANCE -> {
          if (plantInScope(scope, machine)) {
            return;
          }
        }
        case TECHNICIAN, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER, AUDITOR -> {
          // Not plant-scoped for request creation (spec gate); must go through a bound
          // workorder as the assigned executor.
        }
        default -> {
        }
      }
      throw new RequestForbiddenException();
    }
    // Standalone CONSUMABLE (no workorder, no machine): allow any user with a non-empty
    // plant scope — there is no bound target to scope against (FR-140, no machine binding).
    if (resolved.workOrderId() == null && resolved.machineId() == null) {
      var scope = scopes.derive(user);
      if (scope.plantIds() != null && !scope.plantIds().isEmpty()) {
        return;
      }
    }
    throw new RequestForbiddenException();
  }

  /**
   * Leader scope. For a bound machine: SECTION_LEADER is group-in-scope only (FR-103
   * parity); MAINTENANCE_LEADER/MANAGER_MAINTENANCE may act on group-in-scope OR plant
   * scope; SUPER_ADMIN is unrestricted. A {@code null} machine (unbound CONSUMABLE
   * request) has no target to scope against — any non-empty scope dimension suffices.
   */
  private boolean isInScopeLeader(AuthenticatedUser user, MachineEntity machine) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        return true;
      }
      case SECTION_LEADER -> {
        if (machine == null) {
          var scope = scopes.derive(user);
          return !scope.machineGroupIds().isEmpty() || !scope.activeTeamIds().isEmpty();
        }
        return groupInScope(scopes.derive(user), machine);
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        var scope = scopes.derive(user);
        if (machine == null) {
          return scope.plantIds() != null && !scope.plantIds().isEmpty();
        }
        var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
        return groupInScope(scope, machine) || plantInScope;
      }
      default -> {
        return false;
      }
    }
  }

  /**
   * Transition actor gate (FR-141, story 12-2). ACK/PROCESSING/READY/PURCHASE_REQUESTED/
   * PART_RECEIVED are INVENTORY_MAINTENANCE/STOREKEEPER (or any in-scope leader as a
   * fallback); PICKED_UP/CLOSED require the workorder's section leader in scope (or
   * SUPER_ADMIN). The gate is authoritative — the rego only does role-level default-deny.
   * A null machine (unbound CONSUMABLE) is allowed for inventory roles and in-scope leaders.
   */
  private void requireTransitionAccess(AuthenticatedUser user, SparepartRequestEntity entity,
      SparepartRequestStatus toStatus, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (isInventoryAction(toStatus)) {
      if (user.applicationRole() == ApplicationRole.INVENTORY_MAINTENANCE
          || user.applicationRole() == ApplicationRole.STOREKEEPER) {
        return;
      }
      if (isInScopeLeader(user, machine)) {
        return;
      }
      throw new RequestForbiddenException();
    }
    // PICKED_UP / CLOSED: the workorder's section leader in scope (or SUPER_ADMIN above).
    if (machine == null || !isInScopeLeader(user, machine)) {
      throw new RequestForbiddenException();
    }
  }

  /** Inventory/stores-driven actions (FR-141): ACK/PROCESSING/READY/PURCHASE_REQUESTED/PART_RECEIVED. */
  private static boolean isInventoryAction(SparepartRequestStatus toStatus) {
    return toStatus == SparepartRequestStatus.ACKED
        || toStatus == SparepartRequestStatus.PROCESSING
        || toStatus == SparepartRequestStatus.READY
        || toStatus == SparepartRequestStatus.PURCHASE_REQUESTED
        || toStatus == SparepartRequestStatus.PART_RECEIVED;
  }

  /** MRE recording is inventory/stores-driven (FR-145). */
  private void requireInventoryAccess(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (user.applicationRole() == ApplicationRole.INVENTORY_MAINTENANCE
        || user.applicationRole() == ApplicationRole.STOREKEEPER) {
      return;
    }
    if (isInScopeLeader(user, machine)) {
      return;
    }
    throw new RequestForbiddenException();
  }

  /**
   * AD-5: after a status-affecting request transition bound to a workorder, recompute
   * the workorder's derived ON_PROCUREMENT state via the maintenance application service.
   * {@code recomputeProcurementState} is idempotent and only applies to
   * IN_PROGRESS/ON_PROCUREMENT workorders, so calling it unconditionally is safe; it is a
   * no-op otherwise. MRE does not change readiness and never reaches this path.
   */
  private void recomputeProcurement(SparepartRequestEntity entity) {
    if (entity.getWorkOrderId() != null) {
      workOrderService.recomputeProcurementState(entity.getWorkOrderId());
    }
  }

  /**
   * Resolve the machine a request is scoped against for plant/group access checks: the
   * request's own machine when bound, else the bound workorder's machine, else {@code null}
   * (unbound CONSUMABLE — inventory roles may act, leaders need a non-empty plant scope).
   */
  private MachineEntity machineForRequest(SparepartRequestEntity entity) {
    if (entity.getMachineId() != null) {
      return machine(entity.getMachineId());
    }
    if (entity.getWorkOrderId() != null) {
      var workOrder = workOrders.findById(entity.getWorkOrderId()).orElseThrow(RequestNotFoundException::new);
      if (workOrder.getMachineId() != null) {
        return machine(workOrder.getMachineId());
      }
    }
    return null;
  }

  private UUID plantIdOf(MachineEntity machine) {
    return machine != null ? machine.getPlant().getId() : null;
  }

  private static String normalizeNote(String note) {
    if (note == null) {
      return null;
    }
    var trimmed = note.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_NOTE_LENGTH) {
      throw new RequestValidationException(Map.of("note",
          "Note must be between 1 and " + MAX_NOTE_LENGTH + " characters."));
    }
    return trimmed;
  }

  private static String normalizeMreCode(String mreCode) {
    if (mreCode == null) {
      return null;
    }
    var trimmed = mreCode.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isExecutor(AuthenticatedUser user, WorkOrderEntity workOrder) {
    return workOrder.getAssignedTechnicianId() != null
        && UUID.fromString(user.id()).equals(workOrder.getAssignedTechnicianId())
        && (user.applicationRole() == ApplicationRole.TECHNICIAN
            || user.applicationRole() == ApplicationRole.STAFF_MAINTENANCE);
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  private boolean plantInScope(OperationalScope scope, MachineEntity machine) {
    return scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
  }

  private void validatePriceEntry(UUID estPriceId) {
    if (estPriceId != null && !priceEntries.existsById(estPriceId)) {
      throw new PriceEntryNotFoundException();
    }
  }

  /** FR-144: an unknown/absent material code starts the request in PENDING_COMPLETION. */
  private SparepartRequestStatus resolveStatus(SparepartRequestType requestType, String materialCode) {
    if (requestType == SparepartRequestType.SERVICE_EXTERNAL) {
      // External services have no material code and no stock flow — always REQUESTED.
      return SparepartRequestStatus.REQUESTED;
    }
    var code = normalizeMaterialCode(materialCode);
    if (code == null) {
      return SparepartRequestStatus.PENDING_COMPLETION;
    }
    return spareparts.findByMaterialCodeIgnoreCase(code).isPresent()
        ? SparepartRequestStatus.REQUESTED
        : SparepartRequestStatus.PENDING_COMPLETION;
  }

  /**
   * Resolve the sparepart the request refers to (FR-140): a material-code match wins over
   * an explicit sparepart id. SPAREPART requests must reference an ELECTRIC/MECHANIC
   * category sparepart on the target machine — a sparepart from another machine (material
   * codes are global) is a cross-machine mismatch.
   */
  private SparepartEntity resolveSparepart(String materialCode, UUID explicitSparepartId,
      SparepartRequestType requestType, UUID machineId) {
    SparepartEntity resolved = null;
    var code = normalizeMaterialCode(materialCode);
    if (code != null) {
      var match = spareparts.findByMaterialCodeIgnoreCase(code);
      if (match.isPresent()) {
        resolved = match.get();
      }
    }
    if (explicitSparepartId != null) {
      var explicit = spareparts.findById(explicitSparepartId).orElseThrow(SparepartNotFoundException::new);
      if (resolved != null && !resolved.getId().equals(explicit.getId())) {
        throw new RequestValidationException(
            Map.of("sparepartId", "sparepartId does not match the material code."));
      }
      resolved = explicit;
    }
    if (resolved == null) {
      return null;
    }
    if (requestType == SparepartRequestType.SPAREPART) {
      if (!isElectricOrMechanic(resolved.getCategory())) {
        throw new RequestValidationException(
            Map.of("requestType", "SPAREPART requires an ELECTRIC or MECHANIC sparepart."));
      }
      if (machineId != null && !machineId.equals(resolved.getMachine().getId())) {
        throw new RequestValidationException(
            Map.of("sparepartId", "The sparepart does not belong to the selected machine."));
      }
    }
    return resolved;
  }

  private boolean isElectricOrMechanic(SparepartTaxonomyEntity category) {
    return category != null && SPAREPART_CATEGORY_CODES.contains(category.getCode());
  }

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(WorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
  }

  private MachineEntity machine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
  }

  private static String entityLabel(SparepartRequestEntity entity) {
    return entity.getRequestType().name() + " "
        + (entity.getMaterialCode() != null ? entity.getMaterialCode() : "new");
  }

  private Map<String, Object> requestValues(SparepartRequestEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId());
    values.put("requestType", entity.getRequestType().name());
    values.put("workOrderId", entity.getWorkOrderId());
    values.put("machineId", entity.getMachineId());
    values.put("materialCode", entity.getMaterialCode());
    values.put("quantity", entity.getQuantity());
    values.put("status", entity.getStatus().name());
    return values;
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String normalizeUrl(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    var trimmed = url.trim();
    try {
      var uri = new URI(trimmed);
      if (uri.getScheme() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
        throw new RequestValidationException(Map.of("purchaseReferenceUrl", "Purchase reference URL must be http(s)."));
      }
    } catch (URISyntaxException exception) {
      throw new RequestValidationException(Map.of("purchaseReferenceUrl", "Purchase reference URL is malformed."));
    }
    return trimmed;
  }

  private static String normalizeMaterialCode(String materialCode) {
    if (materialCode == null) {
      return null;
    }
    var trimmed = materialCode.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  // -------------------------------------------------------------------------
  // Command & exceptions
  // -------------------------------------------------------------------------

  public record RequestPage(List<SparepartRequest> items, long total, int page, int size) {
  }

  public record CreateRequestCommand(SparepartRequestType requestType, String workOrderId, UUID machineId,
      UUID sparepartId, String materialCode, Integer quantity, UUID estPriceId, BigDecimal estUnitPrice,
      String purchaseReferenceUrl, String notes) {
  }

  /** Story 12-2 status transition command (FR-141). */
  public record TransitionCommand(SparepartRequestStatus toStatus, String note) {
  }

  /** Story 12-2 manual MRE code command (FR-145). */
  public record MreCommand(String mreCode, String note) {
  }

  /** Story 12-3 approval command (FR-142): optional note attached to the timeline/audit. */
  public record ApproveCommand(String note) {
  }

  public static class RequestForbiddenException extends RuntimeException {
  }

  /** Story 12-3: requester attempting to approve their own request → 403 (AD-16, NFR-P2-5). */
  public static class SelfApprovalForbiddenException extends RuntimeException {
  }

  /** Story 12-2: invalid/terminal state transition or MRE in the wrong state → 409 (FR-141). */
  public static class InvalidRequestStateTransitionException extends RuntimeException {
  }

  /** Story 12-2: unknown request id → 404 (mirrors WorkOrderExceptionHandler codes). */
  public static class RequestNotFoundException extends RuntimeException {
  }

  public static class WorkOrderNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class SparepartNotFoundException extends RuntimeException {
  }

  public static class PriceEntryNotFoundException extends RuntimeException {
  }

  public static class RequestValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public RequestValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
