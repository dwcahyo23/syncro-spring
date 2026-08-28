package com.syncro.sparepart.stock.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.stock.domain.SparepartStock;
import com.syncro.sparepart.stock.infrastructure.db.SparepartStockEntity;
import com.syncro.sparepart.stock.infrastructure.db.SparepartStockId;
import com.syncro.sparepart.stock.infrastructure.db.SparepartStockRepository;
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
 * Sparepart stock application service (FR-146/AD-11, story 12-4). Owns every stock
 * mutation — the request/workorder modules never touch {@code sparepart_stock}
 * directly; the PICKED_UP decrement and the completion flow call into this service.
 *
 * <p>Gates: mutations require INVENTORY_MAINTENANCE/STOREKEEPER/SUPER_ADMIN with the
 * target plant in the user's assignment scope (SUPER_ADMIN exempt). Negative stock is
 * impossible by construction: adjustments run as a single atomic conditional UPDATE
 * guarded by {@code stock_on_hand + delta >= 0}; a rejected adjustment is a 409
 * NEGATIVE_STOCK_REJECTED. Version conflicts surface as 409 VERSION_CONFLICT.
 */
@Service
public class SparepartStockService {

  private final SparepartStockRepository stocks;
  private final SparepartRepository spareparts;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public SparepartStockService(SparepartStockRepository stocks, SparepartRepository spareparts,
      AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog, Clock clock) {
    this.stocks = stocks;
    this.spareparts = spareparts;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Upsert semantics (STOCK_CREATE/STOCK_UPSERT): a missing row is created with the
   * provided values (version starts at 0); an existing row is fully overwritten with
   * the provided fields and the version is advanced (optimistic lock).
   */
  @Transactional
  public SparepartStock upsert(AuthenticatedUser user, UpsertStockCommand command) {
    requireMutationAccess(user, command.plantId());
    var materialCode = requireKnownSparepart(requireMaterialCode(command.materialCode()));
    requireNonNegative(command.stockOnHand(), "stockOnHand");
    requireNonNegative(command.orderPoint(), "orderPoint");
    requireNonNegative(command.orderQty(), "orderQty");
    var id = new SparepartStockId(materialCode, command.plantId());
    var now = Instant.now(clock);
    var existing = stocks.findById(id).orElse(null);
    if (existing == null) {
      var entity = new SparepartStockEntity(materialCode, command.plantId(),
          command.stockOnHand(), command.orderPoint(), command.orderQty(), now, now);
      var saved = stocks.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART_STOCK,
          stockEntityId(saved), entityLabel(saved), saved.getPlantId(), null,
          stockValues(saved), null));
      return toDomain(saved);
    }
    var previous = stockValues(existing);
    existing.replace(command.stockOnHand(), command.orderPoint(), command.orderQty(), now);
    var saved = stocks.saveAndFlush(existing);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART_STOCK,
        stockEntityId(saved), entityLabel(saved), saved.getPlantId(), previous,
        stockValues(saved), null));
    return toDomain(saved);
  }

  /**
   * Full overwrite of the provided fields (PUT semantics, story 12-4). All three values
   * are optional in the body but at least one must be present; provided fields are
   * replaced, absent fields keep their current value. The optimistic-lock version is
   * required and validated against the stored row (409 VERSION_CONFLICT on mismatch).
   */
  @Transactional
  public SparepartStock update(AuthenticatedUser user, String materialCode, UpdateStockCommand command) {
    requireMutationAccess(user, command.plantId());
    requireNonNegative(command.stockOnHand(), "stockOnHand");
    requireNonNegative(command.orderPoint(), "orderPoint");
    requireNonNegative(command.orderQty(), "orderQty");
    if (command.stockOnHand() == null && command.orderPoint() == null && command.orderQty() == null) {
      throw new SparepartStockValidationException(Map.of("update",
          "At least one of stockOnHand, orderPoint or orderQty must be provided."));
    }
    var id = new SparepartStockId(materialCode, command.plantId());
    var existing = stocks.findById(id)
        .orElseThrow(SparepartStockNotFoundException::new);
    if (existing.getVersion() != command.version()) {
      throw new VersionConflictException();
    }
    var previous = stockValues(existing);
    var now = Instant.now(clock);
    var newOnHand = command.stockOnHand() != null ? command.stockOnHand() : existing.getStockOnHand();
    var newOp = command.orderPoint() != null ? command.orderPoint() : existing.getOrderPoint();
    var newOq = command.orderQty() != null ? command.orderQty() : existing.getOrderQty();
    existing.replace(newOnHand, newOp, newOq, now);
    var saved = stocks.saveAndFlush(existing);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART_STOCK,
        stockEntityId(saved), entityLabel(saved), saved.getPlantId(), previous,
        stockValues(saved), null));
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
  public SparepartStock adjust(AuthenticatedUser user, String materialCode, AdjustStockCommand command) {
    requireMutationAccess(user, command.plantId());
    var id = new SparepartStockId(materialCode, command.plantId());
    var existing = stocks.findById(id)
        .orElseThrow(SparepartStockNotFoundException::new);
    var previous = stockValues(existing);
    var delta = command.delta();
    var now = Instant.now(clock);
    int rows = stocks.adjustIfSufficient(materialCode, command.plantId(), delta, now);
    if (rows == 0) {
      throw new NegativeStockRejectedException();
    }
    stocks.flush();
    var updated = stocks.findById(id)
        .orElseThrow(SparepartStockNotFoundException::new);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART_STOCK,
        stockEntityId(updated), entityLabel(updated), updated.getPlantId(), previous,
        stockValues(updated), null));
    return toDomain(updated);
  }

  /**
   * Atomic decrement for the PICKED_UP hook (AD-11, story 12-4). When the stock row is
   * missing the call returns {@code null} and the caller skips silently (stock is
   * optional per plant; the request lifecycle must not block on an absent record). A
   * decrement that would go negative throws {@link NegativeStockRejectedException} so
   * the request transition rolls back (no partial pickup). Every applied decrement is
   * audit-logged with a SYSTEM actor label — the pickup transition supplies no
   * {@link AuthenticatedUser} (the section leader is the transition actor; the stock
   * movement is recorded under the request's own audit).
   */
  @Transactional
  public SparepartStock decrementOnPickup(String materialCode, UUID plantId, BigDecimal quantity) {
    var id = new SparepartStockId(materialCode, plantId);
    var existing = stocks.findById(id).orElse(null);
    if (existing == null) {
      return null;
    }
    var now = Instant.now(clock);
    var previous = stockValues(existing);
    int rows = stocks.decrementIfSufficient(materialCode, plantId, quantity.negate(), now);
    if (rows == 0) {
      throw new NegativeStockRejectedException();
    }
    stocks.flush();
    var updated = toDomain(stocks.findById(id).orElseThrow(SparepartStockNotFoundException::new));
    auditLog.recordSystem(new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.SPAREPART_STOCK, stockEntityId(existing), entityLabel(existing),
        plantId, previous, stockValuesOf(updated), null));
    return updated;
  }

  private static Map<String, Object> stockValuesOf(SparepartStock domain) {
    var values = new LinkedHashMap<String, Object>();
    values.put("materialCode", domain.materialCode());
    values.put("plantId", domain.plantId());
    values.put("stockOnHand", domain.stockOnHand());
    values.put("orderPoint", domain.orderPoint());
    values.put("orderQty", domain.orderQty());
    values.put("version", domain.version());
    return values;
  }

  /** Reorder-warning rows for a plant (FR-146): on-hand ≤ OP, recommend OQ. */
  @Transactional(readOnly = true)
  public List<SparepartStock> reorderWarnings(AuthenticatedUser user, UUID plantId) {
    requireReadAccess(user, plantId);
    return stocks.findReorderWarnings(plantId).stream()
        .map(SparepartStockService::toDomain)
        .toList();
  }

  /** All stock rows for a plant (read path for the Stock page). */
  @Transactional(readOnly = true)
  public List<SparepartStock> list(AuthenticatedUser user, UUID plantId) {
    requireReadAccess(user, plantId);
    return stocks.findAllByPlantId(plantId).stream()
        .map(SparepartStockService::toDomain)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void requireMutationAccess(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (user.applicationRole() != ApplicationRole.INVENTORY_MAINTENANCE
        && user.applicationRole() != ApplicationRole.STOREKEEPER) {
      throw new SparepartStockForbiddenException();
    }
    if (!plantAssigned(user, plantId)) {
      throw new SparepartStockForbiddenException();
    }
  }

  private void requireReadAccess(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new SparepartStockForbiddenException();
    }
  }

  private boolean plantAssigned(AuthenticatedUser user, UUID plantId) {
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .anyMatch(assignment -> assignment.getPlantId().equals(plantId));
  }

  private String requireMaterialCode(String materialCode) {
    if (materialCode == null || materialCode.trim().isEmpty()) {
      throw new SparepartStockValidationException(Map.of("materialCode",
          "Material code must not be blank."));
    }
    var trimmed = materialCode.trim();
    if (trimmed.length() > 64) {
      throw new SparepartStockValidationException(Map.of("materialCode",
          "Material code must be at most 64 characters."));
    }
    return trimmed;
  }

  /** AD-11: stock can never be negative, even through a direct upsert/update overwrite. */
  private static void requireNonNegative(BigDecimal value, String field) {
    if (value != null && value.signum() < 0) {
      throw new SparepartStockValidationException(Map.of(field, field + " must not be negative."));
    }
  }

  /**
   * Validates the material code against an existing sparepart AND normalizes it to the
   * sparepart's canonical casing — the stock FK and unique constraint are case-sensitive,
   * so a different-case input would pass the case-insensitive lookup and then fail the FK.
   * Returns the canonical code to store.
   */
  private String requireKnownSparepart(String materialCode) {
    return spareparts.findByMaterialCodeIgnoreCase(materialCode)
        .map(entity -> entity.getMaterialCode())
        .orElseThrow(SparepartNotFoundException::new);
  }

  /** Upsert-create command (STOCK_CREATE). */
  public record UpsertStockCommand(String materialCode, UUID plantId, BigDecimal stockOnHand,
      BigDecimal orderPoint, BigDecimal orderQty) {
  }

  /** PUT partial-overwrite command (all three optional, at least one required). */
  public record UpdateStockCommand(UUID plantId, long version, BigDecimal stockOnHand,
      BigDecimal orderPoint, BigDecimal orderQty) {
  }

  /** POST /adjust signed-delta command. */
  public record AdjustStockCommand(UUID plantId, BigDecimal delta) {
  }

  private static UUID stockEntityId(SparepartStockEntity entity) {
    return UUID.nameUUIDFromBytes(
        (entity.getMaterialCode() + ":" + entity.getPlantId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static String entityLabel(SparepartStockEntity entity) {
    return entity.getMaterialCode() + " @" + entity.getPlantId();
  }

  private static Map<String, Object> stockValues(SparepartStockEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("materialCode", entity.getMaterialCode());
    values.put("plantId", entity.getPlantId());
    values.put("stockOnHand", entity.getStockOnHand());
    values.put("orderPoint", entity.getOrderPoint());
    values.put("orderQty", entity.getOrderQty());
    values.put("version", entity.getVersion());
    return values;
  }

  private static SparepartStock toDomain(SparepartStockEntity entity) {
    return new SparepartStock(entity.getMaterialCode(), entity.getPlantId(), entity.getStockOnHand(),
        entity.getOrderPoint(), entity.getOrderQty(), entity.getVersion(),
        entity.getCreatedAt(), entity.getUpdatedAt());
  }

  public static class SparepartStockNotFoundException extends RuntimeException {
  }

  /** Plant-scope or role rejection → 403 FORBIDDEN. */
  public static class SparepartStockForbiddenException extends RuntimeException {
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

  public static class SparepartStockValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public SparepartStockValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
