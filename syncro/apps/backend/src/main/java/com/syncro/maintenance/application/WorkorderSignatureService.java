package com.syncro.maintenance.application;

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
import com.syncro.maintenance.infrastructure.db.WorkorderSignatureEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderSignatureRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder signature approval (story 14-3, FR-175). An in-scope leader/SPV approves a
 * DONE/CLOSED workorder by uploading a signature image to Garage and recording the object
 * key + signer identity in the {@code workorder_signatures} table (V66). One signature per
 * workorder — the unique constraint on {@code work_order_id} surfaces as a 409 on duplicate
 * approve. The read gate is any-authenticated (print-report posture).
 *
 * <p>Scope gate mirrors the workorder-report/evidence pattern: in-scope leader (SECTION_LEADER,
 * MAINTENANCE_LEADER, MANAGER_MAINTENANCE, SUPER_ADMIN) or assigned executor.
 */
@Service
public class WorkorderSignatureService {

  private static final String SIGNATURE_PREFIX = "workorders/";
  private static final String SIGNATURE_SUFFIX = "/signature/";

  private final WorkOrderRepository workOrders;
  private final MachineRepository machines;
  private final WorkorderSignatureRepository signatures;
  private final ObjectStorageService objectStorage;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkorderSignatureService(WorkOrderRepository workOrders, MachineRepository machines,
      WorkorderSignatureRepository signatures, ObjectStorageService objectStorage,
      OperationalScopeService scopes, AuditLogWriter auditLog, Clock clock) {
    this.workOrders = workOrders;
    this.machines = machines;
    this.signatures = signatures;
    this.objectStorage = objectStorage;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Approves a DONE/CLOSED workorder by recording the signature. Gate: in-scope leader/SPV
   * or SUPER_ADMIN. Returns the persisted signature entity for the controller to map.
   *
   * @throws SignatureForbiddenException      when the user is not authorized
   * @throws WorkorderNotTerminalException    when the WO is not DONE or CLOSED
   * @throws SignatureAlreadyExistsException  when the WO already has a signature
   * @throws SignatureValidationException     when the object key or signer identity is invalid
   */
  @Transactional
  public SignatureResult approve(AuthenticatedUser user, String workOrderId, ApproveSignatureCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireLeaderAccess(user, machine);

    if (entity.getStatus() != com.syncro.maintenance.domain.workorder.WorkOrderStatus.DONE
        && entity.getStatus() != com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED) {
      throw new WorkorderNotTerminalException();
    }

    if (signatures.existsByWorkOrderId(workOrderId)) {
      throw new SignatureAlreadyExistsException();
    }

    var objectKey = normalizeObjectKey(command.signatureObjectKey());
    var signerIdentity = normalizeSignerIdentity(user, command.signerIdentity());

    var now = Instant.now(clock);
    WorkorderSignatureEntity saved;
    try {
      saved = signatures.saveAndFlush(new WorkorderSignatureEntity(
          UUID.randomUUID(), workOrderId, objectKey, signerIdentity,
          UUID.fromString(user.id()), now, now, now));
    } catch (DataIntegrityViolationException exception) {
      // Concurrent duplicate approve: the pre-check passed but the unique constraint
      // (uq_workorder_signatures_work_order) rejected the insert — surface as 409.
      if (isUniqueWorkOrderViolation(exception)) {
        throw new SignatureAlreadyExistsException();
      }
      throw exception;
    }

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORKORDER_SIGNATURE,
        saved.getId(), workOrderId, machine.getPlant().getId(), null,
        Map.<String, Object>of("signatureObjectKey", objectKey, "signerIdentity", signerIdentity), null));

    return new SignatureResult(saved.getId(), saved.getSignatureObjectKey(), saved.getSignerIdentity(),
        saved.getSignedBy(), saved.getSignedAt());
  }

  /** Reads the signature for a workorder, if present. Any authenticated user. */
  @Transactional(readOnly = true)
  public SignatureResult getSignature(String workOrderId) {
    return signatures.findByWorkOrderId(workOrderId)
        .map(e -> new SignatureResult(e.getId(), e.getSignatureObjectKey(), e.getSignerIdentity(),
            e.getSignedBy(), e.getSignedAt()))
        .orElse(null);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(SignatureWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(SignatureMachineNotFoundException::new);
  }

  /** Validates/normalizes the signature object key: non-blank, at most 512 chars. */
  private static String normalizeObjectKey(String objectKey) {
    var fieldErrors = new LinkedHashMap<String, String>();
    var key = objectKey == null ? "" : objectKey.trim();
    if (key.isEmpty()) {
      fieldErrors.put("signatureObjectKey", "Signature object key must not be blank.");
    } else if (key.length() > 512) {
      fieldErrors.put("signatureObjectKey", "Signature object key must be at most 512 characters.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new SignatureValidationException(fieldErrors);
    }
    return key;
  }

  /** Signer identity: defaults to the approver's login identifier; at most 200 chars. */
  private static String normalizeSignerIdentity(AuthenticatedUser user, String signerIdentity) {
    var identity = signerIdentity == null || signerIdentity.isBlank()
        ? user.loginIdentifier() : signerIdentity.trim();
    if (identity.length() > 200) {
      throw new SignatureValidationException(
          Map.of("signerIdentity", "Signer identity must be at most 200 characters."));
    }
    return identity;
  }

  /** Walks the cause chain for the workorder-signature unique constraint violation. */
  private static boolean isUniqueWorkOrderViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
          && "uq_workorder_signatures_work_order".equalsIgnoreCase(constraint.getConstraintName())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  /** Leader/SPV gate: same pattern as WorkOrderReportService/WorkOrderEvidenceService. */
  private void requireLeaderAccess(AuthenticatedUser user, MachineEntity machine) {
    if (isInScopeLeader(user, machine)) {
      return;
    }
    throw new SignatureForbiddenException();
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

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  public String presignSignature(String objectKey) {
    try {
      return objectStorage.presignGetUrl(objectKey);
    } catch (ObjectStorageException exception) {
      throw new SignatureStorageException(exception);
    }
  }

  // -------------------------------------------------------------------------
  // Commands, views & exceptions
  // -------------------------------------------------------------------------

  public record ApproveSignatureCommand(String signatureObjectKey, String signerIdentity) {
  }

  public record SignatureResult(UUID id, String signatureObjectKey, String signerIdentity,
      UUID signedBy, Instant signedAt) {
  }

  public static class SignatureForbiddenException extends RuntimeException {
  }

  public static class SignatureWorkOrderNotFoundException extends RuntimeException {
  }

  public static class SignatureMachineNotFoundException extends RuntimeException {
  }

  public static class WorkorderNotTerminalException extends RuntimeException {
  }

  public static class SignatureAlreadyExistsException extends RuntimeException {
  }

  public static class SignatureStorageException extends RuntimeException {
    public SignatureStorageException(Throwable cause) {
      super(cause);
    }
  }

  public static class SignatureValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public SignatureValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}