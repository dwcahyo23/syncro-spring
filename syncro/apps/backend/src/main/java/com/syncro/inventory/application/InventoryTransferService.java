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
import com.syncro.inventory.domain.InventoryTransferStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.inventory.infrastructure.db.InventoryTransferEntity;
import com.syncro.inventory.infrastructure.db.InventoryTransferRepository;
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
 * Inventory-transfer lifecycle service (blueprint E3, story 18-4). A transfer moves
 * {@code quantity} of one sparepart between two ACTIVE locations of the SAME plant
 * through an approval gate: create → {@code PENDING_APPROVAL}; approve →
 * {@code APPROVED} (source debit + destination credit in ONE transaction); reject →
 * {@code REJECTED} with a stored reason. Terminal states never re-open — any review
 * on a non-pending transfer is 409 INVALID_TRANSFER_TRANSITION.
 *
 * <p>Atomicity: the source debit runs as the existing atomic conditional update
 * ({@link InventoryStockBalanceRepository#adjustAvailableIfSufficient} with a signed
 * negative delta, guard {@code available - qty >= 0}); a zero-row result is 409
 * INSUFFICIENT_STOCK and the whole transaction rolls back — no partial move. The
 * destination credit is a create-or-increment upsert
 * ({@link InventoryStockBalanceRepository#creditTransferIn}) so a missing destination
 * row never needs a read-then-write. Both statements bump {@code version} in SQL,
 * bypassing the JPA optimistic lock the same way the stock-adjust path does.
 *
 * <p>Separation of duties: the requester ({@code requested_by}) can never approve or
 * reject their own transfer — 403 TRANSFER_SELF_REVIEW_FORBIDDEN, checked before any
 * stock movement. Reviewers need MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE plus the
 * transfer's plant in their assignment scope (SUPER_ADMIN exempt); creators need
 * INVENTORY_MAINTENANCE/STOREKEEPER/MANAGER_MAINTENANCE with BOTH locations' plant in
 * scope. Concurrent reviews serialize on {@code findByIdForUpdate} (the transfer row
 * has no {@code @Version} column — pattern anchor: story 18-1's sparepart review).
 *
 * <p>Location liveness: BOTH locations must be ACTIVE for a transfer to be created —
 * a deactivated store cannot send or receive stock (documented choice; mirrors
 * 18-3's {@code requireActiveLocation}, extended to the destination because a
 * transfer is a two-sided mutation). Inactive/unknown locations surface as 404
 * INVENTORY_LOCATION_NOT_FOUND; a sparepart outside the locations' plant surfaces as
 * 404 SPAREPART_NOT_FOUND (no cross-plant existence oracle, 18-3 parity).
 *
 * <p>Reads: the list is plant-scoped by joining the source location (creation
 * guarantees source.plant == destination.plant, so the source determines the plant —
 * the table has no plant column); a caller with no plant assignment sees an empty
 * list. Every mutation writes an audit record ({@link AuditEntityType#INVENTORY_TRANSFER});
 * an approve additionally audits both touched balance rows (epic-18: every stock
 * mutation is audit-logged).
 */
@Service
public class InventoryTransferService {

  private final InventoryTransferRepository transfers;
  private final InventoryStockBalanceRepository balances;
  private final InventoryLocationRepository locations;
  private final SparepartRepository spareparts;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public InventoryTransferService(InventoryTransferRepository transfers,
      InventoryStockBalanceRepository balances, InventoryLocationRepository locations,
      SparepartRepository spareparts, AuthUserPlantAssignmentRepository assignments,
      AuditLogWriter auditLog, Clock clock) {
    this.transfers = transfers;
    this.balances = balances;
    this.locations = locations;
    this.spareparts = spareparts;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * POST — request a transfer. Persists PENDING_APPROVAL with {@code requested_by}
   * taken from the authenticated user (never from the body) and audits CREATE.
   */
  @Transactional
  public TransferView create(AuthenticatedUser user, CreateTransferCommand command) {
    requireCreateRole(user);
    var source = requireActiveLocation(requireKnownLocation(command.sourceLocationId()));
    var destination = requireActiveLocation(requireKnownLocation(command.destinationLocationId()));
    if (source.getId().equals(destination.getId())) {
      throw new TransferValidationException(Map.of("destinationLocationId",
          "Source and destination locations must differ."));
    }
    if (!source.getPlantId().equals(destination.getPlantId())) {
      throw new TransferValidationException(Map.of("destinationLocationId",
          "Source and destination locations must belong to the same plant."));
    }
    requireCreatePlantScope(user, source.getPlantId());
    var quantity = requirePositiveQuantity(command.quantity());
    var sparepart = spareparts.findById(command.sparepartId())
        .orElseThrow(SparepartNotFoundException::new);
    requireSparepartInPlant(sparepart, source.getPlantId());
    var now = Instant.now(clock);
    var saved = transfers.saveAndFlush(new InventoryTransferEntity(UUID.randomUUID(),
        sparepart.getId(), source.getId(), destination.getId(), quantity,
        InventoryTransferStatus.PENDING_APPROVAL, UUID.fromString(user.id()), null, null, null,
        now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE,
        AuditEntityType.INVENTORY_TRANSFER, saved.getId(), transferLabel(sparepart, saved),
        source.getPlantId(), null, transferValues(saved), null));
    return toView(saved);
  }

  /**
   * POST /{id}/approve — PENDING_APPROVAL → APPROVED with the atomic stock move.
   * Order: role gate → locked read → SoD → plant scope → state machine → debit →
   * credit → status stamp → audits. A failed debit guard (0 rows) aborts everything.
   */
  @Transactional
  public TransferView approve(AuthenticatedUser user, UUID transferId) {
    requireReviewRole(user);
    var transfer = findForUpdate(transferId);
    requireNotSelfReview(user, transfer);
    var plantId = requireReviewPlantScope(user, transfer);
    if (transfer.getStatus() != InventoryTransferStatus.PENDING_APPROVAL) {
      throw new InvalidTransferTransitionException();
    }
    var sparepartId = transfer.getSparepartId();
    var sourceLocationId = transfer.getSourceLocationId();
    var destinationLocationId = transfer.getDestinationLocationId();
    var now = Instant.now(clock);
    var previous = transferValues(transfer);
    var quantity = transfer.getQuantity();

    // TOCTOU re-check (review pass): both locations must still be ACTIVE and the
    // sparepart must still belong to the locations' plant — a location deactivated
    // between create and approve must not receive/send stock.
    var sourceLocation = requireActiveLocation(requireKnownLocation(sourceLocationId));
    var destinationLocation = requireActiveLocation(requireKnownLocation(destinationLocationId));
    var sparepart = spareparts.findById(sparepartId).orElseThrow(SparepartNotFoundException::new);
    requireSparepartInPlant(sparepart, sourceLocation.getPlantId());

    // Both balance writes run FIRST as atomic SQL (each bumps version in-statement).
    // The conditional updates clear the persistence context, so every read/audit/merge
    // below happens against fresh state — no pending write can be lost to a clear.
    var sourceBefore = balances.findBySparepartIdAndLocationId(sparepartId, sourceLocationId)
        .orElse(null);
    var destinationBefore = balances.findBySparepartIdAndLocationId(sparepartId,
        destinationLocationId).orElse(null);

    // Canonical lock ordering (review pass): lock both balance rows in a
    // deterministic order (by location id, smallest first) before any write, so
    // two opposing transfers (X→Y and Y→X) cannot deadlock. The read-then-write
    // below is safe because the SELECT FOR UPDATE prevents concurrent writes on
    // either row. The debit is always applied BEFORE the credit so that an
    // insufficient-stock failure rolls back both operations (the credit never
    // runs ahead of the guard).
    UUID firstLoc = sourceLocationId.compareTo(destinationLocationId) < 0
        ? sourceLocationId : destinationLocationId;
    UUID secondLoc = firstLoc == sourceLocationId ? destinationLocationId : sourceLocationId;
    // Lock both rows explicitly (SELECT ... FOR UPDATE in the replication).
    balances.lockBySparepartAndLocation(sparepartId, firstLoc);
    balances.lockBySparepartAndLocation(sparepartId, secondLoc);
    // Source debit — 0 rows means insufficient available → 409, whole move rolls back.
    int debited = balances.adjustAvailableIfSufficient(sparepartId, sourceLocationId,
        quantity.negate(), now);
    if (debited == 0) {
      throw new InsufficientStockException();
    }
    // Destination credit — create-or-increment upsert (missing row starts at qty).
    balances.creditTransferIn(UUID.randomUUID(), sparepartId, destinationLocationId, quantity, now);

    var sourceAfter = balances.findBySparepartIdAndLocationId(sparepartId, sourceLocationId)
        .orElseThrow();
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, sourceAfter.getId(),
        balanceLabel(sparepart, sourceAfter), plantId,
        sourceBefore != null ? balanceValues(sparepart, sourceBefore) : null,
        balanceValues(sparepart, sourceAfter), null));
    var destinationAfter = balances.findBySparepartIdAndLocationId(sparepartId,
        destinationLocationId).orElseThrow();
    auditLog.record(user, new AuditRecord(
        destinationBefore == null ? AuditAction.CREATE : AuditAction.UPDATE,
        AuditEntityType.INVENTORY_STOCK_BALANCE, destinationAfter.getId(),
        balanceLabel(sparepart, destinationAfter), plantId,
        destinationBefore != null ? balanceValues(sparepart, destinationBefore) : null,
        balanceValues(sparepart, destinationAfter), null));

    // transfer is detached after the bulk-update clears — saveAndFlush merges it back.
    transfer.review(InventoryTransferStatus.APPROVED, UUID.fromString(user.id()), null, now, now);
    var saved = transfers.saveAndFlush(transfer);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_TRANSFER, saved.getId(), transferLabel(sparepart, saved),
        plantId, previous, transferValues(saved), null));
    return toView(saved);
  }

  /**
   * POST /{id}/reject — PENDING_APPROVAL → REJECTED with a required reason. No stock
   * movement happens on this path; the reviewer and reason are stamped and audited.
   */
  @Transactional
  public TransferView reject(AuthenticatedUser user, UUID transferId, String rejectionReason) {
    requireReviewRole(user);
    var reason = rejectionReason == null ? "" : rejectionReason.trim();
    if (reason.isEmpty()) {
      throw new TransferValidationException(Map.of("rejectionReason",
          "Rejection reason must not be blank."));
    }
    var transfer = findForUpdate(transferId);
    requireNotSelfReview(user, transfer);
    var plantId = requireReviewPlantScope(user, transfer);
    if (transfer.getStatus() != InventoryTransferStatus.PENDING_APPROVAL) {
      throw new InvalidTransferTransitionException();
    }
    var sparepart = spareparts.findById(transfer.getSparepartId())
        .orElseThrow(SparepartNotFoundException::new);
    var now = Instant.now(clock);
    var previous = transferValues(transfer);
    transfer.review(InventoryTransferStatus.REJECTED, UUID.fromString(user.id()), reason, now,
        now);
    var saved = transfers.saveAndFlush(transfer);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.INVENTORY_TRANSFER, saved.getId(), transferLabel(sparepart, saved),
        plantId, previous, transferValues(saved), null));
    return toView(saved);
  }

  /**
   * GET list — optional sparepartId/status filters, newest first. Plant-scoped
   * through the source location; SUPER_ADMIN sees every plant; a caller without any
   * plant assignment sees an empty list (never another plant's transfers).
   */
  @Transactional(readOnly = true)
  public List<TransferView> list(AuthenticatedUser user, UUID sparepartId,
      InventoryTransferStatus status) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return transfers.findAllFiltered(sparepartId, status).stream()
          .map(InventoryTransferService::toView)
          .toList();
    }
    var plantIds = assignedPlantIds(user);
    if (plantIds.isEmpty()) {
      return List.of();
    }
    return transfers.findScoped(plantIds, sparepartId, status).stream()
        .map(InventoryTransferService::toView)
        .toList();
  }

  /** GET one — read gate applies to the transfer's plant (403 outside scope). */
  @Transactional(readOnly = true)
  public TransferView get(AuthenticatedUser user, UUID transferId) {
    var transfer = transfers.findById(transferId)
        .orElseThrow(InventoryTransferNotFoundException::new);
    var plantId = requireSourcePlant(transfer);
    requireReadPlantScope(user, plantId);
    return toView(transfer);
  }

  // -------------------------------------------------------------------------
  // Gates
  // -------------------------------------------------------------------------

  /** Creation gate: INVENTORY_MAINTENANCE/STOREKEEPER/MANAGER_MAINTENANCE (+ SUPER_ADMIN). */
  private static void requireCreateRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN
        || user.applicationRole() == ApplicationRole.INVENTORY_MAINTENANCE
        || user.applicationRole() == ApplicationRole.STOREKEEPER
        || user.applicationRole() == ApplicationRole.MANAGER_MAINTENANCE) {
      return;
    }
    throw new TransferForbiddenException();
  }

  /** Review gate: MANAGER_MAINTENANCE/INVENTORY_MAINTENANCE (+ SUPER_ADMIN). STOREKEEPER cannot review. */
  private static void requireReviewRole(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN
        || user.applicationRole() == ApplicationRole.MANAGER_MAINTENANCE
        || user.applicationRole() == ApplicationRole.INVENTORY_MAINTENANCE) {
      return;
    }
    throw new TransferForbiddenException();
  }

  /** SoD: the requester can never review their own transfer — checked before any movement. */
  private static void requireNotSelfReview(AuthenticatedUser user, InventoryTransferEntity transfer) {
    if (transfer.getRequestedBy() != null
        && transfer.getRequestedBy().equals(UUID.fromString(user.id()))) {
      throw new TransferSelfReviewForbiddenException();
    }
  }

  private void requireCreatePlantScope(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new TransferForbiddenException();
    }
  }

  /** Reviewer plant scope — returns the transfer's plant (derived from the source location). */
  private UUID requireReviewPlantScope(AuthenticatedUser user, InventoryTransferEntity transfer) {
    var plantId = requireSourcePlant(transfer);
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return plantId;
    }
    if (!plantAssigned(user, plantId)) {
      throw new TransferForbiddenException();
    }
    return plantId;
  }

  private void requireReadPlantScope(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    if (!plantAssigned(user, plantId)) {
      throw new TransferForbiddenException();
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

  /** Locked read for review transitions — serializes concurrent approve/reject (story 18-4). */
  private InventoryTransferEntity findForUpdate(UUID transferId) {
    return transfers.findByIdForUpdate(transferId)
        .orElseThrow(InventoryTransferNotFoundException::new);
  }

  /** The transfer's plant, derived through the source location (FK guarantees existence). */
  private UUID requireSourcePlant(InventoryTransferEntity transfer) {
    return requireKnownLocation(transfer.getSourceLocationId()).getPlantId();
  }

  private InventoryLocationEntity requireKnownLocation(UUID locationId) {
    return locations.findById(locationId).orElseThrow(InventoryLocationNotFoundException::new);
  }

  /** Both sides of a transfer must be ACTIVE (documented choice — see class javadoc). */
  private static InventoryLocationEntity requireActiveLocation(InventoryLocationEntity location) {
    if (!location.isActive()) {
      throw new InventoryLocationNotFoundException();
    }
    return location;
  }

  /** 18-3 parity: a part outside the locations' plant is indistinguishable from unknown. */
  private static void requireSparepartInPlant(SparepartEntity sparepart, UUID plantId) {
    var machine = sparepart.getMachine();
    var partPlantId = machine != null && machine.getPlant() != null
        ? machine.getPlant().getId()
        : null;
    if (!plantId.equals(partPlantId)) {
      throw new SparepartNotFoundException();
    }
  }

  /** Business invariant behind the DTO's @Positive and the DB CHECK quantity > 0.
   * Also rejects more than 2 fraction digits — @Digits(integer=16, fraction=2) only
   * enforces the integer part at runtime, and NUMERIC(18,2) would silently round
   * the remainder. */
  private static BigDecimal requirePositiveQuantity(BigDecimal quantity) {
    if (quantity == null || quantity.signum() <= 0) {
      throw new TransferValidationException(Map.of("quantity",
          "Quantity must be greater than zero."));
    }
    if (quantity.scale() > 2) {
      throw new TransferValidationException(Map.of("quantity",
          "Quantity must have at most 2 decimal places."));
    }
    return quantity;
  }

  private static String transferLabel(SparepartEntity sparepart, InventoryTransferEntity transfer) {
    var code = sparepart.getMaterialCode() != null ? sparepart.getMaterialCode() : sparepart.getCode();
    return code + " " + transfer.getSourceLocationId() + "->" + transfer.getDestinationLocationId();
  }

  private static String balanceLabel(SparepartEntity sparepart, InventoryStockBalanceEntity entity) {
    var code = sparepart.getMaterialCode() != null ? sparepart.getMaterialCode() : sparepart.getCode();
    return code + " @" + entity.getLocationId();
  }

  private static Map<String, Object> transferValues(InventoryTransferEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("sparepartId", entity.getSparepartId().toString());
    values.put("sourceLocationId", entity.getSourceLocationId().toString());
    values.put("destinationLocationId", entity.getDestinationLocationId().toString());
    values.put("quantity", entity.getQuantity());
    values.put("status", entity.getStatus().name());
    values.put("requestedBy", entity.getRequestedBy() != null ? entity.getRequestedBy().toString() : null);
    values.put("reviewedBy", entity.getReviewedBy() != null ? entity.getReviewedBy().toString() : null);
    values.put("rejectionReason", entity.getRejectionReason());
    values.put("reviewedAt", entity.getReviewedAt() != null ? entity.getReviewedAt().toString() : null);
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

  private static TransferView toView(InventoryTransferEntity entity) {
    return new TransferView(entity.getId(), entity.getSparepartId(), entity.getSourceLocationId(),
        entity.getDestinationLocationId(), entity.getQuantity(), entity.getStatus(),
        entity.getRequestedBy(), entity.getReviewedBy(), entity.getRejectionReason(),
        entity.getReviewedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands, read model, exceptions
  // -------------------------------------------------------------------------

  /** POST command — requested_by is never part of it (taken from the authenticated user). */
  public record CreateTransferCommand(UUID sparepartId, UUID sourceLocationId,
      UUID destinationLocationId, BigDecimal quantity) {
  }

  /** Service read model (the api layer maps it to the response DTO). */
  public record TransferView(UUID id, UUID sparepartId, UUID sourceLocationId,
      UUID destinationLocationId, BigDecimal quantity, InventoryTransferStatus status,
      UUID requestedBy, UUID reviewedBy, String rejectionReason, Instant reviewedAt,
      Instant createdAt, Instant updatedAt) {
  }

  /** Role/scope rejection → 403 FORBIDDEN. */
  public static class TransferForbiddenException extends RuntimeException {
  }

  /** Requester reviewing their own transfer → 403 TRANSFER_SELF_REVIEW_FORBIDDEN (SoD). */
  public static class TransferSelfReviewForbiddenException extends RuntimeException {
  }

  /** Unknown transfer id → 404 INVENTORY_TRANSFER_NOT_FOUND. */
  public static class InventoryTransferNotFoundException extends RuntimeException {
  }

  /** Review on a non-PENDING_APPROVAL transfer → 409 INVALID_TRANSFER_TRANSITION. */
  public static class InvalidTransferTransitionException extends RuntimeException {
  }

  /** Source debit matched 0 rows → 409 INSUFFICIENT_STOCK (whole move rolled back). */
  public static class InsufficientStockException extends RuntimeException {
  }

  /** qty ≤ 0, same source/dest, cross-plant locations, blank reason → 400 VALIDATION_ERROR. */
  public static class TransferValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public TransferValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
