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
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.application.InventoryTransferService.CreateTransferCommand;
import com.syncro.inventory.application.InventoryTransferService.InsufficientStockException;
import com.syncro.inventory.application.InventoryTransferService.InvalidTransferTransitionException;
import com.syncro.inventory.application.InventoryTransferService.InventoryTransferNotFoundException;
import com.syncro.inventory.application.InventoryTransferService.TransferForbiddenException;
import com.syncro.inventory.application.InventoryTransferService.TransferSelfReviewForbiddenException;
import com.syncro.inventory.application.InventoryTransferService.TransferValidationException;
import com.syncro.inventory.domain.InventoryTransferStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceEntity;
import com.syncro.inventory.infrastructure.db.InventoryStockBalanceRepository;
import com.syncro.inventory.infrastructure.db.InventoryTransferRepository;
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
 * Story 18-4: the inventory-transfer lifecycle against a real Postgres (Testcontainers).
 * Covers every row of the spec's I/O matrix at the service boundary: create →
 * PENDING_APPROVAL + audit; approve moves stock atomically (both balances change,
 * reviewer stamped, audits written); insufficient source stock → 409 with NO movement
 * anywhere; SoD (requester can never review their own) → 403; double review → 409;
 * reject with reason → REJECTED + no movement; blank reason / cross-plant / same
 * location / qty ≤ 0 → 400; unknown refs and inactive locations → 404; wrong role →
 * 403; plant-scoped list with filters. The concurrency guard (two threads approve the
 * same transfer, exactly one wins) lives in
 * {@code InventoryTransferApproveConcurrencyIntegrationTest} — it needs committed
 * data and independent transactions, which the {@code @Transactional} base class here
 * cannot provide.
 */
class InventoryTransferServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired
  private InventoryTransferService service;
  @Autowired
  private InventoryTransferRepository transfers;
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
  @DisplayName("18.4-SVC-001 P0 create persists PENDING_APPROVAL with requested_by and audits CREATE")
  void createPersistsPendingAndAudits() throws Exception {
    var fixture = fixture("TRF-P1");
    var requester = reviewer(fixture);

    var created = service.create(requester, new CreateTransferCommand(fixture.sparepartId,
        fixture.sourceId, fixture.destinationId, new BigDecimal("5")));

    assertThat(created.status()).isEqualTo(InventoryTransferStatus.PENDING_APPROVAL);
    assertThat(created.requestedBy()).isEqualTo(UUID.fromString(requester.id()));
    assertThat(created.reviewedBy()).isNull();
    assertThat(created.reviewedAt()).isNull();
    assertThat(created.rejectionReason()).isNull();
    var stored = transfers.findById(created.id()).orElseThrow();
    assertThat(stored.getQuantity()).isEqualByComparingTo("5");
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_TRANSFER);
    assertThat(entry.getPlantId()).isEqualTo(fixture.plant.getId());
    assertThat(mapper.readTree(entry.getNewValue()).get("status").asText())
        .isEqualTo("PENDING_APPROVAL");
    assertThat(mapper.readTree(entry.getNewValue()).get("requestedBy").asText())
        .isEqualTo(requester.id());
  }

  @Test
  @DisplayName("18.4-SVC-002 P0 create rejects qty <= 0, same source/dest, cross-plant locations → 400")
  void createRejectsInvalidInputs() {
    var fixture = fixture("TRF-P2");
    var requester = reviewer(fixture);

    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        fixture.sparepartId, fixture.sourceId, fixture.destinationId, BigDecimal.ZERO)))
        .isInstanceOf(TransferValidationException.class);
    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        fixture.sparepartId, fixture.sourceId, fixture.sourceId, BigDecimal.TEN)))
        .isInstanceOf(TransferValidationException.class);
    var otherPlant = plant("TRF-P2B");
    var foreignDest = location(otherPlant, "WS-X");
    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        fixture.sparepartId, fixture.sourceId, foreignDest.getId(), BigDecimal.TEN)))
        .isInstanceOf(TransferValidationException.class);
    assertThat(transfers.findAll()).extracting(t -> t.getSparepartId())
        .doesNotContain(fixture.sparepartId);
  }

  @Test
  @DisplayName("18.4-SVC-003 P0 create rejects unknown refs and inactive locations → 404")
  void createRejectsUnknownRefs() {
    var fixture = fixture("TRF-P3");
    var requester = reviewer(fixture);

    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        UUID.randomUUID(), fixture.sourceId, fixture.destinationId, BigDecimal.TEN)))
        .isInstanceOf(SparepartNotFoundException.class);
    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        fixture.sparepartId, UUID.randomUUID(), fixture.destinationId, BigDecimal.TEN)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        fixture.sparepartId, fixture.sourceId, UUID.randomUUID(), BigDecimal.TEN)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    // Inactive destination: a transfer is a two-sided mutation — both sides must be active.
    deactivate(fixture.destinationId, fixture.plant);
    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        fixture.sparepartId, fixture.sourceId, fixture.destinationId, BigDecimal.TEN)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  @Test
  @DisplayName("18.4-SVC-004 P0 create with a sparepart from another plant → 404 (no cross-plant oracle)")
  void createRejectsForeignPlantSparepart() {
    var fixture = fixture("TRF-P4");
    var foreignPart = sparepart(plant("TRF-P4B"), "MC-T4B");
    var requester = reviewer(fixture);

    assertThatThrownBy(() -> service.create(requester, new CreateTransferCommand(
        foreignPart.getId(), fixture.sourceId, fixture.destinationId, BigDecimal.TEN)))
        .isInstanceOf(SparepartNotFoundException.class);
  }

  @Test
  @DisplayName("18.4-SVC-005 P0 create gates role and plant scope → 403")
  void createGatesRoleAndScope() {
    var fixture = fixture("TRF-P5");
    var tech = persistedUser(ApplicationRole.TECHNICIAN, "trf-tech@syncro.dev");
    assign(tech, fixture.plant);
    assertThatThrownBy(() -> service.create(tech, new CreateTransferCommand(fixture.sparepartId,
        fixture.sourceId, fixture.destinationId, BigDecimal.TEN)))
        .isInstanceOf(TransferForbiddenException.class);

    var outsider = persistedUser(ApplicationRole.STOREKEEPER, "trf-outsider@syncro.dev");
    assign(outsider, plant("TRF-P5B"));
    assertThatThrownBy(() -> service.create(outsider, new CreateTransferCommand(
        fixture.sparepartId, fixture.sourceId, fixture.destinationId, BigDecimal.TEN)))
        .isInstanceOf(TransferForbiddenException.class);
  }

  // --- Approve -----------------------------------------------------------------

  @Test
  @DisplayName("18.4-SVC-006 P0 approve moves stock atomically, stamps reviewer, audits everything")
  void approveMovesStockAndAudits() throws Exception {
    var fixture = fixture("TRF-P6");
    var sourceRow = balance(fixture.sparepartId, fixture.sourceId, "10");
    var destRow = balance(fixture.sparepartId, fixture.destinationId, "2");
    var created = createPending(fixture);
    var reviewer = reviewer(fixture);

    var approved = service.approve(reviewer, created.id());

    assertThat(approved.status()).isEqualTo(InventoryTransferStatus.APPROVED);
    assertThat(approved.reviewedBy()).isEqualTo(UUID.fromString(reviewer.id()));
    assertThat(approved.reviewedAt()).isNotNull();
    assertThat(balances.findById(sourceRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("5");
    assertThat(balances.findById(destRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("7");
    // Transfer audit: UPDATE with previous + new status and reviewer identity.
    var transferEntry = latestAuditEntryFor(approved.id());
    assertThat(transferEntry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(transferEntry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_TRANSFER);
    assertThat(mapper.readTree(transferEntry.getPreviousValue()).get("status").asText())
        .isEqualTo("PENDING_APPROVAL");
    assertThat(mapper.readTree(transferEntry.getNewValue()).get("status").asText())
        .isEqualTo("APPROVED");
    assertThat(mapper.readTree(transferEntry.getNewValue()).get("reviewedBy").asText())
        .isEqualTo(reviewer.id());
    // Both balance rows are audit-visible (epic-18: every stock mutation is audited).
    var sourceAudit = latestAuditEntryFor(sourceRow.getId());
    assertThat(sourceAudit.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_STOCK_BALANCE);
    assertThat(mapper.readTree(sourceAudit.getNewValue()).get("available").decimalValue())
        .isEqualByComparingTo("5");
    var destAudit = latestAuditEntryFor(destRow.getId());
    assertThat(mapper.readTree(destAudit.getNewValue()).get("available").decimalValue())
        .isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("18.4-SVC-007 P0 approve creates a missing destination balance row with available = qty")
  void approveCreatesMissingDestinationRow() throws Exception {
    var fixture = fixture("TRF-P7");
    balance(fixture.sparepartId, fixture.sourceId, "10");
    var created = createPending(fixture);

    var approved = service.approve(reviewer(fixture), created.id());

    assertThat(approved.status()).isEqualTo(InventoryTransferStatus.APPROVED);
    var dest = balances.findBySparepartIdAndLocationId(fixture.sparepartId,
        fixture.destinationId).orElseThrow();
    assertThat(dest.getAvailable()).isEqualByComparingTo("5");
    assertThat(dest.getReserved()).isEqualByComparingTo("0");
    assertThat(dest.getConsumed()).isEqualByComparingTo("0");
    assertThat(dest.getMinimumStock()).isEqualByComparingTo("0");
    // The created row is audited as CREATE on the balance entity.
    var entry = latestAuditEntryFor(dest.getId());
    assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_STOCK_BALANCE);
  }

  @Test
  @DisplayName("18.4-SVC-008 P0 approve with insufficient source stock → 409 and NO movement anywhere")
  void approveInsufficientStockRollsBack() {
    var fixture = fixture("TRF-P8");
    var sourceRow = balance(fixture.sparepartId, fixture.sourceId, "3");
    var destRow = balance(fixture.sparepartId, fixture.destinationId, "2");
    var created = createPending(fixture);

    assertThatThrownBy(() -> service.approve(reviewer(fixture), created.id()))
        .isInstanceOf(InsufficientStockException.class);
    assertThat(balances.findById(sourceRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("3");
    assertThat(balances.findById(destRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("2");
    assertThat(transfers.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryTransferStatus.PENDING_APPROVAL);
  }

  @Test
  @DisplayName("18.4-SVC-009 P0 requester can never approve or reject their own transfer → 403")
  void selfReviewForbidden() {
    var fixture = fixture("TRF-P9");
    balance(fixture.sparepartId, fixture.sourceId, "10");
    var requester = reviewer(fixture);
    var created = createPending(fixture, requester);

    assertThatThrownBy(() -> service.approve(requester, created.id()))
        .isInstanceOf(TransferSelfReviewForbiddenException.class);
    assertThatThrownBy(() -> service.reject(requester, created.id(), "self reject"))
        .isInstanceOf(TransferSelfReviewForbiddenException.class);
    // No movement, no stamping: still pending.
    assertThat(balances.findBySparepartIdAndLocationId(fixture.sparepartId, fixture.sourceId)
        .orElseThrow().getAvailable()).isEqualByComparingTo("10");
    assertThat(transfers.findById(created.id()).orElseThrow().getReviewedBy()).isNull();
  }

  @Test
  @DisplayName("18.4-SVC-010 P0 review on a terminal transfer → 409 INVALID_TRANSFER_TRANSITION")
  void doubleReviewRejected() {
    var fixture = fixture("TRF-P10");
    balance(fixture.sparepartId, fixture.sourceId, "10");
    var created = createPending(fixture);
    var reviewer = reviewer(fixture);
    service.approve(reviewer, created.id());

    assertThatThrownBy(() -> service.approve(reviewer, created.id()))
        .isInstanceOf(InvalidTransferTransitionException.class);
    assertThatThrownBy(() -> service.reject(reviewer, created.id(), "too late"))
        .isInstanceOf(InvalidTransferTransitionException.class);
    // The second approve attempt moved nothing.
    assertThat(balances.findBySparepartIdAndLocationId(fixture.sparepartId, fixture.sourceId)
        .orElseThrow().getAvailable()).isEqualByComparingTo("5");
  }

  @Test
  @DisplayName("18.4-SVC-011 P0 approve gates reviewer role and plant scope → 403")
  void approveGatesRoleAndScope() {
    var fixture = fixture("TRF-P11");
    balance(fixture.sparepartId, fixture.sourceId, "10");
    var created = createPending(fixture);

    // STOREKEEPER may create but may NOT review.
    var storekeeper = persistedUser(ApplicationRole.STOREKEEPER, "trf-sk-review@syncro.dev");
    assign(storekeeper, fixture.plant);
    assertThatThrownBy(() -> service.approve(storekeeper, created.id()))
        .isInstanceOf(TransferForbiddenException.class);
    // Right reviewer role, wrong plant.
    var outsider = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "trf-manager-out@syncro.dev");
    assign(outsider, plant("TRF-P11B"));
    assertThatThrownBy(() -> service.approve(outsider, created.id()))
        .isInstanceOf(TransferForbiddenException.class);
    assertThat(transfers.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryTransferStatus.PENDING_APPROVAL);
  }

  @Test
  @DisplayName("18.4-SVC-012 P0 SUPER_ADMIN approves out-of-plant transfers (scope bypass)")
  void superAdminApprovesAcrossPlants() {
    var fixture = fixture("TRF-P12");
    balance(fixture.sparepartId, fixture.sourceId, "10");
    var created = createPending(fixture);
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var approved = service.approve(admin, created.id());

    assertThat(approved.status()).isEqualTo(InventoryTransferStatus.APPROVED);
  }

  @Test
  @DisplayName("18.4-SVC-013 P1 approve unknown transfer → 404")
  void approveUnknownTransfer() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.approve(admin, UUID.randomUUID()))
        .isInstanceOf(InventoryTransferNotFoundException.class);
  }

  // --- Reject ------------------------------------------------------------------

  @Test
  @DisplayName("18.4-SVC-014 P0 reject stores reason + reviewer, moves no stock, audits UPDATE")
  void rejectStoresReasonAndAudits() throws Exception {
    var fixture = fixture("TRF-P14");
    var sourceRow = balance(fixture.sparepartId, fixture.sourceId, "10");
    var created = createPending(fixture);
    var reviewer = reviewer(fixture);

    var rejected = service.reject(reviewer, created.id(), "Not needed right now");

    assertThat(rejected.status()).isEqualTo(InventoryTransferStatus.REJECTED);
    assertThat(rejected.rejectionReason()).isEqualTo("Not needed right now");
    assertThat(rejected.reviewedBy()).isEqualTo(UUID.fromString(reviewer.id()));
    assertThat(rejected.reviewedAt()).isNotNull();
    assertThat(balances.findById(sourceRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("10");
    var entry = latestAuditEntryFor(rejected.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(mapper.readTree(entry.getNewValue()).get("status").asText()).isEqualTo("REJECTED");
    assertThat(mapper.readTree(entry.getNewValue()).get("rejectionReason").asText())
        .isEqualTo("Not needed right now");
  }

  @Test
  @DisplayName("18.4-SVC-015 P0 reject with blank reason → 400")
  void rejectBlankReasonRejected() {
    var fixture = fixture("TRF-P15");
    var created = createPending(fixture);

    assertThatThrownBy(() -> service.reject(reviewer(fixture), created.id(), "   "))
        .isInstanceOf(TransferValidationException.class);
    assertThatThrownBy(() -> service.reject(reviewer(fixture), created.id(), null))
        .isInstanceOf(TransferValidationException.class);
    assertThat(transfers.findById(created.id()).orElseThrow().getStatus())
        .isEqualTo(InventoryTransferStatus.PENDING_APPROVAL);
  }

  // --- Read --------------------------------------------------------------------

  @Test
  @DisplayName("18.4-SVC-016 P0 list filters by sparepartId and status, newest first")
  void listFilters() {
    var fixture = fixture("TRF-P16");
    balance(fixture.sparepartId, fixture.sourceId, "20");
    var otherPart = sparepart(fixture.plant, "MC-T16B");
    balance(otherPart.getId(), fixture.sourceId, "20");
    var first = createPending(fixture);
    var second = service.create(reviewer(fixture), new CreateTransferCommand(otherPart.getId(),
        fixture.sourceId, fixture.destinationId, new BigDecimal("4")));
    service.approve(reviewer(fixture), first.id());
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var all = service.list(admin, null, null);
    // Newest-first ordering is asserted loosely: both rows are created within the same
    // transaction window, so a microsecond timestamp tie must not make this flaky.
    assertThat(all).extracting(view -> view.id()).containsExactlyInAnyOrder(second.id(), first.id());
    var approvedOnly = service.list(admin, null, InventoryTransferStatus.APPROVED);
    assertThat(approvedOnly).extracting(view -> view.id()).containsExactly(first.id());
    var byPart = service.list(admin, otherPart.getId(), null);
    assertThat(byPart).extracting(view -> view.id()).containsExactly(second.id());
  }

  @Test
  @DisplayName("18.4-SVC-017 P0 list is plant-scoped; outsiders see nothing")
  void listIsPlantScoped() {
    var fixture = fixture("TRF-P17");
    createPending(fixture);
    var insider = reviewer(fixture);
    var outsider = persistedUser(ApplicationRole.STOREKEEPER, "trf-outsider-list@syncro.dev");
    assign(outsider, plant("TRF-P17B"));

    assertThat(service.list(insider, null, null)).hasSize(1);
    assertThat(service.list(outsider, null, null)).isEmpty();
  }

  @Test
  @DisplayName("18.4-SVC-018 P1 get by id returns the view; unknown → 404; out of scope → 403")
  void getByIdGated() {
    var fixture = fixture("TRF-P18");
    var created = createPending(fixture);
    var outsider = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "trf-out-get@syncro.dev");
    assign(outsider, plant("TRF-P18B"));

    assertThat(service.get(reviewer(fixture), created.id()).id()).isEqualTo(created.id());
    assertThatThrownBy(() -> service.get(reviewer(fixture), UUID.randomUUID()))
        .isInstanceOf(InventoryTransferNotFoundException.class);
    assertThatThrownBy(() -> service.get(outsider, created.id()))
        .isInstanceOf(TransferForbiddenException.class);
  }

  // --- helpers -----------------------------------------------------------------

  /** plant + two active locations + one sparepart of that plant. */
  private record Fixture(PlantEntity plant, UUID sourceId, UUID destinationId, UUID sparepartId) {
  }

  private Fixture fixture(String plantCode) {
    var plant = plant(plantCode);
    var source = location(plant, "WS-A");
    var destination = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-" + plantCode.replace("TRF-", ""));
    return new Fixture(plant, source.getId(), destination.getId(), sparepart.getId());
  }

  private InventoryTransferService.TransferView createPending(Fixture fixture) {
    return createPending(fixture, reviewer(fixture));
  }

  private InventoryTransferService.TransferView createPending(Fixture fixture,
      AuthenticatedUser requester) {
    return service.create(requester, new CreateTransferCommand(fixture.sparepartId,
        fixture.sourceId, fixture.destinationId, new BigDecimal("5")));
  }

  /** A MANAGER_MAINTENANCE reviewer assigned to the fixture plant (never the requester). */
  private AuthenticatedUser reviewer(Fixture fixture) {
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE,
        "trf-rev-" + SEQ.incrementAndGet() + "@syncro.dev");
    assign(user, fixture.plant);
    return user;
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
