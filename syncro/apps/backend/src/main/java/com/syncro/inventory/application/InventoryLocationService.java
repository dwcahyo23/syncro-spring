package com.syncro.inventory.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
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
 * Inventory-location lifecycle service (blueprint E1, story 18-2). Named per-plant
 * storage locations: create, update (rename / description / activate-deactivate),
 * list and get. There is deliberately NO delete — balances, transfers and
 * reservations reference locations with RESTRICT FKs, so deactivation is the
 * lifecycle end.
 *
 * <p>Gates (epic-18 AC): mutations require SUPER_ADMIN/MANAGER_MAINTENANCE/
 * INVENTORY_MAINTENANCE with the target plant in the user's assignment scope
 * (SUPER_ADMIN exempt) — STOREKEEPER is deliberately NOT granted location
 * management. Reads require a plant assignment (or SUPER_ADMIN). The gate idiom
 * mirrors {@link InventoryStockService}. Every mutation writes an audit record
 * ({@link AuditEntityType#INVENTORY_LOCATION}) with previous + new values.
 *
 * <p>Uniqueness: (plant, code) case-insensitive pre-check plus the
 * {@code uq_inventory_locations_plant_code} constraint as the race backstop, both
 * surfacing as 409 DUPLICATE_LOCATION. The seeded default location ("GUDANG-UTAMA")
 * stays resolvable by {@link InventoryStockService}; deactivating it is allowed
 * (soft) but the code lookup itself is untouched.
 */
@Service
public class InventoryLocationService {

  private static final String LOCATION_CODE_UNIQUE_CONSTRAINT = "uq_inventory_locations_plant_code";

  private final InventoryLocationRepository locations;
  private final PlantRepository plants;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public InventoryLocationService(InventoryLocationRepository locations, PlantRepository plants,
      AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog, Clock clock) {
    this.locations = locations;
    this.plants = plants;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /** POST — create an active location; (plant, code) must be unused (case-insensitive). */
  @Transactional
  public LocationView create(AuthenticatedUser user, CreateLocationCommand command) {
    requireMutationAccess(user, command.plantId());
    var plant = plants.findById(command.plantId())
        .orElseThrow(PlantNotFoundForLocationException::new);
    var code = requireCode(command.code());
    var name = requireText(command.name(), "name", 255);
    var description = normalizeOptional(command.description(), "description", 1000);
    if (locations.findByPlantIdAndCodeIgnoreCase(plant.getId(), code).isPresent()) {
      throw new DuplicateLocationException();
    }
    var now = Instant.now(clock);
    var saved = save(new InventoryLocationEntity(UUID.randomUUID(), plant.getId(), code, name,
        description, true, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.INVENTORY_LOCATION,
        saved.getId(), entityLabel(saved, plant.getCode()), plant.getId(), null,
        locationValues(saved), null));
    return toView(saved);
  }

  /**
   * PUT — provided fields are replaced, absent fields keep their current value
   * (InventoryStockService.update idiom), so {@code {"isActive": false}} alone is a
   * valid deactivation. An all-absent body is rejected (400) rather than a no-op
   * that bumps updatedAt and writes an identical audit row. Unknown id → 404; a code
   * already used by another location in the plant → 409 DUPLICATE_LOCATION. The
   * plant's default location ("GUDANG-UTAMA", resolved by {@link
   * InventoryStockService}) may be deactivated but never renamed — a rename would
   * break stock resolution for the whole plant.
   */
  @Transactional
  public LocationView update(AuthenticatedUser user, UUID locationId,
      UpdateLocationCommand command) {
    var location = locations.findById(locationId)
        .orElseThrow(InventoryLocationNotFoundException::new);
    requireMutationAccess(user, location.getPlantId());
    var plant = plants.findById(location.getPlantId())
        .orElseThrow(PlantNotFoundForLocationException::new);
    if (command.code() == null && command.name() == null && command.description() == null
        && command.active() == null) {
      throw new InventoryLocationValidationException(Map.of("update",
          "At least one of code, name, description or isActive must be provided."));
    }
    var code = command.code() != null ? requireCode(command.code()) : location.getCode();
    if (command.code() != null
        && InventoryStockService.DEFAULT_LOCATION_CODE.equalsIgnoreCase(location.getCode())
        && !InventoryStockService.DEFAULT_LOCATION_CODE.equalsIgnoreCase(code)) {
      throw new InventoryLocationValidationException(
          Map.of("code", "The default location code cannot be renamed."));
    }
    var name = command.name() != null ? requireText(command.name(), "name", 255) : location.getName();
    var description = command.description() != null
        ? normalizeOptional(command.description(), "description", 1000)
        : location.getDescription();
    if (locations.existsByPlantIdAndCodeIgnoreCaseAndIdNot(location.getPlantId(), code, locationId)) {
      throw new DuplicateLocationException();
    }
    var previous = locationValues(location);
    var now = Instant.now(clock);
    location.update(code, name, description,
        command.active() != null ? command.active() : location.isActive(), now);
    var saved = save(location);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.INVENTORY_LOCATION,
        saved.getId(), entityLabel(saved, plant.getCode()), saved.getPlantId(), previous,
        locationValues(saved), null));
    return toView(saved);
  }

  /** GET list — only the requested plant, ordered by name; activeOnly filters is_active. */
  @Transactional(readOnly = true)
  public List<LocationView> list(AuthenticatedUser user, UUID plantId, boolean activeOnly) {
    requireReadAccess(user, plantId);
    if (!plants.existsById(plantId)) {
      throw new PlantNotFoundForLocationException();
    }
    var result = activeOnly
        ? locations.findAllByPlantIdAndActiveTrueOrderByNameAsc(plantId)
        : locations.findAllByPlantIdOrderByNameAsc(plantId);
    return result.stream().map(InventoryLocationService::toView).toList();
  }

  /** GET one — read gate applies to the location's plant. */
  @Transactional(readOnly = true)
  public LocationView get(AuthenticatedUser user, UUID locationId) {
    var location = locations.findById(locationId)
        .orElseThrow(InventoryLocationNotFoundException::new);
    requireReadAccess(user, location.getPlantId());
    return toView(location);
  }

  // -------------------------------------------------------------------------
  // Gates (InventoryStockService idiom)
  // -------------------------------------------------------------------------

  private void requireMutationAccess(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE
        && user.applicationRole() != ApplicationRole.INVENTORY_MAINTENANCE) {
      throw new InventoryLocationForbiddenException();
    }
    if (!plantAssigned(user, plantId)) {
      throw new InventoryLocationForbiddenException();
    }
  }

  private void requireReadAccess(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new InventoryLocationForbiddenException();
    }
  }

  private boolean plantAssigned(AuthenticatedUser user, UUID plantId) {
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .anyMatch(assignment -> assignment.getPlantId().equals(plantId));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Race backstop: the plain UNIQUE (plant_id, code) maps to DUPLICATE_LOCATION. */
  private InventoryLocationEntity save(InventoryLocationEntity location) {
    try {
      return locations.saveAndFlush(location);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(LOCATION_CODE_UNIQUE_CONSTRAINT)) {
        throw new DuplicateLocationException();
      }
      throw exception;
    }
  }

  /**
   * Codes are stored UPPERCASE: the DB UNIQUE (plant_id, code) is case-sensitive, so
   * normalizing on write is what makes the case-insensitive pre-check race-proof
   * (two rows "WS-01"/"ws-01" can never coexist). Seeds are already uppercase and
   * findByPlantIdAndCodeIgnoreCase still matches.
   */
  private static String requireCode(String value) {
    var trimmed = requireText(value, "code", 64);
    return trimmed.toUpperCase(Locale.ROOT);
  }

  private static String requireText(String value, String field, int maxLength) {
    var trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new InventoryLocationValidationException(
          Map.of(field, field + " must not be blank."));
    }
    if (trimmed.length() > maxLength) {
      throw new InventoryLocationValidationException(
          Map.of(field, field + " must be at most " + maxLength + " characters."));
    }
    return trimmed;
  }

  private static String normalizeOptional(String value, String field, int maxLength) {
    if (value == null) {
      return null;
    }
    var trimmed = value.trim();
    if (trimmed.length() > maxLength) {
      throw new InventoryLocationValidationException(
          Map.of(field, field + " must be at most " + maxLength + " characters."));
    }
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String entityLabel(InventoryLocationEntity location, String plantCode) {
    return location.getCode() + " @" + plantCode;
  }

  private static Map<String, Object> locationValues(InventoryLocationEntity location) {
    var values = new LinkedHashMap<String, Object>();
    values.put("plantId", location.getPlantId().toString());
    values.put("code", location.getCode());
    values.put("name", location.getName());
    values.put("description", location.getDescription());
    values.put("active", location.isActive());
    return values;
  }

  private static LocationView toView(InventoryLocationEntity location) {
    return new LocationView(location.getId(), location.getPlantId(), location.getCode(),
        location.getName(), location.getDescription(), location.isActive(),
        location.getCreatedAt(), location.getUpdatedAt());
  }

  /** POST command. */
  public record CreateLocationCommand(UUID plantId, String code, String name, String description) {
  }

  /**
   * PUT command — null fields keep their current value (partial update; an all-null
   * body is rejected). For {@code description}, null means "keep" while an empty or
   * blank string means "clear". {@code active} null keeps the current flag (partial
   * lifecycle toggle); codes are normalized to uppercase on write.
   */
  public record UpdateLocationCommand(String code, String name, String description, Boolean active) {
  }

  /** Service read model (the api layer maps it to the response DTO). */
  public record LocationView(UUID id, UUID plantId, String code, String name, String description,
      boolean active, Instant createdAt, Instant updatedAt) {
  }

  /** (plant, code) already used — pre-check or DB constraint backstop → 409. */
  public static class DuplicateLocationException extends RuntimeException {
  }

  /** Unknown location id → 404 INVENTORY_LOCATION_NOT_FOUND. */
  public static class InventoryLocationNotFoundException extends RuntimeException {
  }

  /** Role/plant-scope rejection → 403 FORBIDDEN. */
  public static class InventoryLocationForbiddenException extends RuntimeException {
  }

  /** Unknown plant (create/list) → 404 PLANT_NOT_FOUND. */
  public static class PlantNotFoundForLocationException extends RuntimeException {
  }

  /** Blank/over-long fields → 400 VALIDATION_ERROR with field errors. */
  public static class InventoryLocationValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public InventoryLocationValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
