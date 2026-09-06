package com.syncro.auth.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.api.AuthDtos.UserSignatureView;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.UserSignatureEntity;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.config.GarageProperties;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stores and reads the one reusable signature image per user (story 22-3, FR-132/133/175).
 * Upload upserts the single {@code user_signatures} row (V1 {@code uq_user_signatures_user}):
 * bytes → SHA-256 (computed in-memory; {@link ObjectStorageService} has no download method,
 * so the hash must be captured at upload) → Garage store → row replace → best-effort delete
 * of the superseded object AFTER commit (a failed delete leaves an orphan object, never a
 * dangling reference). The plain image bytes are never returned by any endpoint — reads
 * return the reference plus a short-TTL presigned URL.
 *
 * <p>Gates: writes are owner-or-SUPER_ADMIN; reading another user's signature is
 * SUPER_ADMIN-only (service-authoritative; rego {@code user_signature_paths} is the coarse
 * parity gate).
 */
@Service
public class UserSignatureService {

  private static final Logger log = LoggerFactory.getLogger(UserSignatureService.class);
  private static final String CONTENT_TYPE_PATTERN = "image/(jpeg|png|webp|gif)";
  private static final String KEY_PREFIX = "user-signatures/";

  private final UserSignatureRepository signatures;
  private final ObjectStorageService objectStorage;
  private final GarageProperties garage;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public UserSignatureService(UserSignatureRepository signatures, ObjectStorageService objectStorage,
      GarageProperties garage, AuditLogWriter auditLog, Clock clock) {
    this.signatures = signatures;
    this.objectStorage = objectStorage;
    this.garage = garage;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /** Upload outcome: the stored view plus whether this upload created the row (vs replaced). */
  public record StoreResult(UserSignatureView view, boolean created) {
  }

  /**
   * Uploads (or replaces) the signature image for {@code userId}. Gate: the actor is the
   * owner or SUPER_ADMIN. Non-image content type → {@link UnsupportedContentTypeException}
   * (415); empty bytes → {@link ValidationException} (400).
   */
  @Transactional
  public StoreResult store(AuthenticatedUser actor, UUID userId, String contentType, byte[] data) {
    requireWriteAccess(actor, userId);
    validate(contentType, data);

    var normalizedType = contentType.trim().toLowerCase(Locale.ROOT);
    var sha256 = sha256Hex(data);
    var objectKey = KEY_PREFIX + userId + "/" + UUID.randomUUID() + "." + extension(normalizedType);
    storeObject(objectKey, data, normalizedType);

    var now = Instant.now(clock);
    var existing = signatures.findByUserId(userId);
    String supersededKey;
    UserSignatureEntity saved;
    boolean created;
    if (existing.isPresent()) {
      var entity = existing.get();
      supersededKey = entity.getObjectKey();
      entity.replaceContent(garage.bucket(), objectKey, normalizedType, sha256, now);
      saved = signatures.saveAndFlush(entity);
      created = false;
    } else {
      try {
        saved = signatures.saveAndFlush(new UserSignatureEntity(UUID.randomUUID(), userId,
            garage.bucket(), objectKey, normalizedType, sha256, 0, null, now, now));
        created = true;
      } catch (DataIntegrityViolationException race) {
        // Review 22-3 P4: two simultaneous first uploads — uq_user_signatures_user
        // rejected this insert. The transaction is already doomed, so drop the
        // just-stored object (no orphan) and surface a clean 409; the client's
        // retry takes the replace path above.
        deleteQuietly(objectKey);
        throw new SignatureUploadConflictException();
      }
      supersededKey = null;
    }

    var previous = supersededKey == null ? null : Map.<String, Object>of("objectKey", supersededKey);
    var values = new LinkedHashMap<String, Object>();
    values.put("userId", userId);
    values.put("bucket", saved.getBucket());
    values.put("objectKey", saved.getObjectKey());
    values.put("contentType", saved.getContentType());
    values.put("sha256", saved.getSha256());
    auditLog.record(actor, new AuditRecord(supersededKey == null ? AuditAction.CREATE : AuditAction.UPDATE,
        AuditEntityType.SIGNATURE_USE, saved.getId(), "user:" + userId, null, previous,
        Map.copyOf(values), null));

    if (supersededKey != null) {
      deleteSupersededAfterCommit(supersededKey);
    }
    // Review 22-3 P5: a presign failure must not turn a successful store into a 502 —
    // the row is committed and correct; the URL is simply skipped (14-3 null-url precedent).
    return new StoreResult(toView(saved, presignedGetUrlOrNull(saved.getObjectKey())), created);
  }

  /** Reads a user's signature reference + presigned URL. Gate: owner or SUPER_ADMIN. */
  @Transactional(readOnly = true)
  public UserSignatureView get(AuthenticatedUser actor, UUID userId) {
    if (!actor.applicationRole().equals(ApplicationRole.SUPER_ADMIN)
        && !UUID.fromString(actor.id()).equals(userId)) {
      throw new SignatureForbiddenException();
    }
    return signatures.findByUserId(userId)
        .map(entity -> toView(entity, presignedGetUrlOrNull(entity.getObjectKey())))
        .orElseThrow(SignatureNotFoundException::new);
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void requireWriteAccess(AuthenticatedUser actor, UUID userId) {
    if (actor.applicationRole() == ApplicationRole.SUPER_ADMIN
        || UUID.fromString(actor.id()).equals(userId)) {
      return;
    }
    throw new SignatureForbiddenException();
  }

  private static void validate(String contentType, byte[] data) {
    var type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    if (!type.matches(CONTENT_TYPE_PATTERN)) {
      throw new UnsupportedContentTypeException();
    }
    if (data == null || data.length == 0) {
      throw new ValidationException(Map.of("data", "Signature image must not be empty."));
    }
  }

  private static String sha256Hex(byte[] data) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(data);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static String extension(String contentType) {
    return switch (contentType) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      case "image/gif" -> "gif";
      default -> throw new IllegalStateException("Content type already validated");
    };
  }

  /**
   * Best-effort superseded-object delete, deferred to after commit so a rolled-back
   * upload never destroys the still-referenced old object.
   */
  private void deleteSupersededAfterCommit(String supersededKey) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          deleteQuietly(supersededKey);
        }
      });
    } else {
      deleteQuietly(supersededKey);
    }
  }

  private void deleteQuietly(String key) {
    try {
      objectStorage.delete(key);
    } catch (ObjectStorageException exception) {
      // Orphan object, never a dangling reference — the new row already points at the
      // fresh key; a failed supersede delete must not fail the completed upload.
      log.warn("Superseded signature object delete failed key={}", key, exception);
    }
  }

  /** Presign failure skips the URL (null) rather than failing a completed store/read (review 22-3 P5). */
  private String presignedGetUrlOrNull(String key) {
    try {
      return objectStorage.presignGetUrl(key);
    } catch (ObjectStorageException exception) {
      log.warn("Signature presign failed key={}", key, exception);
      return null;
    }
  }

  private void storeObject(String key, byte[] data, String contentType) {
    try {
      objectStorage.store(key, data, contentType);
    } catch (ObjectStorageException exception) {
      throw new StorageException(exception);
    }
  }

  private static UserSignatureView toView(UserSignatureEntity entity, String presignedUrl) {
    return new UserSignatureView(entity.getId(), entity.getUserId(), entity.getBucket(),
        entity.getObjectKey(), entity.getContentType(), entity.getSha256(),
        entity.getCreatedAt(), entity.getUpdatedAt(), presignedUrl);
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class SignatureNotFoundException extends RuntimeException {
  }

  public static class SignatureForbiddenException extends RuntimeException {
  }

  public static class UnsupportedContentTypeException extends RuntimeException {
  }

  /** Concurrent first-upload race on uq_user_signatures_user (review 22-3 P4). */
  public static class SignatureUploadConflictException extends RuntimeException {
  }

  public static class ValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  /** Object-storage failure; wraps the SDK cause but never leaks it to callers. */
  public static class StorageException extends RuntimeException {
    public StorageException(Throwable cause) {
      super(cause);
    }
  }
}
