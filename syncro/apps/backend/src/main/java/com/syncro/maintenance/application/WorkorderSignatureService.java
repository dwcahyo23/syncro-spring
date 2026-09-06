package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.SignatureUseEntity;
import com.syncro.auth.infrastructure.SignatureUseRepository;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
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
 * PENDING_REVIEW/CLOSED workorder by uploading a signature image to Garage and recording
 * the object key + signer identity in the {@code signature_uses} table with
 * {@code subject_type='WORK_ORDER'} (blueprint I1/DP4, story 15-1 — replaces the legacy
 * {@code workorder_signatures} table). One signature per workorder — the
 * {@code uq_signature_uses_work_order} partial unique constraint surfaces as a 409 on
 * duplicate approve. The read gate is any-authenticated (print-report posture).
 *
 * <p>Story 22-3 enrichment: when the approve carries an optional {@code signatureId}
 * referencing a stored {@code user_signatures} row, the use row is enriched with the
 * signature reference (id + bucket/key/sha256 copied at signing time) plus the request's
 * ip and user-agent. Without a {@code signatureId} the behavior is byte-identical to the
 * pre-22-3 contract (null reference columns). The audit type stays
 * {@code WORKORDER_SIGNATURE} for this flow (design note 22-3).
 *
 * <p>Scope gate mirrors the workorder-report/evidence pattern: in-scope leader
 * (SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE, SUPER_ADMIN) or assigned
 * executor.
 */
@Service
public class WorkorderSignatureService {

  private static final String SIGNATURE_MODULE = "maintenance";
  private static final String SUBJECT_TYPE_WORK_ORDER = "WORK_ORDER";
  private static final String APPROVE_ACTION = "APPROVE_WORKORDER";

  private final WorkOrderRepository workOrders;
  private final MachineRepository machines;
  private final SignatureUseRepository signatureUses;
  private final UserSignatureRepository userSignatures;
  private final AuthUserRepository users;
  private final ObjectStorageService objectStorage;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkorderSignatureService(WorkOrderRepository workOrders, MachineRepository machines,
      SignatureUseRepository signatureUses, UserSignatureRepository userSignatures,
      AuthUserRepository users, ObjectStorageService objectStorage,
      OperationalScopeService scopes, AuditLogWriter auditLog, Clock clock) {
    this.workOrders = workOrders;
    this.machines = machines;
    this.signatureUses = signatureUses;
    this.userSignatures = userSignatures;
    this.users = users;
    this.objectStorage = objectStorage;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Approves a PENDING_REVIEW/CLOSED workorder by recording the signature use. Gate:
   * in-scope leader/SPV or SUPER_ADMIN. Returns the persisted signature result for the
   * controller to map.
   *
   * @param command when {@code signatureId} is set, the stored signature reference is
   *                resolved and copied onto the use row (404 when unknown, 403 when it
   *                belongs to another user); ip/user-agent are captured only on that
   *                enriched path, and the client {@code signatureObjectKey} is ignored
   * @throws SignatureForbiddenException      when the user is not authorized or the
   *                                          referenced signature belongs to another user
   * @throws WorkorderNotTerminalException    when the WO is not PENDING_REVIEW or CLOSED
   * @throws SignatureAlreadyExistsException  when the WO already has a signature
   * @throws SignatureValidationException     when the object key or signer identity is invalid
   * @throws SignatureNotFoundException       when the referenced user signature does not exist
   */
  @Transactional
  public SignatureResult approve(AuthenticatedUser user, String workOrderId, ApproveSignatureCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireLeaderAccess(user, machine);

    if (entity.getStatus() != com.syncro.maintenance.domain.workorder.WorkOrderStatus.PENDING_REVIEW
        && entity.getStatus() != com.syncro.maintenance.domain.workorder.WorkOrderStatus.CLOSED) {
      throw new WorkorderNotTerminalException();
    }

    if (signatureUses.existsBySubjectTypeAndSubjectId(SUBJECT_TYPE_WORK_ORDER, workOrderId)) {
      throw new SignatureAlreadyExistsException();
    }

    var signerIdentity = normalizeSignerIdentity(user, command.signerIdentity());

    // Story 22-3: an optional stored-signature reference enriches the use row; the
    // legacy path (no signatureId) keeps null reference columns byte-identically.
    // Either-or: without a signatureId the client object key is required (review 22-3 P9).
    var stored = command.signatureId() == null ? null
        : userSignatures.findById(command.signatureId())
            .orElseThrow(SignatureNotFoundException::new);
    if (stored != null && !stored.getUserId().equals(UUID.fromString(user.id()))) {
      // Review 22-3 P1: a signature evidences ITS owner's approval — a leader may not
      // attach another user's stored signature to their own approve.
      throw new SignatureForbiddenException();
    }
    var enriched = stored != null;
    var objectKey = enriched ? stored.getObjectKey() : normalizeObjectKey(command.signatureObjectKey());

    var now = Instant.now(clock);
    SignatureUseEntity saved;
    try {
      saved = signatureUses.saveAndFlush(new SignatureUseEntity(
          UUID.randomUUID(), UUID.fromString(user.id()), enriched ? stored.getId() : null,
          SIGNATURE_MODULE, SUBJECT_TYPE_WORK_ORDER, workOrderId, APPROVE_ACTION, null,
          enriched ? stored.getBucket() : null,
          objectKey,
          enriched ? stored.getSha256() : null, null, null,
          enriched ? truncateIp(command.ipAddress()) : null,
          enriched ? command.userAgent() : null, now, now));
    } catch (DataIntegrityViolationException exception) {
      // Concurrent duplicate approve: the pre-check passed but the unique constraint
      // (uq_signature_uses_work_order) rejected the insert — surface as 409.
      if (isUniqueWorkOrderViolation(exception)) {
        throw new SignatureAlreadyExistsException();
      }
      throw exception;
    }

    var values = new LinkedHashMap<String, Object>();
    values.put("signatureObjectKey", objectKey);
    values.put("signerIdentity", signerIdentity);
    values.put("subjectType", SUBJECT_TYPE_WORK_ORDER);
    if (enriched) {
      values.put("signatureId", stored.getId());
      values.put("signatureBucket", stored.getBucket());
      // Review 22-3 P2: pre-V16 rows carry a NULL sha256 — Map.copyOf rejects null
      // values, so only put what exists (a valid approve must never 500).
      if (stored.getSha256() != null) {
        values.put("signatureSha256", stored.getSha256());
      }
    }
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORKORDER_SIGNATURE,
        saved.getId(), workOrderId, machine.getPlant().getId(), null, Map.copyOf(values), null));

    return new SignatureResult(saved.getId(), saved.getSignatureObjectKey(), signerIdentity,
        saved.getSignerId(), saved.getSignedAt());
  }

  /**
   * Reads the signature for a workorder, if present. Any authenticated user. The signer
   * identity (print block display) is resolved at read time from the signature use's
   * signer — display_name falling back to login_identifier — because the blueprint
   * signature_uses table carries no stored identity column (DP4, story 15-1).
   */
  @Transactional(readOnly = true)
  public SignatureResult getSignature(String workOrderId) {
    return signatureUses.findBySubjectTypeAndSubjectId(SUBJECT_TYPE_WORK_ORDER, workOrderId)
        .map(this::toResult)
        .orElse(null);
  }

  private SignatureResult toResult(SignatureUseEntity entity) {
    var identity = entity.getSignerId() == null ? null
        : users.findById(entity.getSignerId())
            .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                ? u.getDisplayName() : u.getLoginIdentifier())
            .orElse(null);
    return new SignatureResult(entity.getId(), entity.getSignatureObjectKey(), identity,
        entity.getSignerId(), entity.getSignedAt());
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

  /** ip_address is VARCHAR(64) — a longer (spoofed) XFF hop is truncated, never rejected. */
  private static String truncateIp(String ipAddress) {
    if (ipAddress == null) {
      return null;
    }
    return ipAddress.length() > 64 ? ipAddress.substring(0, 64) : ipAddress;
  }

  /** Walks the cause chain for the workorder-signature unique constraint violation. */
  private static boolean isUniqueWorkOrderViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
          && "uq_signature_uses_work_order".equalsIgnoreCase(constraint.getConstraintName())) {
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

  public record ApproveSignatureCommand(String signatureObjectKey, String signerIdentity,
      UUID signatureId, String ipAddress, String userAgent) {

    /** Legacy shape: no stored-signature reference, no request metadata. */
    public ApproveSignatureCommand(String signatureObjectKey, String signerIdentity) {
      this(signatureObjectKey, signerIdentity, null, null, null);
    }
  }

  public record SignatureResult(UUID id, String signatureObjectKey, String signerIdentity,
      UUID signedBy, Instant signedAt) {
  }

  public static class SignatureForbiddenException extends RuntimeException {
  }

  public static class SignatureNotFoundException extends RuntimeException {
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
