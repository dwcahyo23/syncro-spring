package com.syncro.integration.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.integration.api.IntegrationDtos.CreateWebhookConfigRequest;
import com.syncro.integration.api.IntegrationDtos.UpdateWebhookConfigRequest;
import com.syncro.integration.api.IntegrationDtos.WebhookConfigView;
import com.syncro.integration.domain.WebhookDirection;
import com.syncro.integration.infrastructure.db.WebhookConfigEntity;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SUPER_ADMIN-only CRUD for outbound webhook configs (story 22-1, blueprint I4).
 *
 * <p>Every mutation is triple-gated: this service role gate, the rego
 * {@code admin_only_paths} exclusion (coarse parity gate), and an immutable audit
 * row of type {@code WEBHOOK_CONFIG} with previous/new values. The HMAC secret is
 * stored in the V1 plaintext column but masked to its last 4 characters in every API
 * projection and every audit value — the raw secret never leaves this service.
 *
 * <p>There is no DELETE endpoint (21-1 no-delete precedent): deactivation via
 * {@code active=false} preserves the delivery history.
 */
@Service
public class WebhookConfigService {

  private static final String NAME_UNIQUE_CONSTRAINT = "uq_webhook_configs_name";

  private final WebhookConfigRepository configs;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WebhookConfigService(WebhookConfigRepository configs, AuditLogWriter auditLog,
      Clock clock) {
    this.configs = configs;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<WebhookConfigView> list(AuthenticatedUser user) {
    requireSuperAdmin(user);
    return configs.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
        .map(WebhookConfigService::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public WebhookConfigView get(AuthenticatedUser user, UUID id) {
    requireSuperAdmin(user);
    return toView(load(id));
  }

  @Transactional
  public WebhookConfigView create(AuthenticatedUser user, CreateWebhookConfigRequest request) {
    requireSuperAdmin(user);
    var name = requireText(request.name(), "name", 200);
    if (request.direction() == null) {
      throw new WebhookValidationException(Map.of("direction", "direction is required."));
    }
    var direction = request.direction();
    var eventTypes = normalizeEventTypes(request.eventTypes());
    var endpointUrl = trimToNull(request.endpointUrl());
    var hmacSecret = trimToNull(request.hmacSecret());
    validateOutbound(direction, endpointUrl, hmacSecret, eventTypes);
    if (configs.findByName(name).isPresent()) {
      throw new DuplicateWebhookNameException();
    }
    var now = Instant.now(clock);
    var entity = new WebhookConfigEntity(
        UUID.randomUUID(), name, direction, eventTypes, endpointUrl, hmacSecret,
        request.active() == null || request.active(), userIdOrNull(user), now, now);
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WEBHOOK_CONFIG,
        saved.getId(), saved.getName(), null, null, configValues(saved), null));
    return toView(saved);
  }

  /**
   * Partial update: null fields keep their current value. A non-null {@code hmacSecret}
   * rotates the secret (the old signature stays valid only for already-queued payloads —
   * documented limitation, no dual-key window this story). Name and direction are
   * immutable identity fields and not accepted here.
   */
  @Transactional
  public WebhookConfigView update(AuthenticatedUser user, UUID id, UpdateWebhookConfigRequest request) {
    requireSuperAdmin(user);
    var entity = load(id);
    var previous = configValues(entity);
    var eventTypes = request.eventTypes() != null
        ? normalizeEventTypes(request.eventTypes())
        : entity.getEventTypes();
    var endpointUrl = request.endpointUrl() != null
        ? trimToNull(request.endpointUrl())
        : entity.getEndpointUrl();
    var hmacSecret = request.hmacSecret() != null
        ? trimToNull(request.hmacSecret())
        : entity.getHmacSecret();
    var active = request.active() != null ? request.active() : entity.isActive();
    validateOutbound(entity.getDirection(), endpointUrl, hmacSecret, eventTypes);
    entity.applyUpdate(eventTypes, endpointUrl, hmacSecret, active, Instant.now(clock));
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WEBHOOK_CONFIG,
        saved.getId(), saved.getName(), null, previous, configValues(saved), null));
    return toView(saved);
  }

  private WebhookConfigEntity load(UUID id) {
    return configs.findById(id).orElseThrow(WebhookConfigNotFoundException::new);
  }

  /** Gate: webhook config surface is SUPER_ADMIN-only (rego admin_only_paths parity). */
  private void requireSuperAdmin(AuthenticatedUser user) {
    if (user == null || user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new WebhookForbiddenException();
    }
  }

  private static UUID userIdOrNull(AuthenticatedUser user) {
    try {
      return UUID.fromString(user.id());
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /** Race backstop: the UNIQUE name constraint maps to DUPLICATE_WEBHOOK_NAME. */
  private WebhookConfigEntity save(WebhookConfigEntity entity) {
    try {
      return configs.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(NAME_UNIQUE_CONSTRAINT)) {
        throw new DuplicateWebhookNameException();
      }
      throw exception;
    }
  }

  /**
   * V1 columns are nullable, so the OUTBOUND completeness rule (endpoint + secret +
   * at least one subscribed event type) is enforced here, service-level (spec Always).
   * The endpoint must also be a parseable absolute http(s) URI (review 22-1 P11) — a
   * malformed URL would otherwise burn the full retry budget against an unroutable host.
   */
  private static void validateOutbound(WebhookDirection direction, String endpointUrl,
      String hmacSecret, List<String> eventTypes) {
    if (direction != WebhookDirection.OUTBOUND) {
      return;
    }
    var errors = new LinkedHashMap<String, String>();
    if (endpointUrl == null) {
      errors.put("endpointUrl", "endpointUrl is required for OUTBOUND configs.");
    } else if (!isHttpUri(endpointUrl)) {
      errors.put("endpointUrl", "endpointUrl must be an absolute http(s) URL.");
    }
    if (hmacSecret == null) {
      errors.put("hmacSecret", "hmacSecret is required for OUTBOUND configs.");
    }
    if (eventTypes == null || eventTypes.isEmpty()) {
      errors.put("eventTypes", "eventTypes must not be empty for OUTBOUND configs.");
    }
    if (!errors.isEmpty()) {
      throw new WebhookValidationException(errors);
    }
  }

  private static boolean isHttpUri(String value) {
    try {
      var uri = java.net.URI.create(value);
      return uri.isAbsolute()
          && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost() != null && !uri.getHost().isBlank();
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static List<String> normalizeEventTypes(List<String> eventTypes) {
    if (eventTypes == null) {
      return null;
    }
    var normalized = new ArrayList<String>(eventTypes.size());
    for (var value : eventTypes) {
      var trimmed = value == null ? "" : value.trim();
      if (trimmed.isEmpty()) {
        throw new WebhookValidationException(Map.of("eventTypes", "eventTypes must not contain blank values."));
      }
      if (!normalized.contains(trimmed)) {
        normalized.add(trimmed);
      }
    }
    return List.copyOf(normalized);
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static String requireText(String value, String field, int maxLength) {
    var trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new WebhookValidationException(Map.of(field, field + " must not be blank."));
    }
    if (trimmed.length() > maxLength) {
      throw new WebhookValidationException(
          Map.of(field, field + " must be at most " + maxLength + " characters."));
    }
    return trimmed;
  }

  /** Last 4 characters only — the raw secret never appears in a view or audit value. */
  static String maskSecret(String secret) {
    if (secret == null) {
      return null;
    }
    if (secret.length() <= 4) {
      return "****";
    }
    return "****" + secret.substring(secret.length() - 4);
  }

  static WebhookConfigView toView(WebhookConfigEntity entity) {
    return new WebhookConfigView(
        entity.getId(),
        entity.getName(),
        entity.getDirection(),
        entity.getEventTypes() == null ? List.of() : List.copyOf(entity.getEventTypes()),
        entity.getEndpointUrl(),
        maskSecret(entity.getHmacSecret()),
        entity.isActive(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }

  /** Audit projection — secret masked in BOTH previous and new values (spec Always). */
  private static Map<String, Object> configValues(WebhookConfigEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("name", entity.getName());
    values.put("direction", entity.getDirection().name());
    values.put("eventTypes", entity.getEventTypes() == null ? List.of() : List.copyOf(entity.getEventTypes()));
    values.put("endpointUrl", entity.getEndpointUrl());
    values.put("hmacSecret", maskSecret(entity.getHmacSecret()));
    values.put("isActive", entity.isActive());
    return values;
  }

  public static class WebhookConfigNotFoundException extends RuntimeException {
  }

  public static class DuplicateWebhookNameException extends RuntimeException {
  }

  public static class WebhookForbiddenException extends RuntimeException {
  }

  /** Blank/missing-field failures → 400 VALIDATION_ERROR with field errors. */
  public static class WebhookValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public WebhookValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
