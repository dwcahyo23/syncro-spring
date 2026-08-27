package com.syncro.sparepart.request.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.request.domain.SparepartRequest;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sparepart request creation (FR-140/FR-143/FR-144, story 12-1). Enforces the type
 * rules: SPAREPART binds a machine + sparepart (electric/mechanic taxonomy), CONSUMABLE
 * has no machine binding, SERVICE_EXTERNAL requires a workorder. A request with an
 * unknown/absent material code starts PENDING_COMPLETION for inventory to finish (12-4).
 * The full state machine, ON_PROCUREMENT wiring, approval and stock are later stories.
 */
@Service
public class SparepartRequestService {

  private static final int MAX_URL_LENGTH = 2048;
  private static final int MAX_MATERIAL_CODE_LENGTH = 64;

  private final SparepartRequestRepository requests;
  private final WorkOrderRepository workOrders;
  private final MachineRepository machines;
  private final SparepartRepository spareparts;
  private final SparepartPriceEntryRepository priceEntries;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public SparepartRequestService(SparepartRequestRepository requests, WorkOrderRepository workOrders,
      MachineRepository machines, SparepartRepository spareparts, SparepartPriceEntryRepository priceEntries,
      AuditLogWriter auditLog, OperationalScopeService scopes, Clock clock) {
    this.requests = requests;
    this.workOrders = workOrders;
    this.machines = machines;
    this.spareparts = spareparts;
    this.priceEntries = priceEntries;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  @Transactional
  public SparepartRequest create(AuthenticatedUser user, CreateRequestCommand command) {
    validate(command);
    var resolved = resolveTarget(user, command);
    requireCreateAccess(user, resolved);

    var now = Instant.now(clock);
    var status = resolveStatus(command.requestType(), command.materialCode());
    var materialCode = normalizeMaterialCode(command.materialCode());
    var sparepartId = resolveSparepartId(materialCode, command.sparepartId());
    var entity = new SparepartRequestEntity(UUID.randomUUID(), command.requestType(), resolved.workOrderId(),
        resolved.machineId(), sparepartId, materialCode, command.quantity().shortValue(),
        command.estPriceId(), command.estUnitPrice(), normalizeUrl(command.purchaseReferenceUrl()),
        status, UUID.fromString(user.id()), now, normalize(command.notes()), now, now);
    var saved = requests.saveAndFlush(entity);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_REQUEST,
        saved.getId(), entityLabel(saved), resolved.plantId(), null, requestValues(saved), null));
    return SparepartRequestMapper.toDomain(saved);
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
    }
    var url = normalizeUrl(command.purchaseReferenceUrl());
    if (url != null && url.length() > MAX_URL_LENGTH) {
      fieldErrors.put("purchaseReferenceUrl", "Purchase reference URL must be at most 2048 characters.");
    }
    var materialCode = normalizeMaterialCode(command.materialCode());
    if (materialCode != null && materialCode.length() > MAX_MATERIAL_CODE_LENGTH) {
      fieldErrors.put("materialCode", "Material code must be at most 64 characters.");
    }
    if (command.estUnitPrice() != null && command.estUnitPrice().signum() < 0) {
      fieldErrors.put("estUnitPrice", "Estimated unit price must be non-negative.");
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
   * Access gate: SUPER_ADMIN exempt; in-scope leader (group/team in scope) OR assigned
   * executor on the bound workorder OR plant access (staff/leader) to the target machine.
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
    }
    if (resolved.machineId() != null) {
      var machine = machine(resolved.machineId());
      var scope = scopes.derive(user);
      if (scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId())) {
        return;
      }
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

  private boolean isInScopeLeader(AuthenticatedUser user, MachineEntity machine) {
    switch (user.applicationRole()) {
      case SUPER_ADMIN -> {
        return true;
      }
      case SECTION_LEADER -> {
        return groupInScope(scopes.derive(user), machine);
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        var scope = scopes.derive(user);
        var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
        return groupInScope(scope, machine) || plantInScope;
      }
      default -> {
        return false;
      }
    }
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

  /** Auto-resolve sparepart_id from material code when it matches; otherwise use the explicit one. */
  private UUID resolveSparepartId(String materialCode, UUID explicitSparepartId) {
    var code = normalizeMaterialCode(materialCode);
    if (code != null) {
      var match = spareparts.findByMaterialCodeIgnoreCase(code);
      if (match.isPresent()) {
        return match.get().getId();
      }
    }
    if (explicitSparepartId != null) {
      var sparepart = spareparts.findById(explicitSparepartId).orElseThrow(SparepartNotFoundException::new);
      return sparepart.getId();
    }
    return null;
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

  public record CreateRequestCommand(SparepartRequestType requestType, String workOrderId, UUID machineId,
      UUID sparepartId, String materialCode, Integer quantity, UUID estPriceId, BigDecimal estUnitPrice,
      String purchaseReferenceUrl, String notes) {
  }

  public static class RequestForbiddenException extends RuntimeException {
  }

  public static class WorkOrderNotFoundException extends RuntimeException {
  }

  public static class MachineNotFoundException extends RuntimeException {
  }

  public static class SparepartNotFoundException extends RuntimeException {
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
