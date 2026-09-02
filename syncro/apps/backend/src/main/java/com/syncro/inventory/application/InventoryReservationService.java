package com.syncro.inventory.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.domain.InventoryReservationStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryReservationEntity;
import com.syncro.inventory.infrastructure.db.InventoryReservationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory-reservation lifecycle service (blueprint E4, story 18-5). A reservation
 * holds {@code quantity} of one sparepart at one location against a downstream
 * reference ({@code referenceType}/{@code referenceId} — WORK_ORDER first). Creating
 * it atomically moves stock available → reserved at the location; consuming draws the
 * remaining quantity down (reserved → available); cancel returns the remaining
 * quantity to available. Lifecycle: ACTIVE → CONSUMED | CANCELLED | EXPIRED; terminal
 * states reject further transitions (409 INVALID_RESERVATION_TRANSITION).
 *
 * <p>Transfer interlock (FR-146c): reservations reduce the balance's {@code available}
 * column at create time, so {@code available} ALREADY excludes reserved stock. The
 * transfer approve (story 18-4) gates the source move on {@code available - qty >= 0}
 * and never touches {@code reserved} — reserved stock can therefore never be moved by
 * a transfer; no interlock code is needed beyond this documented column-semantics
 * contract.
 *
 * <p>Atomicity: every balance move is a single-statement conditional UPDATE
 * (version-bypass idiom — {@code moveToReserved} / {@code moveFromReserved}), guarded
 * by {@code available - qty >= 0} / {@code reserved >= qty} respectively; a zero-row
 * result is 409 INSUFFICIENT_STOCK and the whole transaction rolls back (no partial
 * reservation / release). The reservation row is read with a pessimistic lock
 * ({@link InventoryReservationRepository#findByIdForUpdate}) on consume/cancel so the
 * ACTIVE precondition cannot pass twice.
 *
 * <p>Expiry is evaluated on access (get/consume/cancel), never by a scheduler: an
 * ACTIVE reservation whose {@code expires_at} is in the past has its remaining
 * quantity returned to available and is marked EXPIRED on first access. Idempotent —
 * an already EXPIRED reservation is never touched again. Expiry writes are audited
 * with a SYSTEM actor (time-based transition, no operator action — consumeOnPickup
 * parity).
 *
 * <p>Gates: create/consume/cancel need INVENTORY_MAINTENANCE/STOREKEEPER/
 * MANAGER_MAINTENANCE with the location's plant in assignment scope (SUPER_ADMIN
 * bypass). Reads are open to any user assigned to the plant. Every mutation is
 * audit-logged with previous + new state
 * ({@link AuditEntityType#INVENTORY_RESERVATION}) and the touched balance row
 * (epic-18: every stock mutation is audit-logged).
 */
@Service
public class InventoryReservationService {

  private final InventoryReservationRepository reservations;
  private final InventoryStockBalanceRepository balances;
  private final InventoryLocationRepository locations;
  private final SparepartRepository spareparts;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public InventoryReservationService(InventoryReservationRepository reservations,
      InventoryStockBalanceRepository balances, InventoryLocationRepository locations,
      SparepartRepository spareparts, AuthUserPlantAssignmentRepository assignments,
      AuditLogWriter auditLog, Clock clock) {
    this.reservations = reservations;
    this.balances = balances;
    this.locations = locations;
    this.spareparts = spareparts;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * POST — create an ACTIVE reservation. Atomically moves {@code available → reserved}
   * on the balance row (single conditional UPDATE, guard {@code available - qty >= 0})
   * in the SAME transaction as the reservation INSERT; a zero-row move aborts
   * everything with 409 INSUFFICIENT_STOCK — no partial reservation. {@code requested_by}
   * comes from the authenticated user (never the body).
   */
  @Transactional
  public ReservationView create(AuthenticatedUser user, CreateReservationCommand command) {
    requireMutationRole(user);
    var referenceType = requireReferenceType(command.referenceType());
    var referenceId = requireReferenceId(command.referenceId());
    var quantity = requirePositiveQuantity(command.quantity());
    var location = requireActiveLocation(requireKnownLocation(command.locationId()));
    requirePlantScope(user, location.getPlantId());
    var sparepart = spareparts.findById(command.sparepartId())
        .orElseThrow(SparepartNotFoundException::new);
    requireSparepartInPlant(sparepart, location.getPlantId());
    var now = Instant.now(clock);

    // Atomic available -> reserved move; 0 rows = insufficient (or missing) balance row.
    int moved = balances.moveToReserved(sparepart.getId(), location.getId(), quantity, now);
    if (moved == 0) {
      throw new InsufficientStockException();
    }

    var saved = reservations.saveAndFlush(new InventoryReservationEntity(UUID.randomUUID(),
        sparepart.getId(), location.getId(), quantity, quantity,
        InventoryReservationStatus.ACTIVE, referenceType, referenceId,
        UUID.fromString(user.id()), null, null, command.expiresAt(), now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE,
        AuditEntityType.INVENTORY_RESERVATION, saved.getId(),
        reservationLabel(sparepart, saved), location.getPlantId(), null,
        reservationValues(saved), null));
    auditBalance(user, sparepart, location.getId(), location.getPlantId());
    return toView(saved);
  }

  /**
   * POST /{id}/consume — partial or full draw-down of the reservation. The remaining
   * quantity moves reserved → available by the consumed amount (the balance's
   * {@code consumed} running total is untouched; reservations keep their own ledger).
   * Over-consume (qty &gt; remaining) → 400 VALIDATION_ERROR; consuming past expiry or a
   * terminal reservation → 409. The row is locked ({@code findByIdForUpdate}) and the
   * ACTIVE precondition is checked inside the same transaction as the balance move.
   */
  @Transactional
  public ReservationView consume(AuthenticatedUser user, UUID reservationId,
      BigDecimal quantity) {
    requireMutationRole(user);
    var amount = requirePositiveQuantity(quantity);
    var reservation = findForUpdate(reservationId);
    var plantId = requireReservationPlant(reservation);
    requirePlantScope(user, plantId);
    expireIfNeeded(reservation, plantId);
    if (reservation.getStatus() != InventoryReservationStatus.ACTIVE) {
      throw new InvalidReservationTransitionException();
    }
    if (amount.compareTo(reservation.getRemainingQuantity()) > 0) {
      throw new ReservationValidationException(Map.of("quantity",
          "Quantity must not exceed the remaining reservation quantity."));
    }
    var sparepart = spareparts.findById(reservation.getSparepartId())
        .orElseThrow(SparepartNotFoundException::new);
    var now = Instant.now(clock);
    var previous = reservationValues(reservation);

    // Consumed quantity moves reserved -> consumed (NOT back to available — it is
    // used against the reference). 0 rows = reserved no longer covers.
    int moved = balances.consumeFromReserved(reservation.getSparepartId(),
        reservation.getLocationId(), amount, now);
    if (moved == 0) {
      throw new InsufficientStockException();
    }

    reservation.consume(amount, UUID.fromString(user.id()), now);
    var saved = reservations.saveAndFlush(reservation);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_RESERVATION, saved.getId(),
        reservationLabel(sparepart, saved), plantId, previous, reservationValues(saved), null));
    auditBalance(user, sparepart, reservation.getLocationId(), plantId);
    return toView(saved);
  }

  /**
   * POST /{id}/cancel — returns the remaining quantity to available (reserved →
   * available by {@code remaining_quantity}) and stamps {@code cancelled_by}. The
   * row is locked and the ACTIVE precondition checked in the same transaction; a
   * cancelled/consumed/expired reservation → 409.
   */
  @Transactional
  public ReservationView cancel(AuthenticatedUser user, UUID reservationId) {
    requireMutationRole(user);
    var reservation = findForUpdate(reservationId);
    var plantId = requireReservationPlant(reservation);
    requirePlantScope(user, plantId);
    expireIfNeeded(reservation, plantId);
    if (reservation.getStatus() != InventoryReservationStatus.ACTIVE) {
      throw new InvalidReservationTransitionException();
    }
    var sparepart = spareparts.findById(reservation.getSparepartId())
        .orElseThrow(SparepartNotFoundException::new);
    var now = Instant.now(clock);
    var previous = reservationValues(reservation);
    var remaining = reservation.getRemainingQuantity();

    int moved = balances.moveFromReserved(reservation.getSparepartId(),
        reservation.getLocationId(), remaining, now);
    if (moved == 0) {
      throw new InsufficientStockException();
    }

    reservation.cancel(UUID.fromString(user.id()), now);
    var saved = reservations.saveAndFlush(reservation);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_RESERVATION, saved.getId(),
        reservationLabel(sparepart, saved), plantId, previous, reservationValues(saved), null));
    auditBalance(user, sparepart, reservation.getLocationId(), plantId);
    return toView(saved);
  }

  /**
   * GET one — expiry evaluated on access: an ACTIVE reservation past its expires_at is
   * expired (remaining returned to available) before the view is returned, so reads
   * report EXPIRED and stock has already been released. Read gate: plant assignment.
   */
  @Transactional
  public ReservationView get(AuthenticatedUser user, UUID reservationId) {
    var reservation = reservations.findById(reservationId)
        .orElseThrow(InventoryReservationNotFoundException::new);
    var plantId = requireReservationPlant(reservation);
    requireReadPlantScope(user, plantId);
    expireIfNeeded(reservation, plantId);
    return toView(reservation);
  }

  /**
   * GET list — optional sparepartId/locationId/status/referenceType/referenceId filters,
   * newest first. Plant-scoped through the location; SUPER_ADMIN sees every plant; a
   * caller without any plant assignment sees an empty list.
   */
  @Transactional(readOnly = true)
  public List<ReservationView> list(AuthenticatedUser user, UUID sparepartId, UUID locationId,
      InventoryReservationStatus status, String referenceType, String referenceId) {
    var type = normalizeReferenceType(referenceType);
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return reservations.findAllFiltered(sparepartId, locationId, status, type, referenceId)
          .stream().map(InventoryReservationService::toView).toList();
    }
    var plantIds = assignedPlantIds(user);
    if (plantIds.isEmpty()) {
      return List.of();
    }
    return reservations.findScoped(plantIds, sparepartId, locationId, status, type, referenceId)
        .stream().map(InventoryReservationService::toView).toList();
  }

  // -------------------------------------------------------------------------
  // Expiry-on-access
  // -------------------------------------------------------------------------

  /**
   * First-access expiry (design note: no scheduler in this story). When the ACTIVE
   * reservation's {@code expires_at} is in the past, the remaining quantity is returned
   * to available (single conditional UPDATE) and the reservation is marked EXPIRED.
   * Idempotent: an already-EXPIRED row (or any non-ACTIVE row) is never touched again.
   * Audited with a SYSTEM actor — the transition is time-driven, not an operator action.
   */
  private void expireIfNeeded(InventoryReservationEntity reservation, UUID plantId) {
    if (reservation.getStatus() != InventoryReservationStatus.ACTIVE
        || reservation.getExpiresAt() == null
        || !reservation.getExpiresAt().isBefore(Instant.now(clock))) {
      return;
    }
    var sparepart = spareparts.findById(reservation.getSparepartId())
        .orElseThrow(SparepartNotFoundException::new);
    var now = Instant.now(clock);
    var previous = reservationValues(reservation);
    var remaining = reservation.getRemainingQuantity();

    int moved = balances.moveFromReserved(reservation.getSparepartId(),
        reservation.getLocationId(), remaining, now);
    if (moved == 0) {
      throw new InsufficientStockException();
    }
    reservation.expire(now);
    var saved = reservations.saveAndFlush(reservation);
    auditLog.recordSystem(new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_RESERVATION, saved.getId(),
        reservationLabel(sparepart, saved), plantId, previous, reservationValues(saved), null));
    auditBalanceSystem(sparepart, reservation.getLocationId(), plantId);
  }

  // -------------------------------------------------------------------------
  // Gates
  // -------------------------------------------------------------------------

  /** Mutation gate: INVENTORY_MAINTENANCE/STOREKEEPER/MANAGER_MAINTENANCE (+ SUPER_ADMIN). */
  private static void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN
        || user.applicationRole() == ApplicationRole.INVENTORY_MAINTENANCE
        || user.applicationRole() == ApplicationRole.STOREKEEPER
        || user.applicationRole() == ApplicationRole.MANAGER_MAINTENANCE) {
      return;
    }
    throw new ReservationForbiddenException();
  }

  private void requirePlantScope(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new ReservationForbiddenException();
    }
  }

  private void requireReadPlantScope(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new ReservationForbiddenException();
    }
  }

  private boolean plantAssigned(AuthenticatedUser user, UUID plantId) {
    return assignedPlantIds(user).contains(plantId);
  }

  private List<UUID> assignedPlantIds(AuthenticatedUser user) {
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(assignment -> assignment.getPlantId())
        .distinct()
        .toList();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Locked read for lifecycle transitions — serializes concurrent consume/cancel. */
  private InventoryReservationEntity findForUpdate(UUID reservationId) {
    return reservations.findByIdForUpdate(reservationId)
        .orElseThrow(InventoryReservationNotFoundException::new);
  }

  /** The reservation's plant, derived through the location (FK guarantees existence). */
  private UUID requireReservationPlant(InventoryReservationEntity reservation) {
    return requireKnownLocation(reservation.getLocationId()).getPlantId();
  }

  private InventoryLocationEntity requireKnownLocation(UUID locationId) {
    return locations.findById(locationId).orElseThrow(InventoryLocationNotFoundException::new);
  }

  /** Mutations reject an inactive location (404 — same shape as unknown). */
  private static InventoryLocationEntity requireActiveLocation(InventoryLocationEntity location) {
    if (!location.isActive()) {
      throw new InventoryLocationNotFoundException();
    }
    return location;
  }

  /** 18-3 parity: a part outside the location's plant is indistinguishable from unknown. */
  private static void requireSparepartInPlant(SparepartEntity sparepart, UUID plantId) {
    var machine = sparepart.getMachine();
    var partPlantId = machine != null && machine.getPlant() != null
        ? machine.getPlant().getId()
        : null;
    if (!plantId.equals(partPlantId)) {
      throw new SparepartNotFoundException();
    }
  }

  /** Design note: reference_type is an uppercase string (WORK_ORDER first; ≤50 chars). */
  private static String requireReferenceType(String referenceType) {
    if (referenceType == null || referenceType.trim().isEmpty()) {
      throw new ReservationValidationException(Map.of("referenceType",
          "Reference type must not be blank."));
    }
    var trimmed = referenceType.trim();
    if (trimmed.length() > 50) {
      throw new ReservationValidationException(Map.of("referenceType",
          "Reference type must be at most 50 characters."));
    }
    return trimmed.toUpperCase(Locale.ROOT);
  }

  private static String requireReferenceId(String referenceId) {
    if (referenceId == null || referenceId.trim().isEmpty()) {
      throw new ReservationValidationException(Map.of("referenceId",
          "Reference id must not be blank."));
    }
    var trimmed = referenceId.trim();
    if (trimmed.length() > 64) {
      throw new ReservationValidationException(Map.of("referenceId",
          "Reference id must be at most 64 characters."));
    }
    return trimmed;
  }

  /** Query-param counterpart of {@link #requireReferenceType}: optional, still uppercased. */
  private static String normalizeReferenceType(String referenceType) {
    if (referenceType == null || referenceType.trim().isEmpty()) {
      return null;
    }
    return referenceType.trim().toUpperCase(Locale.ROOT);
  }

  /** Business invariant behind the DTO's @Positive and the DB CHECK quantity > 0. */
  private static BigDecimal requirePositiveQuantity(BigDecimal quantity) {
    if (quantity == null || quantity.signum() <= 0) {
      throw new ReservationValidationException(Map.of("quantity",
          "Quantity must be greater than zero."));
    }
    if (quantity.scale() > 2) {
      throw new ReservationValidationException(Map.of("quantity",
          "Quantity must have at most 2 decimal places."));
    }
    return quantity;
  }

  /** Reads + audits the touched balance row after a user-driven mutation (epic-18). */
  private void auditBalance(AuthenticatedUser user, SparepartEntity sparepart, UUID locationId,
      UUID plantId) {
    var after = balances.findBySparepartIdAndLocationId(sparepart.getId(), locationId).orElse(null);
    if (after == null) {
      return;
    }
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, after.getId(), balanceLabel(sparepart, after),
        plantId, null, balanceValues(sparepart, after), null));
  }

  /** SYSTEM-actor variant for the time-driven expiry release. */
  private void auditBalanceSystem(SparepartEntity sparepart, UUID locationId, UUID plantId) {
    var after = balances.findBySparepartIdAndLocationId(sparepart.getId(), locationId).orElse(null);
    if (after == null) {
      return;
    }
    auditLog.recordSystem(new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, after.getId(), balanceLabel(sparepart, after),
        plantId, null, balanceValues(sparepart, after), null));
  }

  private static String reservationLabel(SparepartEntity sparepart,
      InventoryReservationEntity entity) {
    var code = sparepart.getMaterialCode() != null ? sparepart.getMaterialCode() : sparepart.getCode();
    return code + " @" + entity.getLocationId() + " ref=" + entity.getReferenceType();
  }

  private static String balanceLabel(SparepartEntity sparepart, InventoryStockBalanceEntity entity) {
    var code = sparepart.getMaterialCode() != null ? sparepart.getMaterialCode() : sparepart.getCode();
    return code + " @" + entity.getLocationId();
  }

  private static Map<String, Object> reservationValues(InventoryReservationEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("sparepartId", entity.getSparepartId().toString());
    values.put("locationId", entity.getLocationId().toString());
    values.put("quantity", entity.getQuantity());
    values.put("remainingQuantity", entity.getRemainingQuantity());
    values.put("status", entity.getStatus().name());
    values.put("referenceType", entity.getReferenceType());
    values.put("referenceId", entity.getReferenceId());
    values.put("requestedBy", entity.getRequestedBy() != null ? entity.getRequestedBy().toString() : null);
    values.put("consumedBy", entity.getConsumedBy() != null ? entity.getConsumedBy().toString() : null);
    values.put("cancelledBy", entity.getCancelledBy() != null ? entity.getCancelledBy().toString() : null);
    values.put("expiresAt", entity.getExpiresAt() != null ? entity.getExpiresAt().toString() : null);
    return values;
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

  private static ReservationView toView(InventoryReservationEntity entity) {
    return new ReservationView(entity.getId(), entity.getSparepartId(), entity.getLocationId(),
        entity.getQuantity(), entity.getRemainingQuantity(), entity.getStatus(),
        entity.getReferenceType(), entity.getReferenceId(), entity.getRequestedBy(),
        entity.getConsumedBy(), entity.getCancelledBy(), entity.getExpiresAt(),
        entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands, read model, exceptions
  // -------------------------------------------------------------------------

  /** POST command — requested_by is never part of it (taken from the authenticated user). */
  public record CreateReservationCommand(UUID sparepartId, UUID locationId, BigDecimal quantity,
      String referenceType, String referenceId, Instant expiresAt) {
  }

  /** Service read model (the api layer maps it to the response DTO). */
  public record ReservationView(UUID id, UUID sparepartId, UUID locationId, BigDecimal quantity,
      BigDecimal remainingQuantity, InventoryReservationStatus status, String referenceType,
      String referenceId, UUID requestedBy, UUID consumedBy, UUID cancelledBy, Instant expiresAt,
      Instant createdAt, Instant updatedAt) {
  }

  /** Role/scope rejection → 403 FORBIDDEN. */
  public static class ReservationForbiddenException extends RuntimeException {
  }

  /** Unknown reservation id → 404 INVENTORY_RESERVATION_NOT_FOUND. */
  public static class InventoryReservationNotFoundException extends RuntimeException {
  }

  /** Transition on a non-ACTIVE (terminal or expired) reservation → 409. */
  public static class InvalidReservationTransitionException extends RuntimeException {
  }

  /** Balance move matched 0 rows → 409 INSUFFICIENT_STOCK (whole transaction rolled back). */
  public static class InsufficientStockException extends RuntimeException {
  }

  /** qty ≤ 0 / over-consume / blank or oversized reference → 400 VALIDATION_ERROR. */
  public static class ReservationValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ReservationValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}