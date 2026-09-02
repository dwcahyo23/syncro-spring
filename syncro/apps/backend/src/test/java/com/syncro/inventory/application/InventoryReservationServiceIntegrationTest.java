package com.syncro.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogEntity;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryReservationService.CreateReservationCommand;
import com.syncro.inventory.application.InventoryReservationService.InsufficientStockException;
import com.syncro.inventory.application.InventoryReservationService.InvalidReservationTransitionException;
import com.syncro.inventory.application.InventoryReservationService.InventoryReservationNotFoundException;
import com.syncro.inventory.application.InventoryReservationService.ReservationForbiddenException;
import com.syncro.inventory.application.InventoryReservationService.ReservationValidationException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.domain.InventoryReservationStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryReservationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Story 18-5: the inventory-reservation lifecycle against a real Postgres
 * (Testcontainers). Covers every row of the spec's I/O matrix at the service
 * boundary: create atomically moves available → reserved and audits CREATE;
 * insufficient available → 409 with NO partial state; partial then full consume
 * draws remaining to 0 with consumed_by stamped and reserved released; cancel
 * returns remaining to available with cancelled_by stamped; terminal transitions →
 * 409 INVALID_RESERVATION_TRANSITION; expiry on access returns stock and reports
 * EXPIRED (with consume-after-expiry → 409); unknown refs / inactive location →
 * 404; wrong role / out-of-scope → 403; over-consume / qty ≤ 0 / blank reference →
 * 400; plant-scoped list with all filters; and the transfer interlock (FR-146c) —
 * a transfer approve never touches the reserved column. The concurrency guard (two
 * threads consume the same reservation, exactly one succeeds) lives in
 * {@code InventoryReservationConsumeConcurrencyIntegrationTest} — it needs committed
 * data and independent transactions, which the {@code @Transactional} base class here
 * cannot provide.
 */
class InventoryReservationServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired
  private InventoryReservationService service;
  @Autowired
  private InventoryTransferService transferService;
  @Autowired
  private InventoryReservationRepository reservations;
  @Autowired
  private InventoryLocationRepository locations;
  @Autowired
  private InventoryStockBalanceRepository balances;
  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private SparepartTaxonomyRepository taxonomy;
  @Autowired
  private SparepartRepository spareparts;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private AuthUserPlantAssignmentRepository assignments;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private PasswordEncoder passwordEncoder;

  private final ObjectMapper mapper = new ObjectMapper();

  // --- Create ------------------------------------------------------------------

  @Test
  @DisplayName("18.5-SVC-001 P0 create reserves stock atomically (available 6 / reserved 4) and audits CREATE")
  void createMovesStockAndAudits() throws Exception {
    var fixture = fixture("RSV-P1");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var user = mutationUser(fixture);

    var created = service.create(user, new CreateReservationCommand(fixture.sparepartId,
        fixture.locationId, new BigDecimal("4"), "WORK_ORDER", "WO-001", null));

    assertThat(created.status()).isEqualTo(InventoryReservationStatus.ACTIVE);
    assertThat(created.quantity()).isEqualByComparingTo("4");
    assertThat(created.remainingQuantity()).isEqualByComparingTo("4");
    assertThat(created.requestedBy()).isEqualTo(UUID.fromString(user.id()));
    assertThat(created.consumedBy()).isNull();
    assertThat(created.cancelledBy()).isNull();
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("6");
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("4");
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_RESERVATION);
    assertThat(entry.getPlantId()).isEqualTo(fixture.plant.getId());
    assertThat(mapper.readTree(entry.getNewValue()).get("status").asText()).isEqualTo("ACTIVE");
    assertThat(mapper.readTree(entry.getNewValue()).get("referenceType").asText())
        .isEqualTo("WORK_ORDER");
    // The touched balance row is audited too (epic-18: every stock mutation is audited).
    var balanceAudit = latestAuditEntryFor(row.getId());
    assertThat(balanceAudit.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_STOCK_BALANCE);
    assertThat(mapper.readTree(balanceAudit.getNewValue()).get("reserved").decimalValue())
        .isEqualByComparingTo("4");
  }

  @Test
  @DisplayName("18.5-SVC-002 P0 create with insufficient available → 409 INSUFFICIENT_STOCK, no partial state")
  void createInsufficientStockLeavesNoTrace() {
    var fixture = fixture("RSV-P2");
    var row = balance(fixture.sparepartId, fixture.locationId, "3");
    var user = mutationUser(fixture);

    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("4"), "WORK_ORDER", "WO-002",
        null)))
        .isInstanceOf(InsufficientStockException.class);
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("3");
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("0");
    assertThat(reservations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("18.5-SVC-003 P0 create rejects qty <= 0 and blank/oversized references → 400")
  void createRejectsInvalidInputs() {
    var fixture = fixture("RSV-P3");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var user = mutationUser(fixture);

    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, BigDecimal.ZERO, "WORK_ORDER", "WO-003", null)))
        .isInstanceOf(ReservationValidationException.class);
    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("2"), "  ", "WO-003", null)))
        .isInstanceOf(ReservationValidationException.class);
    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "  ", null)))
        .isInstanceOf(ReservationValidationException.class);
    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("2"), "X".repeat(51), "WO-003",
        null)))
        .isInstanceOf(ReservationValidationException.class);
    assertThat(reservations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("18.5-SVC-004 P0 create rejects unknown refs and inactive locations → 404")
  void createRejectsUnknownRefs() {
    var fixture = fixture("RSV-P4");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var user = mutationUser(fixture);

    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        UUID.randomUUID(), fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "WO-004", null)))
        .isInstanceOf(SparepartNotFoundException.class);
    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, UUID.randomUUID(), new BigDecimal("2"), "WORK_ORDER", "WO-004",
        null)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    deactivate(fixture.locationId, fixture.plant);
    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "WO-004",
        null)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  @Test
  @DisplayName("18.5-SVC-005 P0 create with a sparepart from another plant → 404 (no cross-plant oracle)")
  void createRejectsForeignPlantSparepart() {
    var fixture = fixture("RSV-P5");
    var foreignPart = sparepart(plant("RSV-P5B"), "MC-P5B");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var user = mutationUser(fixture);

    assertThatThrownBy(() -> service.create(user, new CreateReservationCommand(
        foreignPart.getId(), fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "WO-005",
        null)))
        .isInstanceOf(SparepartNotFoundException.class);
  }

  @Test
  @DisplayName("18.5-SVC-006 P0 create gates role and plant scope → 403")
  void createGatesRoleAndScope() {
    var fixture = fixture("RSV-P6");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var tech = persistedUser(ApplicationRole.TECHNICIAN, "rsv-tech@syncro.dev");
    assign(tech, fixture.plant);
    assertThatThrownBy(() -> service.create(tech, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "WO-006",
        null)))
        .isInstanceOf(ReservationForbiddenException.class);
    var outsider = persistedUser(ApplicationRole.STOREKEEPER, "rsv-outsider@syncro.dev");
    assign(outsider, plant("RSV-P6B"));
    assertThatThrownBy(() -> service.create(outsider, new CreateReservationCommand(
        fixture.sparepartId, fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "WO-006",
        null)))
        .isInstanceOf(ReservationForbiddenException.class);
  }

  // --- Consume -----------------------------------------------------------------

  @Test
  @DisplayName("18.5-SVC-007 P0 partial consume draws remaining down and releases reserved")
  void partialConsumeReleasesReserved() {
    var fixture = fixture("RSV-P7");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-007", null);
    var user = mutationUser(fixture);

    var consumed = service.consume(user, created.id(), new BigDecimal("2"));

    assertThat(consumed.status()).isEqualTo(InventoryReservationStatus.ACTIVE);
    assertThat(consumed.remainingQuantity()).isEqualByComparingTo("2");
    assertThat(consumed.consumedBy()).isEqualTo(UUID.fromString(user.id()));
    // Consumed: reserved 4 -> 2; consumed 0 -> 2; available stays at 6.
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("2");
    assertThat(balances.findById(row.getId()).orElseThrow().getConsumed())
        .isEqualByComparingTo("2");
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("6");
  }

  @Test
  @DisplayName("18.5-SVC-008 P0 full consume reaches CONSUMED with consumed_by stamped")
  void fullConsumeReachesConsumed() {
    var fixture = fixture("RSV-P8");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-008", null);
    var user = mutationUser(fixture);

    var consumed = service.consume(user, created.id(), new BigDecimal("4"));

    assertThat(consumed.status()).isEqualTo(InventoryReservationStatus.CONSUMED);
    assertThat(consumed.remainingQuantity()).isEqualByComparingTo("0");
    assertThat(consumed.consumedBy()).isEqualTo(UUID.fromString(user.id()));
    // Full consume: reserved 4 -> 0; consumed 0 -> 4; available stays at 6.
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("0");
    assertThat(balances.findById(row.getId()).orElseThrow().getConsumed())
        .isEqualByComparingTo("4");
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("6");
  }

  @Test
  @DisplayName("18.5-SVC-009 P0 over-consume (qty > remaining) → 400 VALIDATION_ERROR")
  void overConsumeRejected() {
    var fixture = fixture("RSV-P9");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-009", null);
    var user = mutationUser(fixture);

    assertThatThrownBy(() -> service.consume(user, created.id(), new BigDecimal("5")))
        .isInstanceOf(ReservationValidationException.class);
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("4");
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("6");
  }

  @Test
  @DisplayName("18.5-SVC-010 P0 consume audits UPDATE with previous + new state")
  void consumeAuditsUpdate() throws Exception {
    var fixture = fixture("RSV-P10");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-010", null);
    var user = mutationUser(fixture);

    service.consume(user, created.id(), new BigDecimal("4"));

    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_RESERVATION);
    assertThat(mapper.readTree(entry.getPreviousValue()).get("remainingQuantity").decimalValue())
        .isEqualByComparingTo("4");
    assertThat(mapper.readTree(entry.getNewValue()).get("remainingQuantity").decimalValue())
        .isEqualByComparingTo("0");
    assertThat(mapper.readTree(entry.getNewValue()).get("status").asText())
        .isEqualTo("CONSUMED");
  }

  // --- Cancel ------------------------------------------------------------------

  @Test
  @DisplayName("18.5-SVC-011 P0 cancel returns remaining to available with cancelled_by stamped")
  void cancelReturnsRemaining() {
    var fixture = fixture("RSV-P11");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-011", null);
    var user = mutationUser(fixture);
    service.consume(user, created.id(), new BigDecimal("1")); // remaining 3

    var cancelled = service.cancel(user, created.id());

    assertThat(cancelled.status()).isEqualTo(InventoryReservationStatus.CANCELLED);
    assertThat(cancelled.remainingQuantity()).isEqualByComparingTo("3");
    assertThat(cancelled.cancelledBy()).isEqualTo(UUID.fromString(user.id()));
    // Reserved 4 -> 3 after consume -> 0 after cancel; consumed 0 -> 1; available 6 -> 9.
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("0");
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("9");
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_RESERVATION);
  }

  // --- Transitions --------------------------------------------------------------

  @Test
  @DisplayName("18.5-SVC-012 P0 transition on a terminal reservation → 409 INVALID_RESERVATION_TRANSITION")
  void terminalTransitionsRejected() {
    var fixture = fixture("RSV-P12");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-012", null);
    var user = mutationUser(fixture);

    service.consume(user, created.id(), new BigDecimal("4")); // CONSUMED

    assertThatThrownBy(() -> service.consume(user, created.id(), new BigDecimal("1")))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertThatThrownBy(() -> service.cancel(user, created.id()))
        .isInstanceOf(InvalidReservationTransitionException.class);

    var cancelled = createActive(fixture, "3", "WO-012B", null);
    service.cancel(user, cancelled.id()); // CANCELLED
    assertThatThrownBy(() -> service.consume(user, cancelled.id(), new BigDecimal("1")))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertThatThrownBy(() -> service.cancel(user, cancelled.id()))
        .isInstanceOf(InvalidReservationTransitionException.class);
  }

  @Test
  @DisplayName("18.5-SVC-013 P0 consume/cancel gate role and plant scope → 403")
  void transitionGatesRoleAndScope() {
    var fixture = fixture("RSV-P13");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-013", null);
    var tech = persistedUser(ApplicationRole.TECHNICIAN, "rsv-tech13@syncro.dev");
    assign(tech, fixture.plant);
    assertThatThrownBy(() -> service.consume(tech, created.id(), new BigDecimal("1")))
        .isInstanceOf(ReservationForbiddenException.class);
    assertThatThrownBy(() -> service.cancel(tech, created.id()))
        .isInstanceOf(ReservationForbiddenException.class);
    var outsider = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "rsv-out13@syncro.dev");
    assign(outsider, plant("RSV-P13B"));
    assertThatThrownBy(() -> service.consume(outsider, created.id(), new BigDecimal("1")))
        .isInstanceOf(ReservationForbiddenException.class);
    assertThat(reservations.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryReservationStatus.ACTIVE);
  }

  @Test
  @DisplayName("18.5-SVC-014 P1 unknown reservation → 404")
  void unknownReservation() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.consume(admin, UUID.randomUUID(), new BigDecimal("1")))
        .isInstanceOf(InventoryReservationNotFoundException.class);
    assertThatThrownBy(() -> service.cancel(admin, UUID.randomUUID()))
        .isInstanceOf(InventoryReservationNotFoundException.class);
    assertThatThrownBy(() -> service.get(admin, UUID.randomUUID()))
        .isInstanceOf(InventoryReservationNotFoundException.class);
  }

  @Test
  @DisplayName("18.5-SVC-015 P0 SUPER_ADMIN mutates out-of-plant reservations (scope bypass)")
  void superAdminBypassesScope() {
    var fixture = fixture("RSV-P15");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-015", null);
    var admin = adminUser(); // persisted (consumed_by FK)

    var consumed = service.consume(admin, created.id(), new BigDecimal("2"));
    assertThat(consumed.status()).isEqualTo(InventoryReservationStatus.ACTIVE);
    assertThat(consumed.remainingQuantity()).isEqualByComparingTo("2");
  }

  // --- Expiry ------------------------------------------------------------------

  @Test
  @DisplayName("18.5-SVC-016 P0 expiry on access: read reports EXPIRED and returns stock")
  void expiryOnReadReturnsStock() {
    var fixture = fixture("RSV-P16");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-016",
        Instant.parse("2026-08-01T00:00:00Z")); // already past
    var user = mutationUser(fixture);

    var read = service.get(user, created.id());

    assertThat(read.status()).isEqualTo(InventoryReservationStatus.EXPIRED);
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("0");
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("10");
    assertThat(balances.findById(row.getId()).orElseThrow().getConsumed())
        .isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("18.5-SVC-017 P0 consume past expiry → 409 and stock already returned")
  void consumePastExpiryRejected() {
    var fixture = fixture("RSV-P17");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-017",
        Instant.parse("2026-08-01T00:00:00Z")); // already past
    var user = mutationUser(fixture);

    assertThatThrownBy(() -> service.consume(user, created.id(), new BigDecimal("1")))
        .isInstanceOf(InvalidReservationTransitionException.class);
    assertThat(reservations.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryReservationStatus.EXPIRED);
    // Stock returned on the consume attempt (expiry is evaluated on access).
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("0");
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("10");
  }

  @Test
  @DisplayName("18.5-SVC-018 P0 non-expired reservation is unaffected by the expiry check")
  void nonExpiredUnaffected() {
    var fixture = fixture("RSV-P18");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-018",
        Instant.parse("2030-01-01T00:00:00Z")); // future
    var user = mutationUser(fixture);

    var read = service.get(user, created.id());
    assertThat(read.status()).isEqualTo(InventoryReservationStatus.ACTIVE);
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("4");
  }

  // --- Reads -------------------------------------------------------------------

  @Test
  @DisplayName("18.5-SVC-019 P0 list filters by every optional filter, newest first")
  void listFilters() {
    var fixture = fixture("RSV-P19");
    balance(fixture.sparepartId, fixture.locationId, "20");
    var otherPart = sparepart(fixture.plant, "MC-P19B");
    balance(otherPart.getId(), fixture.locationId, "20");
    var admin = adminUser(); // persisted (requested_by/cancelled_by FKs)
    var first = createActive(fixture, "4", "WO-019", null);
    var second = service.create(admin, new CreateReservationCommand(otherPart.getId(),
        fixture.locationId, new BigDecimal("2"), "WORK_ORDER", "WO-019B", null));
    service.cancel(admin, first.id());

    assertThat(service.list(admin, null, null, null, null, null))
        .extracting(view -> view.id())
        .containsExactlyInAnyOrder(second.id(), first.id());
    assertThat(service.list(admin, null, null, InventoryReservationStatus.CANCELLED, null, null))
        .extracting(view -> view.id()).containsExactly(first.id());
    assertThat(service.list(admin, otherPart.getId(), null, null, null, null))
        .extracting(view -> view.id()).containsExactly(second.id());
    assertThat(service.list(admin, null, fixture.locationId, null, null, null)).hasSize(2);
    assertThat(service.list(admin, null, null, null, "WORK_ORDER", "WO-019"))
        .extracting(view -> view.id()).containsExactly(first.id());
  }

  @Test
  @DisplayName("18.5-SVC-020 P0 list is plant-scoped; outsiders see nothing")
  void listIsPlantScoped() {
    var fixture = fixture("RSV-P20");
    balance(fixture.sparepartId, fixture.locationId, "10");
    createActive(fixture, "4", "WO-020", null);
    var insider = mutationUser(fixture);
    var outsider = persistedUser(ApplicationRole.STOREKEEPER, "rsv-out-list@syncro.dev");
    assign(outsider, plant("RSV-P20B"));

    assertThat(service.list(insider, null, null, null, null, null)).hasSize(1);
    assertThat(service.list(outsider, null, null, null, null, null)).isEmpty();
  }

  @Test
  @DisplayName("18.5-SVC-021 P1 get out-of-plant reservation → 403")
  void getOutOfScope() {
    var fixture = fixture("RSV-P21");
    balance(fixture.sparepartId, fixture.locationId, "10");
    var created = createActive(fixture, "4", "WO-021", null);
    var outsider = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "rsv-out-get@syncro.dev");
    assign(outsider, plant("RSV-P21B"));

    assertThatThrownBy(() -> service.get(outsider, created.id()))
        .isInstanceOf(ReservationForbiddenException.class);
  }

  // --- Transfer interlock (FR-146c) ---------------------------------------------

  @Test
  @DisplayName("18.5-SVC-022 P0 transfer approve never touches reserved; available gates the move")
  void transferInterlock() {
    var fixture = fixture("RSV-P22");
    var row = balance(fixture.sparepartId, fixture.locationId, "10");
    var destLoc = location(fixture.plant, "WS-DEST22");
    balance(fixture.sparepartId, destLoc.getId(), "0");
    // Reserve 4 of the 10 available: available 6, reserved 4. Then transfer 5 of
    // the remaining available (6) — approved. Reserved must stay 4.
    createActive(fixture, "4", "WO-022", null);
    var requester = adminUser(); // persisted (transfer requested_by FK)
    var reviewer = adminUser();
    var created = transferService.create(requester, new com.syncro.inventory.application
        .InventoryTransferService.CreateTransferCommand(fixture.sparepartId,
        fixture.locationId, destLoc.getId(), new BigDecimal("5")));

    var approved = transferService.approve(reviewer, created.id());

    assertThat(approved.status())
        .isEqualTo(com.syncro.inventory.domain.InventoryTransferStatus.APPROVED);
    // Source: available 6 - 5 = 1; reserved untouched at 4.
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("1");
    assertThat(balances.findById(row.getId()).orElseThrow().getReserved())
        .isEqualByComparingTo("4");
    assertThat(balances.findBySparepartIdAndLocationId(fixture.sparepartId, destLoc.getId())
        .orElseThrow().getAvailable()).isEqualByComparingTo("5");
  }

  // --- helpers -----------------------------------------------------------------

  /** plant + one active location + one sparepart of that plant. */
  private record Fixture(PlantEntity plant, UUID locationId, UUID sparepartId) {
  }

  private Fixture fixture(String plantCode) {
    var plant = plant(plantCode);
    var location = location(plant, "WS-RSV");
    var sparepart = sparepart(plant, "MC-" + plantCode.replace("RSV-", ""));
    return new Fixture(plant, location.getId(), sparepart.getId());
  }

  private InventoryReservationService.ReservationView createActive(Fixture fixture,
      String quantity, String referenceId, Instant expiresAt) {
    // requested_by has an FK to auth_users — the actor must be a persisted row.
    return service.create(adminUser(), new CreateReservationCommand(fixture.sparepartId,
        fixture.locationId, new BigDecimal(quantity), "WORK_ORDER", referenceId, expiresAt));
  }

  /** A MANAGER_MAINTENANCE mutation user assigned to the fixture plant. */
  private AuthenticatedUser mutationUser(Fixture fixture) {
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE,
        "rsv-" + SEQ.incrementAndGet() + "@syncro.dev");
    assign(user, fixture.plant);
    return user;
  }

  /** A persisted SUPER_ADMIN (scope bypass, but requested_by/consumed_by FKs need the row). */
  private AuthenticatedUser adminUser() {
    return persistedUser(ApplicationRole.SUPER_ADMIN,
        "rsv-admin-" + SEQ.incrementAndGet() + "@syncro.dev");
  }

  private PlantEntity plant(String code) {
    return plants.findByCodeIgnoreCase(code)
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code,
            "Plant " + code, NOW, NOW)));
  }

  private InventoryLocationEntity location(PlantEntity plant, String code) {
    return locations.findByPlantIdAndCodeIgnoreCase(plant.getId(), code)
        .orElseGet(() -> locations.saveAndFlush(new InventoryLocationEntity(UUID.randomUUID(),
            plant.getId(), code, "Location " + code, null, true, NOW, NOW)));
  }

  private void deactivate(UUID locationId, PlantEntity plant) {
    var location = locations.findById(locationId).orElseThrow();
    locations.saveAndFlush(new InventoryLocationEntity(location.getId(), plant.getId(),
        location.getCode(), location.getName(), location.getDescription(), false,
        location.getCreatedAt(), NOW));
  }

  private SparepartEntity sparepart(PlantEntity plant, String materialCode) {
    var existing = spareparts.findByMaterialCodeIgnoreCase(materialCode);
    if (existing.isPresent()) {
      return existing.get();
    }
    var n = SEQ.incrementAndGet();
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant,
        "Group " + n, NOW, NOW));
    var machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group,
        "M-" + n, "Machine " + n, MachineStatus.ACTIVE, "Juki",
        java.time.LocalDate.parse("2026-05-27"), null, List.of(), NOW, NOW));
    var category = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.CATEGORY, "CAT" + n, "Category " + n, NOW, NOW));
    var brand = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.BRAND, "BRD" + n, "Brand " + n, category, NOW, NOW));
    var kind = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.KIND, "KND" + n, "Kind " + n, category, NOW, NOW));
    var type = taxonomy.saveAndFlush(new SparepartTaxonomyEntity(UUID.randomUUID(),
        SparepartTaxonomyDimension.TYPE, "TYP" + n, "Type " + n, category, NOW, NOW));
    var entity = new SparepartEntity(UUID.randomUUID(), "SP-" + n, "Part " + n, machine,
        category, brand, kind, type, NOW, NOW);
    entity.updateProcurement(materialCode, null, NOW);
    return spareparts.saveAndFlush(entity);
  }

  private InventoryStockBalanceEntity balance(UUID sparepartId, UUID locationId, String available) {
    return balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(), sparepartId,
        locationId, new BigDecimal(available), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        NOW, NOW));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, NOW, NOW));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()),
        plant.getId(), NOW));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private AuditLogEntity latestAuditEntryFor(UUID entityId) {
    var entries = auditLogs.findByEntityIdOrderByCreatedAtAsc(entityId);
    assertThat(entries).isNotEmpty();
    return entries.get(entries.size() - 1);
  }
}
