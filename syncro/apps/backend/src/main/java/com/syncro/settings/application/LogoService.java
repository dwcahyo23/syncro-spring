package com.syncro.settings.application;

import com.syncro.settings.infrastructure.SettingsEntity;
import com.syncro.settings.infrastructure.SettingsRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company logo configuration (story 14-3, FR-175). The logo is stored in Garage under a
 * fixed {@code settings/logo} prefix; the current object key lives in the single-row
 * {@code settings} table (V66) — no separate migration needed. {@code GET} returns a
 * fresh short-TTL presigned URL (null when no logo is configured); {@code PUT} replaces
 * the logo, deleting the previous Garage object (8-4 pattern). The SUPER_ADMIN gate lives
 * in the controller.
 */
@Service
public class LogoService {

  private static final String LOGO_PREFIX = "settings/logo/";
  private static final String CONTENT_TYPE_PATTERN = "image/(jpeg|png|webp)";
  private static final long MAX_BYTES = 5L * 1024L * 1024L;

  private final SettingsRepository settings;
  private final ObjectStorageService objectStorage;
  private final Clock clock;

  public LogoService(SettingsRepository settings, ObjectStorageService objectStorage, Clock clock) {
    this.settings = settings;
    this.objectStorage = objectStorage;
    this.clock = clock;
  }

  /** Returns the current logo presigned URL, or null when no logo is configured. */
  @Transactional(readOnly = true)
  public LogoView get() {
    var entity = loadSettings();
    var key = entity.getLogoObjectKey();
    if (key == null) {
      return new LogoView(null, null);
    }
    return new LogoView(key, presign(key));
  }

  /**
   * Uploads (or replaces) the logo. Replace deletes the previous Garage object before
   * storing the new one (8-4 pattern); the settings row keeps only the new key.
   */
  @Transactional
  public LogoView replace(LogoCommand command) {
    validate(command);
    var entity = loadSettings();
    var previousKey = entity.getLogoObjectKey();
    var newKey = LOGO_PREFIX + UUID.randomUUID() + "." + extension(command.contentType());
    if (previousKey != null) {
      deleteObject(previousKey);
    }
    storeObject(newKey, command.data(), command.contentType());
    entity.setLogoObjectKey(newKey, Instant.now(clock));
    settings.saveAndFlush(entity);
    return new LogoView(newKey, presign(newKey));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private SettingsEntity loadSettings() {
    return settings.findFirstByOrderBySingletonKeyAsc()
        .orElseThrow(LogoNotConfiguredException::new);
  }

  private void validate(LogoCommand command) {
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
          "Content type must be one of image/jpeg, image/png, or image/webp.");
    }
    if (command.data() == null || command.data().length == 0) {
      fieldErrors.put("data", "Logo file must not be empty.");
    } else if (command.data().length > MAX_BYTES) {
      fieldErrors.put("data", "Logo file exceeds the maximum allowed size (5 MB).");
    }
    if (!fieldErrors.isEmpty()) {
      throw new LogoValidationException(fieldErrors);
    }
  }

  private static String extension(String contentType) {
    return switch (contentType.trim().toLowerCase(Locale.ROOT)) {
      case "image/jpeg" -> "jpg";
      case "image/png" -> "png";
      case "image/webp" -> "webp";
      default -> throw new IllegalStateException("Content type already validated");
    };
  }

  private String presign(String key) {
    try {
      return objectStorage.presignGetUrl(key);
    } catch (ObjectStorageException exception) {
      throw new LogoStorageException(exception);
    }
  }

  private void deleteObject(String key) {
    try {
      objectStorage.delete(key);
    } catch (ObjectStorageException exception) {
      throw new LogoStorageException(exception);
    }
  }

  private void storeObject(String key, byte[] data, String contentType) {
    try {
      objectStorage.store(key, data, contentType);
    } catch (ObjectStorageException exception) {
      throw new LogoStorageException(exception);
    }
  }

  // -------------------------------------------------------------------------
  // Commands, views & exceptions
  // -------------------------------------------------------------------------

  public record LogoCommand(String filename, String contentType, byte[] data) {
  }

  public record LogoView(String objectKey, String presignedUrl) {
  }

  public static class LogoValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public LogoValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }

  public static class LogoStorageException extends RuntimeException {
    public LogoStorageException(Throwable cause) {
      super(cause);
    }
  }

  public static class LogoNotConfiguredException extends RuntimeException {
  }
}