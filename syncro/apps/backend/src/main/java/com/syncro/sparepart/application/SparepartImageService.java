package com.syncro.sparepart.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JobScopeService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.config.SparepartImageProperties;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the single global image per sparepart (Story 8-4). Only the bucket-relative object
 * key is persisted (in {@code spareparts.image_object_key}); image bytes live in Garage and
 * presigned URLs are short-TTL and never stored. Replacing an image always deletes the previous
 * object first so no orphan accumulates; if the follow-up store fails the entity is left
 * unchanged (the old reference points at an already-deleted object, so callers retry).
 */
@Service
public class SparepartImageService {
  private static final String JOB_SCOPE_LEVEL = "LEADER";
  private static final String CONTENT_TYPE_PATTERN = "image/(jpeg|png|webp|gif)";

  private final SparepartRepository spareparts;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final JobScopeService jobScopes;
  private final ObjectStorageService objectStorage;
  private final SparepartImageProperties properties;

  public SparepartImageService(SparepartRepository spareparts,
      AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog, Clock clock,
      JobScopeService jobScopes, ObjectStorageService objectStorage, SparepartImageProperties properties) {
    this.spareparts = spareparts;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
    this.jobScopes = jobScopes;
    this.objectStorage = objectStorage;
    this.properties = properties;
  }

  /**
   * Reads the image view without requiring job scope: any plant-scoped user may preview.
   * 404 {@link ImageNotFoundException} when the sparepart has no image yet.
   */
  @Transactional(readOnly = true)
  public SparepartImageView get(AuthenticatedUser user, UUID sparepartId) {
    var sparepart = findScopedSparepart(user, sparepartId);
    var key = sparepart.getImageObjectKey();
    if (key == null) {
      throw new ImageNotFoundException();
    }
    return new SparepartImageView(sparepart.getId(), key, presignedGetUrl(key));
  }

  /**
   * Uploads or replaces the sparepart image. Gate order mirrors patchProcurement (Story 8-2):
   * app-role first, then LEADER-or-above job scope (SUPER_ADMIN bypasses), then plant masking.
   */
  @Transactional
  public SparepartImageView replace(AuthenticatedUser user, UUID sparepartId,
      SparepartImageCommand command) {
    requireMutationRole(user);
    jobScopes.requireLevelOrAbove(user, JOB_SCOPE_LEVEL);
    var sparepart = findScopedSparepart(user, sparepartId);
    validate(command);
    var previousKey = sparepart.getImageObjectKey();
    var newKey = buildKey(sparepartId, command.contentType());
    if (previousKey != null) {
      deleteObject(previousKey);
    }
    storeObject(newKey, command.data(), command.contentType());
    sparepart.updateImageObjectKey(newKey, Instant.now(clock));
    var saved = spareparts.saveAndFlush(sparepart);
    var action = previousKey == null ? AuditAction.CREATE : AuditAction.UPDATE;
    var previous = previousKey == null ? null : Map.<String, Object>of("imageObjectKey", previousKey);
    auditLog.record(user, new AuditRecord(action, AuditEntityType.SPAREPART, sparepartId,
        saved.getCode(), saved.getMachine().getPlant().getId(), previous,
        Map.<String, Object>of("imageObjectKey", newKey)));
    return new SparepartImageView(saved.getId(), newKey, presignedGetUrl(newKey));
  }

  /** Removes the sparepart image; a missing image is an idempotent no-op with no audit. */
  @Transactional
  public void delete(AuthenticatedUser user, UUID sparepartId) {
    requireMutationRole(user);
    jobScopes.requireLevelOrAbove(user, JOB_SCOPE_LEVEL);
    var sparepart = findScopedSparepart(user, sparepartId);
    var previousKey = sparepart.getImageObjectKey();
    if (previousKey == null) {
      return;
    }
    deleteObject(previousKey);
    sparepart.updateImageObjectKey(null, Instant.now(clock));
    var saved = spareparts.saveAndFlush(sparepart);
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.SPAREPART,
        sparepartId, saved.getCode(), saved.getMachine().getPlant().getId(),
        Map.<String, Object>of("imageObjectKey", previousKey), null));
  }

  private void validate(SparepartImageCommand command) {
    var fieldErrors = new LinkedHashMap<String, String>();
    var filename = command.filename() == null ? "" : command.filename().trim();
    if (filename.isEmpty()) {
      fieldErrors.put("filename", "Filename must not be blank.");
    } else if (filename.length() > 255) {
      fieldErrors.put("filename", "Filename must be at most 255 characters.");
    }
    var contentType = command.contentType() == null
        ? "" : command.contentType().trim().toLowerCase(Locale.ROOT);
    if (!contentType.matches(CONTENT_TYPE_PATTERN)) {
      fieldErrors.put("contentType",
          "Content type must be one of image/jpeg, image/png, image/webp, or image/gif.");
    }
    if (command.data() == null || command.data().length == 0) {
      fieldErrors.put("data", "Image file must not be empty.");
    } else if (command.data().length > properties.maxBytes()) {
      fieldErrors.put("data", "Image file exceeds the maximum allowed size.");
    }
    if (!fieldErrors.isEmpty()) {
      throw new ValidationException(fieldErrors);
    }
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new MutationForbiddenException();
    }
  }

  private SparepartEntity findScopedSparepart(AuthenticatedUser user, UUID sparepartId) {
    var sparepart = spareparts.findById(sparepartId).orElseThrow(NotFoundException::new);
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return sparepart;
    }
    var assignedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(assignment -> assignment.getPlantId())
        .toList();
    if (!assignedPlantIds.contains(sparepart.getMachine().getPlant().getId())) {
      throw new NotFoundException();
    }
    return sparepart;
  }

  private String buildKey(UUID sparepartId, String contentType) {
    var extension = switch (contentType.toLowerCase(Locale.ROOT)) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      case "image/gif" -> "gif";
      default -> throw new IllegalStateException("Content type already validated");
    };
    // The uuid segment makes every replacement a fresh object URL, so browsers never
    // serve a stale cached image even though the presigned URL is new.
    return "spareparts/" + sparepartId + "/" + UUID.randomUUID() + "." + extension;
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

  public record SparepartImageCommand(String filename, String contentType, byte[] data) {
  }

  public record SparepartImageView(UUID sparepartId, String objectKey, String presignedUrl) {
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

  public static class MutationForbiddenException extends RuntimeException {
  }

  /** Same 404 used for unknown ids and out-of-plant masking. */
  public static class NotFoundException extends RuntimeException {
  }

  public static class ImageNotFoundException extends RuntimeException {
  }

  /** Object-storage failure; wraps the SDK cause but never leaks it to callers. */
  public static class StorageException extends RuntimeException {
    public StorageException(Throwable cause) {
      super(cause);
    }
  }
}