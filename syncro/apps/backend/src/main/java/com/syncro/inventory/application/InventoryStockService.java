package com.syncro.inventory.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.inventory.domain.InventoryStockBalance;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory stock application service (blueprint E2, story 15-1 re-key of the
 * legacy sparepart_stock module). Owns every stock mutation — the request/workorder
 * modules never touch {@code inventory_stock_balances} directly; the PICKED_UP
 * decrement and the completion flow call into this service.
 *
 * <p>Keying: balances are (sparepart_id, location_id). The public API stays keyed
 * by material code + plant (the operator-facing contract); the service resolves the
 * sparepart by material code and the plant's default location ("GUDANG UTAMA",
 * seeded per plant) so an operator never has to know the location UUID for the
 * standard store flow.
 *
 * <p>Gates: mutations require INVENTORY_MAINTENANCE/STOREKEEPER/SUPER_ADMIN with the
 * target plant in the user's assignment scope (SUPER_ADMIN exempt). Negative stock is
 * impossible by construction: consumption runs as a single atomic conditional UPDATE
 * guarded by {@code available - delta >= 0} (the consumed running total rises by the
 * same delta); a rejected adjustment is a 409 NEGATIVE_STOCK_REJECTED. Version
 * conflicts surface as 409 VERSION_CONFLICT.
 */
@Service
public class InventoryStockService {

  static final String DEFAULT_LOCATION_CODE = "GUDANG-UTAMA";

  private final InventoryStockBalanceRepository balances;
  private final InventoryLocationRepository locations;
  private final SparepartRepository spareparts;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public InventoryStockService(InventoryStockBalanceRepository balances,
      InventoryLocationRepository locations, SparepartRepository spareparts,
      AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog, Clock clock) {
    this.balances = balances;
    this.locations = locations;
    this.spareparts = spareparts;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Upsert semantics: a missing balance row is created with the provided values
   * (version starts at 0); an existing row is fully overwritten with the provided
   * fields and the version is advanced (optimistic lock).
   */
  @Transactional
  public InventoryStockBalance upsert(AuthenticatedUser user, UpsertBalanceCommand command) {
    requireMutationAccess(user, command.plantId());
    var sparepart = requireKnownSparepart(command.materialCode());
    requireNonNegative(command.available(), "available");
    requireNonNegative(command.reserved(), "reserved");
    requireNonNegative(command.consumed(), "consumed");
    requireNonNegative(command.minimumStock(), "minimumStock");
    var location = defaultLocation(command.plantId());
    var now = Instant.now(clock);
    var existing = balances.findBySparepartIdAndLocationId(sparepart.getId(), location.getId())
        .orElse(null);
    if (existing == null) {
      var entity = new InventoryStockBalanceEntity(UUID.randomUUID(), sparepart.getId(),
          location.getId(), command.available(), command.reserved(), command.consumed(),
          command.minimumStock(), now, now);
      var saved = balances.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE,
          AuditEntityType.INVENTORY_STOCK_BALANCE, saved.getId(), entityLabel(sparepart, saved),
          command.plantId(), null, balanceValues(sparepart, saved), null));
      return toDomain(saved);
    }
    var previous = balanceValues(sparepart, existing);
    existing.replace(command.available(), command.reserved(), command.consumed(),
        command.minimumStock(), now);
    var saved = balances.saveAndFlush(existing);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, saved.getId(), entityLabel(sparepart, saved),
        command.plantId(), previous, balanceValues(sparepart, saved), null));
    return toDomain(saved);
  }

  /**
   * Full overwrite of the provided fields (PUT semantics). All four value fields are
   * optional in the body but at least one must be present; provided fields are replaced,
   * absent fields keep their current value. The optimistic-lock version is required and
   * validated against the stored row (409 VERSION_CONFLICT on mismatch).
   */
  @Transactional
  public InventoryStockBalance update(AuthenticatedUser user, String materialCode,
      UpdateBalanceCommand command) {
    requireMutationAccess(user, command.plantId());
    requireNonNegative(command.available(), "available");
    requireNonNegative(command.reserved(), "reserved");
    requireNonNegative(command.consumed(), "consumed");
    requireNonNegative(command.minimumStock(), "minimumStock");
    if (command.available() == null && command.reserved() == null && command.consumed() == null
        && command.minimumStock() == null) {
      throw new InventoryStockValidationException(Map.of("update",
          "At least one of available, reserved, consumed or minimumStock must be provided."));
    }
    var sparepart = requireKnownSparepart(materialCode);
    var location = defaultLocation(command.plantId());
    var existing = findBalance(sparepart.getId(), location.getId());
    if (existing.getVersion() != command.version()) {
      throw new VersionConflictException();
    }
    var previous = balanceValues(sparepart, existing);
    var now = Instant.now(clock);
    existing.replace(
        command.available() != null ? command.available() : existing.getAvailable(),
        command.reserved() != null ? command.reserved() : existing.getReserved(),
        command.consumed() != null ? command.consumed() : existing.getConsumed(),
        command.minimumStock() != null ? command.minimumStock() : existing.getMinimumStock(),
        now);
    var saved = balances.saveAndFlush(existing);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, saved.getId(), entityLabel(sparepart, saved),
        command.plantId(), previous, balanceValues(sparepart, saved), null));
    return toDomain(saved);
  }

  /**
   * Stock adjustment (FR-146, NFR-P2-6): a signed delta applied atomically with the
   * non-negative guard. {@code delta} may be negative (withdrawal) or positive
   * (correction/restock). A zero-row update means the guard failed → 409
   * NEGATIVE_STOCK_REJECTED, and no change is applied. The version column is bumped
   * by the same statement so concurrent readers observe the new value.
   */
  @Transactional
  public InventoryStockBalance adjust(AuthenticatedUser user, String materialCode,
      AdjustBalanceCommand command) {
    requireMutationAccess(user, command.plantId());
    var sparepart = requireKnownSparepart(materialCode);
    var location = defaultLocation(command.plantId());
    var existing = findBalance(sparepart.getId(), location.getId());
    var previous = balanceValues(sparepart, existing);
    var delta = command.delta();
    var now = Instant.now(clock);
    int rows = balances.adjustAvailableIfSufficient(sparepart.getId(), location.getId(), delta, now);
    if (rows == 0) {
      throw new NegativeStockRejectedException();
    }
    balances.flush();
    var updated = findBalance(sparepart.getId(), location.getId());
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, updated.getId(), entityLabel(sparepart, updated),
        command.plantId(), previous, balanceValues(sparepart, updated), null));
    return toDomain(updated);
  }

  /**
   * Atomic consumption for the PICKED_UP hook (AD-11). When the balance row is missing
   * the call returns {@code null} and the caller skips silently (stock is optional per
   * plant; the request lifecycle must not block on an absent record). A consumption that
   * would go negative throws {@link NegativeStockRejectedException} so the request
   * transition rolls back (no partial pickup). Every applied consumption is
   * audit-logged with a SYSTEM actor label — the pickup transition supplies no
   * {@link AuthenticatedUser} (the section leader is the transition actor; the stock
   * movement is recorded under the request's own audit).
   */
  @Transactional
  public InventoryStockBalance consumeOnPickup(String materialCode, UUID plantId, BigDecimal quantity) {
    var sparepart = spareparts.findByMaterialCodeIgnoreCase(materialCode).orElse(null);
    if (sparepart == null) {
      return null;
    }
    var location = locations.findByPlantIdAndCodeIgnoreCase(plantId, DEFAULT_LOCATION_CODE).orElse(null);
    if (location == null) {
      return null;
    }
    var existing = balances.findBySparepartIdAndLocationId(sparepart.getId(), location.getId())
        .orElse(null);
    if (existing == null) {
      return null;
    }
    var now = Instant.now(clock);
    var previous = balanceValues(sparepart, existing);
    int rows = balances.consumeIfSufficient(sparepart.getId(), location.getId(), quantity, now);
    if (rows == 0) {
      throw new NegativeStockRejectedException();
    }
    balances.flush();
    var updated = toDomain(findBalance(sparepart.getId(), location.getId()));
    auditLog.recordSystem(new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, existing.getId(),
        entityLabel(sparepart, existing), plantId, previous,
        balanceValuesOf(sparepart, updated), null));
    return updated;
  }

  private static Map<String, Object> balanceValuesOf(SparepartEntity sparepart,
      InventoryStockBalance domain) {
    var values = new LinkedHashMap<String, Object>();
    values.put("materialCode", sparepart.getMaterialCode());
    values.put("sparepartId", domain.sparepartId());
    values.put("locationId", domain.locationId());
    values.put("available", domain.available());
    values.put("reserved", domain.reserved());
    values.put("consumed", domain.consumed());
    values.put("minimumStock", domain.minimumStock());
    values.put("version", domain.version());
    return values;
  }

  /** Reorder-warning rows for a plant (available &le; minimum_stock, POC proposal §4). */
  @Transactional(readOnly = true)
  public List<InventoryStockBalance> reorderWarnings(AuthenticatedUser user, UUID plantId) {
    requireReadAccess(user, plantId);
    return balances.findReorderWarnings(plantId).stream()
        .map(InventoryStockService::toDomain)
        .toList();
  }

  /** All balance rows visible in a plant (read path for the Stock page). */
  @Transactional(readOnly = true)
  public List<InventoryStockBalance> list(AuthenticatedUser user, UUID plantId) {
    requireReadAccess(user, plantId);
    return balances.findAllByPlantId(plantId).stream()
        .map(InventoryStockService::toDomain)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private InventoryStockBalanceEntity findBalance(UUID sparepartId, UUID locationId) {
    return balances.findBySparepartIdAndLocationId(sparepartId, locationId)
        .orElseThrow(StockBalanceNotFoundException::new);
  }

  private InventoryLocationEntity defaultLocation(UUID plantId) {
    return locations.findByPlantIdAndCodeIgnoreCase(plantId, DEFAULT_LOCATION_CODE)
        .orElseThrow(DefaultLocationNotFoundException::new);
  }

  private void requireMutationAccess(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (user.applicationRole() != ApplicationRole.INVENTORY_MAINTENANCE
        && user.applicationRole() != ApplicationRole.STOREKEEPER) {
      throw new InventoryStockForbiddenException();
    }
    if (!plantAssigned(user, plantId)) {
      throw new InventoryStockForbiddenException();
    }
  }

  private void requireReadAccess(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new InventoryStockForbiddenException();
    }
  }

  private boolean plantAssigned(AuthenticatedUser user, UUID plantId) {
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .anyMatch(assignment -> assignment.getPlantId().equals(plantId));
  }

  private SparepartEntity requireKnownSparepart(String materialCode) {
    if (materialCode == null || materialCode.trim().isEmpty()) {
      throw new InventoryStockValidationException(Map.of("materialCode",
          "Material code must not be blank."));
    }
    var trimmed = materialCode.trim();
    if (trimmed.length() > 64) {
      throw new InventoryStockValidationException(Map.of("materialCode",
          "Material code must be at most 64 characters."));
    }
    return spareparts.findByMaterialCodeIgnoreCase(trimmed)
        .orElseThrow(SparepartNotFoundException::new);
  }

  /** AD-11: stock values can never be negative, even through a direct overwrite. */
  private static void requireNonNegative(BigDecimal value, String field) {
    if (value != null && value.signum() < 0) {
      throw new InventoryStockValidationException(Map.of(field, field + " must not be negative."));
    }
  }

  private static UUID balanceEntityId(InventoryStockBalanceEntity entity) {
    return entity.getId();
  }

  private static String entityLabel(SparepartEntity sparepart, InventoryStockBalanceEntity entity) {
    var code = sparepart.getMaterialCode() != null ? sparepart.getMaterialCode() : sparepart.getCode();
    return code + " @" + entity.getLocationId();
  }

  private static Map<String, Object> balanceValues(SparepartEntity sparepart,
      InventoryStockBalanceEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("materialCode", sparepart.getMaterialCode());
    values.put("sparepartId", entity.getSparepartId());
    values.put("locationId", entity.getLocationId());
    values.put("available", entity.getAvailable());
    values.put("reserved", entity.getReserved());
    values.put("consumed", entity.getConsumed());
    values.put("minimumStock", entity.getMinimumStock());
    values.put("version", entity.getVersion());
    return values;
  }

  private static InventoryStockBalance toDomain(InventoryStockBalanceEntity entity) {
    return new InventoryStockBalance(entity.getId(), entity.getSparepartId(), entity.getLocationId(),
        entity.getAvailable(), entity.getReserved(), entity.getConsumed(), entity.getMinimumStock(),
        entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  /** Upsert-create command. */
  public record UpsertBalanceCommand(String materialCode, UUID plantId, BigDecimal available,
      BigDecimal reserved, BigDecimal consumed, BigDecimal minimumStock) {
  }

  /** PUT partial-overwrite command (all four optional, at least one required). */
  public record UpdateBalanceCommand(UUID plantId, long version, BigDecimal available,
      BigDecimal reserved, BigDecimal consumed, BigDecimal minimumStock) {
  }

  /** POST /adjust signed-delta command. */
  public record AdjustBalanceCommand(UUID plantId, BigDecimal delta) {
  }

  public static class StockBalanceNotFoundException extends RuntimeException {
  }

  /** The plant has no default location (seed creates one per plant) → 404. */
  public static class DefaultLocationNotFoundException extends RuntimeException {
  }

  /** Plant-scope or role rejection → 403 FORBIDDEN. */
  public static class InventoryStockForbiddenException extends RuntimeException {
  }

  /** Atomic conditional update matched 0 rows → 409 NEGATIVE_STOCK_REJECTED (AD-11). */
  public static class NegativeStockRejectedException extends RuntimeException {
  }

  /** Optimistic-lock mismatch → 409 VERSION_CONFLICT. */
  public static class VersionConflictException extends RuntimeException {
  }

  /** Unknown sparepart material code → 404 SPAREPART_NOT_FOUND. */
  public static class SparepartNotFoundException extends RuntimeException {
  }

  public static class InventoryStockValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public InventoryStockValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
