package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.WorkorderEvidenceProperties;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderAttachmentRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder evidence & technical drawings (FR-116, story 10-5, AD-10). Object-key-only
 * persistence: bytes live in Garage under {@code workorders/{workOrderId}/{attachmentId}.{ext}},
 * and presigned URLs are short-TTL and never stored. The access gate mirrors
 * {@code WorkOrderService.requireSessionAccess} exactly (in-scope leader OR assigned
 * executor; both sources allowed — attachments are local operational fields, AD-3
 * "preserved"), duplicated here on purpose so the 10.4 service gates stay untouched.
 * Reads (list/get) are any-authenticated — the workorder read posture.
 */
@Service
public class WorkOrderEvidenceService {

  private final WorkOrderRepository workOrders;
  private final MachineRepository machines;
  private final WorkorderAttachmentRepository attachments;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final ObjectStorageService objectStorage;
  private final OperationalScopeService scopes;
  private final WorkorderEvidenceProperties properties;

  public WorkOrderEvidenceService(WorkOrderRepository workOrders, MachineRepository machines,
      WorkorderAttachmentRepository attachments, AuditLogWriter auditLog, Clock clock,
      ObjectStorageService objectStorage, OperationalScopeService scopes,
      WorkorderEvidenceProperties properties) {
    this.workOrders = workOrders;
    this.machines = machines;
    this.attachments = attachments;
    this.auditLog = auditLog;
    this.clock = clock;
    this.objectStorage = objectStorage;
    this.scopes = scopes;
    this.properties = properties;
  }

  /** Uploads a new attachment (POST). Gate: in-scope leader OR assigned executor. */
  @Transactional
  public WorkorderAttachmentView create(AuthenticatedUser user, String workOrderId, EvidenceCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    validate(command);

    var attachmentId = UUID.randomUUID();
    var objectKey = buildKey(workOrderId, attachmentId, command.contentType());
    storeObject(objectKey, command.data(), command.contentType());
    var now = Instant.now(clock);
    var saved = attachments.saveAndFlush(new WorkorderAttachmentEntity(attachmentId, workOrderId,
        command.filename().trim(), command.contentType().trim(), objectKey, command.data().length,
        UUID.fromString(user.id()), now, null));

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORKORDER_ATTACHMENT,
        saved.getId(), workOrderId, machine.getPlant().getId(), null, attachmentValues(saved), null));
    return toView(saved);
  }

  /**
   * Replaces an existing attachment's file (PUT): deletes the previous Garage object
   * before storing the new one (8-4 pattern) so no orphan accumulates. If the store
   * fails after the delete, the row still points at a deleted object — callers retry.
   */
  @Transactional
  public WorkorderAttachmentView replace(AuthenticatedUser user, String workOrderId, UUID attachmentId,
      EvidenceCommand command) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);
    validate(command);

    var attachment = loadAttachment(attachmentId, workOrderId);
    var previousKey = attachment.getObjectKey();
    var newKey = buildKey(workOrderId, attachmentId, command.contentType());
    deleteObject(previousKey);
    storeObject(newKey, command.data(), command.contentType());
    attachment.replace(command.filename().trim(), command.contentType().trim(), newKey,
        command.data().length, Instant.now(clock));
    var saved = attachments.saveAndFlush(attachment);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORKORDER_ATTACHMENT,
        saved.getId(), workOrderId, machine.getPlant().getId(),
        Map.<String, Object>of("objectKey", previousKey), attachmentValues(saved), null));
    return toView(saved);
  }

  /** Deletes an attachment: Garage object first (idempotent), then the row. */
  @Transactional
  public void delete(AuthenticatedUser user, String workOrderId, UUID attachmentId) {
    var entity = loadWorkOrder(workOrderId);
    var machine = loadMachine(entity);
    requireAccess(user, entity, machine);

    var attachment = loadAttachment(attachmentId, workOrderId);
    var previousKey = attachment.getObjectKey();
    deleteObject(previousKey);
    attachments.delete(attachment);

    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.WORKORDER_ATTACHMENT,
        attachmentId, workOrderId, machine.getPlant().getId(),
        Map.<String, Object>of("objectKey", previousKey), null, null));
  }

  /** Lists the workorder's attachments ordered by createdAt asc (any authenticated user). */
  @Transactional(readOnly = true)
  public WorkorderAttachmentsView list(String workOrderId) {
    loadWorkOrder(workOrderId);
    var views = attachments.findByWorkOrderIdOrderByCreatedAtAsc(workOrderId).stream()
        .map(this::toView)
        .toList();
    return new WorkorderAttachmentsView(workOrderId, views);
  }

  /** Gets a single attachment (any authenticated user); 404 when missing or wrong workorder. */
  @Transactional(readOnly = true)
  public WorkorderAttachmentView get(String workOrderId, UUID attachmentId) {
    loadWorkOrder(workOrderId);
    return toView(loadAttachment(attachmentId, workOrderId));
  }

  private void validate(EvidenceCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    var filename = command.filename() == null ? "" : command.filename().trim();
    if (filename.isEmpty()) {
      fieldErrors.put("filename", "Filename must not be blank.");
    } else if (filename.length() > 255) {
      fieldErrors.put("filename", "Filename must be at most 255 characters.");
    }
    var contentType = command.contentType() == null ? "" : command.contentType().trim();
    if (contentType.isEmpty()) {
      fieldErrors.put("contentType", "Content type must not be blank.");
    } else if (contentType.length() > 100) {
      // Mirrors the content_type VARCHAR(100) column (V50) so oversize MIME strings
      // fail validation instead of surfacing as a DataIntegrityViolation 500.
      fieldErrors.put("contentType", "Content type must be at most 100 characters.");
    }
    if (command.data() == null || command.data().length == 0) {
      fieldErrors.put("data", "Attachment file must not be empty.");
    } else if (command.data().length > properties.maxBytes()) {
      fieldErrors.put("data", "Attachment file exceeds the maximum allowed size.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ValidationException(fieldErrors);
    }
  }

  private WorkOrderEntity loadWorkOrder(String workOrderId) {
    return workOrders.findById(workOrderId).orElseThrow(EvidenceWorkOrderNotFoundException::new);
  }

  private MachineEntity loadMachine(WorkOrderEntity workOrder) {
    return machines.findByIdWithPlantAndGroup(workOrder.getMachineId())
        .orElseThrow(EvidenceWorkOrderMachineNotFoundException::new);
  }

  private WorkorderAttachmentEntity loadAttachment(UUID attachmentId, String workOrderId) {
    return attachments.findByIdAndWorkOrderId(attachmentId, workOrderId)
        .orElseThrow(EvidenceAttachmentNotFoundException::new);
  }

  /**
   * Same gate as {@code WorkOrderService.requireSessionAccess} (10.4): in-scope leader
   * OR assigned executor, both sources allowed. Duplicated inline — the 10.4 private
   * gates and constructor stay untouched (existing tests depend on them).
   */
  private void requireAccess(AuthenticatedUser user, WorkOrderEntity entity, MachineEntity machine) {
    if (isInScopeLeader(user, machine) || isExecutor(user, entity)) {
      return;
    }
    throw new EvidenceForbiddenException();
  }

  private boolean isExecutor(AuthenticatedUser user, WorkOrderEntity entity) {
    return entity.getAssignedTechnicianId() != null
        && UUID.fromString(user.id()).equals(entity.getAssignedTechnicianId())
        && (user.applicationRole() == ApplicationRole.TECHNICIAN
            || user.applicationRole() == ApplicationRole.STAFF_MAINTENANCE);
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

  private String buildKey(String workOrderId, UUID attachmentId, String contentType) {
    // Fresh UUID segment per replacement (8-4 pattern): a replace always writes a new
    // object URL so browsers never serve a stale cached file, and the old object is
    // only deleted after the new one is stored (no download window on the same key).
    return "workorders/" + workOrderId + "/" + attachmentId + "/" + UUID.randomUUID() + "." + extension(contentType);
  }

  /**
   * Safe extension from the (already non-blank) content type; anything unknown falls
   * back to {@code bin} — evidence accepts any IANA media type, so the ext is cosmetic.
   */
  private static String extension(String contentType) {
    return switch (contentType.trim().toLowerCase(Locale.ROOT)) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      case "application/pdf" -> "pdf";
      default -> "bin";
    };
  }

  private WorkorderAttachmentView toView(WorkorderAttachmentEntity entity) {
    return new WorkorderAttachmentView(entity.getId(), entity.getWorkOrderId(), entity.getFilename(),
        entity.getContentType(), entity.getObjectKey(), entity.getSizeBytes(), entity.getUploadedBy(),
        entity.getCreatedAt(), entity.getUpdatedAt(), presignedGetUrl(entity.getObjectKey()));
  }

  private Map<String, Object> attachmentValues(WorkorderAttachmentEntity attachment) {
    var values = new HashMap<String, Object>();
    values.put("id", attachment.getId());
    values.put("workOrderId", attachment.getWorkOrderId());
    values.put("filename", attachment.getFilename());
    values.put("contentType", attachment.getContentType());
    values.put("objectKey", attachment.getObjectKey());
    values.put("sizeBytes", attachment.getSizeBytes());
    values.put("uploadedBy", attachment.getUploadedBy());
    return values;
  }

  private String presignedGetUrl(String key) {
    try {
      return objectStorage.presignGetUrl(key);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  private void deleteObject(String key) {
    try {
      objectStorage.delete(key);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  private void storeObject(String key, byte[] data, String contentType) {
    try {
      objectStorage.store(key, data, contentType);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  public record EvidenceCommand(String filename, String contentType, byte[] data) {
  }

  public record WorkorderAttachmentView(UUID id, String workOrderId, String filename, String contentType,
      String objectKey, long sizeBytes, UUID uploadedBy, Instant createdAt, Instant updatedAt,
      String presignedUrl) {
  }

  public record WorkorderAttachmentsView(String workOrderId, List<WorkorderAttachmentView> attachments) {
  }

  public static class ValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class EvidenceForbiddenException extends RuntimeException {
  }

  public static class EvidenceWorkOrderNotFoundException extends RuntimeException {
  }

  public static class EvidenceWorkOrderMachineNotFoundException extends RuntimeException {
  }

  public static class EvidenceAttachmentNotFoundException extends RuntimeException {
  }

  /** Object-storage failure; wraps the SDK cause but never leaks it to callers. */
  public static class StorageException extends RuntimeException {
    public StorageException(Throwable cause) {
      super(cause);
    }
  }
}
