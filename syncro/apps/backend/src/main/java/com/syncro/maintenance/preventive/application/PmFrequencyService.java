package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.preventive.infrastructure.db.PmFrequencyEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmFrequencyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PM frequency master-data service (story 19-1, blueprint F1). Frequencies are
 * global (no plant column) — role gate only, no plant/group scope check.
 *
 * <p>Gates: CREATE/UPDATE require STAFF_MAINTENANCE/SECTION_LEADER/
 * MAINTENANCE_LEADER/MANAGER_MAINTENANCE (SUPER_ADMIN bypass).
 */
@Service
public class PmFrequencyService {

  private static final String FREQUENCY_CODE_UNIQUE_CONSTRAINT = "uq_pm_frequencies_code";

  private final PmFrequencyRepository frequencies;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public PmFrequencyService(PmFrequencyRepository frequencies, AuditLogWriter auditLog, Clock clock) {
    this.frequencies = frequencies;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<FrequencyView> list() {
    return frequencies.findAllByOrderBySortOrderAsc().stream()
        .map(PmFrequencyService::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public FrequencyView get(UUID id) {
    var entity = loadFrequency(id);
    return toView(entity);
  }

  @Transactional
  public FrequencyView create(AuthenticatedUser user, CreateFrequencyCommand command) {
    requireMutationRole(user);
    var code = requireCode(command.code());
    var name = requireText(command.name(), "name", 200);
    var sortOrder = command.sortOrder() != null ? command.sortOrder() : 0;
    requireNonNegativeSortOrder(sortOrder);
    var now = Instant.now(clock);
    var entity = new PmFrequencyEntity(
        UUID.randomUUID(),
        code,
        name,
        normalize(command.description()),
        sortOrder,
        command.isActive() != null ? command.isActive() : true,
        now, now);
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_FREQUENCY,
        saved.getId(), saved.getCode(), null, null, frequencyValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public FrequencyView update(AuthenticatedUser user, UUID id, UpdateFrequencyCommand command) {
    requireMutationRole(user);
    var entity = loadFrequency(id);
    var name = requireText(command.name(), "name", 200);
    var sortOrder = command.sortOrder() != null ? command.sortOrder() : entity.getSortOrder();
    requireNonNegativeSortOrder(sortOrder);
    // Null-merge parity with sortOrder/isActive: an omitted description keeps the
    // current value; a provided (even blank) description replaces it.
    var description = command.description() != null
        ? normalize(command.description())
        : entity.getDescription();
    var previous = frequencyValues(entity);
    var now = Instant.now(clock);
    entity = new PmFrequencyEntity(
        entity.getId(),
        entity.getCode(),
        name,
        description,
        sortOrder,
        command.isActive() != null ? command.isActive() : entity.isActive(),
        entity.getCreatedAt(), now);
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_FREQUENCY,
        saved.getId(), saved.getCode(), null, previous, frequencyValues(saved), null));
    return toView(saved);
  }

  private PmFrequencyEntity loadFrequency(UUID id) {
    return frequencies.findById(id).orElseThrow(PmFrequencyNotFoundException::new);
  }

  /** Gate: frequency mutations are global master-data — role gate only. */
  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    switch (user.applicationRole()) {
      case STAFF_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {}
      default -> throw new PmFrequencyForbiddenException();
    }
  }

  /** Race backstop: the UNIQUE code constraint maps to DUPLICATE_FREQUENCY_CODE. */
  private PmFrequencyEntity save(PmFrequencyEntity entity) {
    try {
      return frequencies.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(FREQUENCY_CODE_UNIQUE_CONSTRAINT)) {
        throw new DuplicateFrequencyCodeException();
      }
      throw exception;
    }
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /**
   * Blank-after-trim and over-long values are rejected here rather than at the
   * DB CHECK (ck_pm_frequencies_code_not_blank / name_not_blank), which would
   * surface as a 500 — @NotBlank passes on "   ", so the service is the last
   * boundary that can produce a 400 VALIDATION_ERROR.
   */
  private static String requireText(String value, String field, int maxLength) {
    var trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new FrequencyValidationException(Map.of(field, field + " must not be blank."));
    }
    if (trimmed.length() > maxLength) {
      throw new FrequencyValidationException(
          Map.of(field, field + " must be at most " + maxLength + " characters."));
    }
    return trimmed;
  }

  /**
   * Codes are stored UPPERCASE (Locale.ROOT — a Turkish-locale toUpperCase would
   * turn "i" into "İ" and break code lookups). Length is validated AFTER
   * uppercasing because case conversion can expand a string past VARCHAR(50)
   * under some locales.
   */
  private static String requireCode(String value) {
    var trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new FrequencyValidationException(Map.of("code", "code must not be blank."));
    }
    var upper = trimmed.toUpperCase(Locale.ROOT);
    if (upper.length() > 50) {
      throw new FrequencyValidationException(
          Map.of("code", "code must be at most 50 characters."));
    }
    return upper;
  }

  private static void requireNonNegativeSortOrder(int sortOrder) {
    if (sortOrder < 0) {
      throw new FrequencyValidationException(
          Map.of("sortOrder", "sortOrder must be zero or greater."));
    }
  }

  private static Map<String, Object> frequencyValues(PmFrequencyEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("code", entity.getCode());
    values.put("name", entity.getName());
    values.put("description", entity.getDescription());
    values.put("sortOrder", entity.getSortOrder());
    values.put("isActive", entity.isActive());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  public static FrequencyView toView(PmFrequencyEntity entity) {
    return new FrequencyView(entity.getId(), entity.getCode(), entity.getName(),
        entity.getDescription(), entity.getSortOrder(), entity.isActive(),
        entity.getCreatedAt(), entity.getUpdatedAt());
  }

  public record CreateFrequencyCommand(String code, String name, String description,
      Integer sortOrder, Boolean isActive) {
  }

  public record UpdateFrequencyCommand(String name, String description, Integer sortOrder,
      Boolean isActive) {
  }

  public record FrequencyView(UUID id, String code, String name, String description,
      int sortOrder, boolean active, Instant createdAt, Instant updatedAt) {
  }

  public static class PmFrequencyNotFoundException extends RuntimeException {
  }

  public static class DuplicateFrequencyCodeException extends RuntimeException {
  }

  public static class PmFrequencyForbiddenException extends RuntimeException {
  }

  /** Blank/over-long/negative-sortOrder fields → 400 VALIDATION_ERROR with field errors. */
  public static class FrequencyValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public FrequencyValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
