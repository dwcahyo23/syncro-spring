package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.config.WorkorderEvidenceProperties;
import com.syncro.maintenance.preventive.domain.ScheduleStatus;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleAttachmentRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PreventiveScheduleRepository;
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
 * Preventive schedule evidence (FR-132, AD-10, story 11-2). Mirrors
 * {@code WorkOrderEvidenceService} exactly: object-key-only persistence in Garage under
 * {@code preventive/{scheduleId}/{attachmentId}/{uuid}.{ext}}, files JPEG/PNG/WebP/PDF
 * (≤10 MB configurable via {@link WorkorderEvidenceProperties}), PostgreSQL stores only
 * the object key, replace/delete removes the previous Garage object. The access gate is
 * scoped-completion (same as checklist submit) — reads are any-authenticated.
 */
@Service
public class PreventiveEvidenceService {

  private final PreventiveScheduleRepository schedules;
  private final PreventiveScheduleAttachmentRepository attachments;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final ObjectStorageService objectStorage;
  private final WorkorderEvidenceProperties properties;
  private final com.syncro.org.application.OperationalScopeService scopes;
  private final com.syncro.machine.infrastructure.MachineRepository machines;

  public PreventiveEvidenceService(PreventiveScheduleRepository schedules,
      PreventiveScheduleAttachmentRepository attachments, AuditLogWriter auditLog, Clock clock,
      ObjectStorageService objectStorage, WorkorderEvidenceProperties properties,
      com.syncro.org.application.OperationalScopeService scopes,
      com.syncro.machine.infrastructure.MachineRepository machines) {
    this.schedules = schedules;
    this.attachments = attachments;
    this.auditLog = auditLog;
    this.clock = clock;
    this.objectStorage = objectStorage;
    this.properties = properties;
    this.scopes = scopes;
    this.machines = machines;
  }

  @Transactional
  public AttachmentView create(AuthenticatedUser user, UUID scheduleId, EvidenceCommand command) {
    var schedule = loadSchedule(scheduleId);
    requireAccess(user, schedule);
    validate(command);

    var attachmentId = UUID.randomUUID();
    var objectKey = buildKey(scheduleId, attachmentId, command.contentType());
    storeObject(objectKey, command.data(), command.contentType());
    var now = Instant.now(clock);
    var saved = attachments.saveAndFlush(new PreventiveScheduleAttachmentEntity(attachmentId, scheduleId,
        command.filename().trim(), command.contentType().trim(), objectKey, command.data().length,
        UUID.fromString(user.id()), now, null));

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PREVENTIVE_ATTACHMENT,
        saved.getId(), "schedule " + scheduleId, null, null, attachmentValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public AttachmentView replace(AuthenticatedUser user, UUID scheduleId, UUID attachmentId,
      EvidenceCommand command) {
    var schedule = loadSchedule(scheduleId);
    requireAccess(user, schedule);

    var attachment = attachments.findByIdAndScheduleId(attachmentId, scheduleId)
        .orElseThrow(EvidenceAttachmentNotFoundException::new);
    if (!canDeleteEvidence(user, schedule, attachment)) {
      throw new EvidenceForbiddenException();
    }
    validate(command);

    var previousKey = attachment.getObjectKey();
    var newKey = buildKey(scheduleId, attachmentId, command.contentType());
    deleteObject(previousKey);
    storeObject(newKey, command.data(), command.contentType());
    attachment.replace(command.filename().trim(), command.contentType().trim(), newKey,
        command.data().length, Instant.now(clock));
    var saved = attachments.saveAndFlush(attachment);

    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PREVENTIVE_ATTACHMENT,
        saved.getId(), "schedule " + scheduleId, null,
        Map.<String, Object>of("objectKey", previousKey), attachmentValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID scheduleId, UUID attachmentId) {
    var schedule = loadSchedule(scheduleId);
    requireAccess(user, schedule);

    var attachment = attachments.findByIdAndScheduleId(attachmentId, scheduleId)
        .orElseThrow(EvidenceAttachmentNotFoundException::new);
    if (!canDeleteEvidence(user, schedule, attachment)) {
      throw new EvidenceForbiddenException();
    }
    var previousKey = attachment.getObjectKey();
    deleteObject(previousKey);
    attachments.delete(attachment);

    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.PREVENTIVE_ATTACHMENT,
        attachmentId, "schedule " + scheduleId, null,
        Map.<String, Object>of("objectKey", previousKey), null, null));
  }

  /** Evidence delete gate: the uploader, an in-scope leader, or SUPER_ADMIN (I/O matrix). */
  private boolean canDeleteEvidence(AuthenticatedUser user, PreventiveScheduleEntity schedule,
      PreventiveScheduleAttachmentEntity attachment) {
    if (user.applicationRole() == com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN) {
      return true;
    }
    if (UUID.fromString(user.id()).equals(attachment.getUploadedBy())) {
      return true;
    }
    var machine = machines.findByIdWithPlantAndGroup(schedule.getMachineId())
        .orElseThrow(EvidenceScheduleNotFoundException::new);
    var scope = scopes.derive(user);
    return switch (user.applicationRole()) {
      case SECTION_LEADER -> scope.machineGroupIds().contains(machine.getMachineGroup().getId())
          || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
        yield scope.machineGroupIds().contains(machine.getMachineGroup().getId())
            || scope.activeTeamIds().contains(machine.getMachineGroup().getId()) || plantInScope;
      }
      default -> false;
    };
  }

  @Transactional(readOnly = true)
  public List<AttachmentView> list(UUID scheduleId) {
    loadSchedule(scheduleId);
    return attachments.findByScheduleIdOrderByCreatedAtAsc(scheduleId).stream()
        .map(this::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public AttachmentView get(UUID scheduleId, UUID attachmentId) {
    loadSchedule(scheduleId);
    return toView(attachments.findByIdAndScheduleId(attachmentId, scheduleId)
        .orElseThrow(EvidenceAttachmentNotFoundException::new));
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
      fieldErrors.put("contentType", "Content type must be at most 100 characters.");
    }
    if (command.data() == null || command.data().length == 0) {
      fieldErrors.put("data", "Attachment file must not be empty.");
    } else if (command.data().length > properties.maxBytes()) {
      fieldErrors.put("data", "Attachment file exceeds the maximum allowed size.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new EvidenceValidationException(fieldErrors);
    }
  }

  private void requireAccess(AuthenticatedUser user, PreventiveScheduleEntity schedule) {
    if (user.applicationRole() == com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var machine = machines.findByIdWithPlantAndGroup(schedule.getMachineId())
        .orElseThrow(EvidenceScheduleNotFoundException::new);
    var scope = scopes.derive(user);
    var plantInScope = scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
    if (!plantInScope && !scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        && !scope.activeTeamIds().contains(machine.getMachineGroup().getId())) {
      throw new EvidenceForbiddenException();
    }
  }

  private PreventiveScheduleEntity loadSchedule(UUID scheduleId) {
    return schedules.findById(scheduleId).orElseThrow(EvidenceScheduleNotFoundException::new);
  }

  private String buildKey(UUID scheduleId, UUID attachmentId, String contentType) {
    return "preventive/" + scheduleId + "/" + attachmentId + "/" + UUID.randomUUID() + "." + extension(contentType);
  }

  private static String extension(String contentType) {
    return switch (contentType.trim().toLowerCase(Locale.ROOT)) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      case "application/pdf" -> "pdf";
      default -> "bin";
    };
  }

  private AttachmentView toView(PreventiveScheduleAttachmentEntity entity) {
    return new AttachmentView(entity.getId(), entity.getScheduleId(), entity.getFilename(), entity.getContentType(),
        entity.getObjectKey(), entity.getSizeBytes(), entity.getUploadedBy(), entity.getCreatedAt(),
        entity.getUpdatedAt(), presignedGetUrl(entity.getObjectKey()));
  }

  private Map<String, Object> attachmentValues(PreventiveScheduleAttachmentEntity attachment) {
    var values = new HashMap<String, Object>();
    values.put("id", attachment.getId());
    values.put("scheduleId", attachment.getScheduleId());
    values.put("filename", attachment.getFilename());
    values.put("contentType", attachment.getContentType());
    values.put("objectKey", attachment.getObjectKey());
    values.put("sizeBytes", attachment.getSizeBytes());
    return values;
  }

  private String presignedGetUrl(String key) {
    try {
      return objectStorage.presignGetUrl(key);
    } catch (ObjectStorageException exception) {
      throw new EvidenceStorageException(exception);
    }
  }

  private void deleteObject(String key) {
    try {
      objectStorage.delete(key);
    } catch (ObjectStorageException exception) {
      throw new EvidenceStorageException(exception);
    }
  }

  private void storeObject(String key, byte[] data, String contentType) {
    try {
      objectStorage.store(key, data, contentType);
    } catch (ObjectStorageException exception) {
      throw new EvidenceStorageException(exception);
    }
  }

  public record EvidenceCommand(String filename, String contentType, byte[] data) {
  }

  public record AttachmentView(UUID id, UUID scheduleId, String filename, String contentType,
      String objectKey, long sizeBytes, UUID uploadedBy, Instant createdAt, Instant updatedAt,
      String presignedUrl) {
  }

  public static class EvidenceValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public EvidenceValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new HashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class EvidenceScheduleNotFoundException extends RuntimeException {
  }

  public static class EvidenceForbiddenException extends RuntimeException {
  }

  public static class EvidenceAttachmentNotFoundException extends RuntimeException {
  }

  public static class EvidenceStorageException extends RuntimeException {
    public EvidenceStorageException(Throwable cause) {
      super(cause);
    }
  }
}