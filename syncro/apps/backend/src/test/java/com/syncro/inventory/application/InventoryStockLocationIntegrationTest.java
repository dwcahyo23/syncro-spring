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
import com.syncro.inventory.application.InventoryStockService.AdjustBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.InventoryStockForbiddenException;
import com.syncro.inventory.application.InventoryStockService.InventoryStockValidationException;
import com.syncro.inventory.application.InventoryStockService.LocationUpsertCommand;
import com.syncro.inventory.application.InventoryStockService.NegativeStockRejectedException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.application.InventoryStockService.UpdateBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.UpsertBalanceCommand;
import com.syncro.inventory.application.InventoryStockService.VersionConflictException;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Story 18-3: location-aware stock balances against a real Postgres (Testcontainers).
 * Covers every row of the spec's I/O matrix at the service boundary: list/create/
 * upsert/adjust at a named location, the atomic underflow guard (409
 * NEGATIVE_STOCK_REJECTED), the legacy no-locationId path (default location), the
 * legacy path with an explicit locationId, per-location reorder warnings, the
 * foreign-plant locationId 404 (never a cross-plant leak), the wrong-role 403, and
 * the optimistic-lock 409. The plant for location-scoped calls is derived from the
 * location, never from the body — the spoofing-prevention invariant.
 */
class InventoryStockLocationIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  private static final String DEFAULT_LOCATION_CODE = "GUDANG-UTAMA";
  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired
  private InventoryStockService service;
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

  // --- List at a location ------------------------------------------------------

  @Test
  @DisplayName("18.3-LOC-001 P0 listAtLocation returns only that location's rows")
  void listAtLocationIsLocationScoped() {
    var plant = plant("STK-P1");
    var defaultLoc = location(plant, DEFAULT_LOCATION_CODE);
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0001");
    balance(sparepart.getId(), defaultLoc.getId(), "10", "5");
    balance(sparepart.getId(), locB.getId(), "3", "5");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-list@syncro.dev");
    assign(user, plant);

    var rows = service.listAtLocation(user, locB.getId());

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().locationId()).isEqualTo(locB.getId());
    assertThat(rows.getFirst().available()).isEqualByComparingTo("3");
  }

  @Test
  @DisplayName("18.3-LOC-002 P0 unknown location on the location-scoped list → 404")
  void listAtUnknownLocationRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.listAtLocation(admin, UUID.randomUUID()))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  // --- Create / upsert at a location -------------------------------------------

  @Test
  @DisplayName("18.3-LOC-003 P0 upsertAtLocation creates a row (version 0) and audits CREATE")
  void upsertAtLocationCreatesAndAudits() throws Exception {
    var plant = plant("STK-P2");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0002");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-create@syncro.dev");
    assign(user, plant);

    var created = service.upsertAtLocation(user, locB.getId(), new LocationUpsertCommand(
        "MC-0002", new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5")));

    assertThat(created.locationId()).isEqualTo(locB.getId());
    assertThat(created.sparepartId()).isEqualTo(sparepart.getId());
    assertThat(created.available()).isEqualByComparingTo("10");
    assertThat(created.version()).isZero();
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_STOCK_BALANCE);
    // Plant derived from the location, not the body.
    assertThat(entry.getPlantId()).isEqualTo(plant.getId());
    assertThat(mapper.readTree(entry.getNewValue()).get("locationId").asText())
        .isEqualTo(locB.getId().toString());
  }

  @Test
  @DisplayName("18.3-LOC-004 P0 upsertAtLocation overwrites an existing row and audits UPDATE")
  void upsertAtLocationOverwritesExisting() throws Exception {
    var plant = plant("STK-P3");
    var locB = location(plant, "WS-B");
    sparepart(plant, "MC-0003");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-upsert@syncro.dev");
    assign(user, plant);
    service.upsertAtLocation(user, locB.getId(), new LocationUpsertCommand("MC-0003",
        new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5")));

    var updated = service.upsertAtLocation(user, locB.getId(), new LocationUpsertCommand("MC-0003",
        new BigDecimal("7"), BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("4")));

    assertThat(updated.available()).isEqualByComparingTo("7");
    assertThat(updated.minimumStock()).isEqualByComparingTo("4");
    assertThat(updated.version()).isEqualTo(1L);
    assertThat(balances.findBySparepartIdAndLocationId(
        spareparts.findByMaterialCodeIgnoreCase("MC-0003").orElseThrow().getId(), locB.getId()))
        .isPresent();
    // The overwrite is audited as UPDATE with previous + new values (spec: audit
    // CREATE/UPDATE on every mutation path).
    var entry = latestAuditEntryFor(updated.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_STOCK_BALANCE);
    assertThat(mapper.readTree(entry.getPreviousValue()).get("available").decimalValue())
        .isEqualByComparingTo("10");
    assertThat(mapper.readTree(entry.getNewValue()).get("available").decimalValue())
        .isEqualByComparingTo("7");
  }

  @Test
  @DisplayName("18.3-LOC-005 P1 upsertAtLocation unknown material → 404; negative value → 400")
  void upsertAtLocationRejectsUnknownAndNegative() {
    var plant = plant("STK-P4");
    var locB = location(plant, "WS-B");
    sparepart(plant, "MC-0004");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-bad@syncro.dev");
    assign(user, plant);

    assertThatThrownBy(() -> service.upsertAtLocation(user, locB.getId(), new LocationUpsertCommand(
        "UNKNOWN-XYZ", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
        .isInstanceOf(SparepartNotFoundException.class);
    assertThatThrownBy(() -> service.upsertAtLocation(user, locB.getId(), new LocationUpsertCommand(
        "MC-0004", new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
        .isInstanceOf(InventoryStockValidationException.class);
    assertThat(balances.findAllByLocationIdOrderBySparepartIdAsc(locB.getId())).isEmpty();
  }

  // --- Adjust at a location ----------------------------------------------------

  @Test
  @DisplayName("18.3-LOC-006 P0 adjust at location: underflow → 409 and no change")
  void adjustAtLocationUnderflowRejected() {
    var plant = plant("STK-P5");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0005");
    var row = balance(sparepart.getId(), locB.getId(), "3", "5");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-under@syncro.dev");
    assign(user, plant);

    assertThatThrownBy(() -> service.adjustAtLocation(user, locB.getId(), "MC-0005",
        new BigDecimal("-5")))
        .isInstanceOf(NegativeStockRejectedException.class);
    assertThat(balances.findById(row.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("3");
  }

  @Test
  @DisplayName("18.3-LOC-007 P0 adjust at location: exact drawdown succeeds with version bump + audit")
  void adjustAtLocationSucceeds() throws Exception {
    var plant = plant("STK-P6");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0006");
    var row = balance(sparepart.getId(), locB.getId(), "3", "5");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-adjust@syncro.dev");
    assign(user, plant);

    var adjusted = service.adjustAtLocation(user, locB.getId(), "MC-0006", new BigDecimal("-3"));

    assertThat(adjusted.available()).isEqualByComparingTo("0");
    assertThat(adjusted.version()).isEqualTo(1L);
    var entry = latestAuditEntryFor(row.getId());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(mapper.readTree(entry.getPreviousValue()).get("available").decimalValue())
        .isEqualByComparingTo("3");
    assertThat(mapper.readTree(entry.getNewValue()).get("available").decimalValue())
        .isEqualByComparingTo("0");
  }

  // --- Legacy contract (no locationId) -----------------------------------------

  @Test
  @DisplayName("18.3-LOC-008 P0 legacy upsert with no locationId targets the plant default")
  void legacyUpsertUsesDefaultLocation() {
    var plant = plant("STK-P7");
    var defaultLoc = location(plant, DEFAULT_LOCATION_CODE);
    sparepart(plant, "MC-0007");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-legacy@syncro.dev");
    assign(user, plant);

    var created = service.upsert(user, new UpsertBalanceCommand("MC-0007", plant.getId(),
        new BigDecimal("9"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("2")));

    assertThat(created.locationId()).isEqualTo(defaultLoc.getId());
  }

  @Test
  @DisplayName("18.3-LOC-009 P0 legacy list with no locationId returns the plant's rows")
  void legacyListUnchanged() {
    var plant = plant("STK-P8");
    var defaultLoc = location(plant, DEFAULT_LOCATION_CODE);
    var sparepart = sparepart(plant, "MC-0008");
    balance(sparepart.getId(), defaultLoc.getId(), "4", "6");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-legacylist@syncro.dev");
    assign(user, plant);

    var rows = service.list(user, plant.getId());

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().locationId()).isEqualTo(defaultLoc.getId());
  }

  @Test
  @DisplayName("18.3-LOC-010 P0 legacy list with an explicit locationId returns that location's rows")
  void legacyListWithLocationId() {
    var plant = plant("STK-P9");
    var defaultLoc = location(plant, DEFAULT_LOCATION_CODE);
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0009");
    balance(sparepart.getId(), defaultLoc.getId(), "10", "5");
    balance(sparepart.getId(), locB.getId(), "3", "5");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-legacyloc@syncro.dev");
    assign(user, plant);

    var rows = service.list(user, plant.getId(), locB.getId());

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().locationId()).isEqualTo(locB.getId());
  }

  @Test
  @DisplayName("18.3-LOC-011 P0 legacy path with a foreign-plant locationId → 404 (no cross-plant leak)")
  void legacyPathForeignLocationRejected() {
    var home = plant("STK-P10A");
    var foreign = plant("STK-P10B");
    var foreignLoc = location(foreign, "WS-X");
    sparepart(home, "MC-0010");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-foreign@syncro.dev");
    assign(user, home);

    assertThatThrownBy(() -> service.list(user, home.getId(), foreignLoc.getId()))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    assertThatThrownBy(() -> service.upsert(user, new UpsertBalanceCommand("MC-0010",
        home.getId(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        foreignLoc.getId())))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  // --- Reorder per location ----------------------------------------------------

  @Test
  @DisplayName("18.3-LOC-012 P0 reorder warning fires at loc B only (available 3 <= minimum 5)")
  void reorderWarningIsPerLocation() {
    var plant = plant("STK-P11");
    var locA = location(plant, "WS-A");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0011");
    balance(sparepart.getId(), locA.getId(), "10", "5");
    balance(sparepart.getId(), locB.getId(), "3", "5");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-reorder@syncro.dev");
    assign(user, plant);

    var warningsAtB = service.reorderWarnings(user, plant.getId(), locB.getId());
    var warningsAtA = service.reorderWarnings(user, plant.getId(), locA.getId());

    assertThat(warningsAtB).hasSize(1);
    assertThat(warningsAtB.getFirst().locationId()).isEqualTo(locB.getId());
    assertThat(warningsAtB.getFirst().reorderWarning()).isTrue();
    assertThat(warningsAtA).isEmpty();
  }

  @Test
  @DisplayName("18.3-LOC-013 P1 plant-wide reorder warnings include the warning row at loc B")
  void plantWideReorderIncludesWarningRow() {
    var plant = plant("STK-P12");
    var locA = location(plant, "WS-A");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0012");
    balance(sparepart.getId(), locA.getId(), "10", "5");
    balance(sparepart.getId(), locB.getId(), "3", "5");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-plantreorder@syncro.dev");
    assign(user, plant);

    var warnings = service.reorderWarnings(user, plant.getId());

    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().locationId()).isEqualTo(locB.getId());
  }

  // --- Gates -------------------------------------------------------------------

  @Test
  @DisplayName("18.3-LOC-014 P0 TECHNICIAN cannot mutate stock at a location (403)")
  void technicianCannotMutateAtLocation() {
    var plant = plant("STK-P13");
    var locB = location(plant, "WS-B");
    sparepart(plant, "MC-0013");
    balance(sparepart(plant, "MC-0013").getId(), locB.getId(), "5", "2");
    var tech = persistedUser(ApplicationRole.TECHNICIAN, "stk-tech@syncro.dev");
    assign(tech, plant);

    assertThatThrownBy(() -> service.upsertAtLocation(tech, locB.getId(), new LocationUpsertCommand(
        "MC-0013", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
        .isInstanceOf(InventoryStockForbiddenException.class);
    assertThatThrownBy(() -> service.adjustAtLocation(tech, locB.getId(), "MC-0013", BigDecimal.ONE))
        .isInstanceOf(InventoryStockForbiddenException.class);
  }

  @Test
  @DisplayName("18.3-LOC-015 P0 STOREKEEPER outside the location's plant cannot mutate (404, no oracle)")
  void outOfScopeStorekeeperRejected() {
    var home = plant("STK-P14A");
    var foreign = plant("STK-P14B");
    var foreignLoc = location(foreign, "WS-F");
    sparepart(foreign, "MC-0014");
    var outsider = persistedUser(ApplicationRole.STOREKEEPER, "stk-outsider@syncro.dev");
    assign(outsider, home);

    // Right role, wrong plant: the location-scoped surface reports 404 (indistinguishable
    // from an unknown id) rather than 403, so plant membership is never leaked.
    assertThatThrownBy(() -> service.upsertAtLocation(outsider, foreignLoc.getId(),
        new LocationUpsertCommand("MC-0014", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  @Test
  @DisplayName("18.3-LOC-016 P0 SUPER_ADMIN bypasses the plant-scope gate on the location surface")
  void superAdminBypassesScope() {
    var plant = plant("STK-P15");
    var locB = location(plant, "WS-B");
    sparepart(plant, "MC-0015");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = service.upsertAtLocation(admin, locB.getId(), new LocationUpsertCommand(
        "MC-0015", new BigDecimal("6"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1")));

    assertThat(created.locationId()).isEqualTo(locB.getId());
  }

  // --- Optimistic lock ---------------------------------------------------------

  @Test
  @DisplayName("18.3-LOC-017 P0 legacy PUT with a stale version → 409 VERSION_CONFLICT")
  void updateVersionConflict() {
    var plant = plant("STK-P16");
    var defaultLoc = location(plant, DEFAULT_LOCATION_CODE);
    var sparepart = sparepart(plant, "MC-0016");
    balance(sparepart.getId(), defaultLoc.getId(), "10", "5");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-version@syncro.dev");
    assign(user, plant);

    assertThatThrownBy(() -> service.update(user, "MC-0016", new UpdateBalanceCommand(
        plant.getId(), 99L, new BigDecimal("8"), null, null, null)))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("18.3-LOC-018 P0 legacy PUT with a locationId targets that location")
  void updateAtLocation() {
    var plant = plant("STK-P17");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0017");
    balance(sparepart.getId(), locB.getId(), "10", "5");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-updateloc@syncro.dev");
    assign(user, plant);

    var updated = service.update(user, "MC-0017", new UpdateBalanceCommand(plant.getId(), 0L,
        new BigDecimal("8"), null, null, null, locB.getId()));

    assertThat(updated.locationId()).isEqualTo(locB.getId());
    assertThat(updated.available()).isEqualByComparingTo("8");
    assertThat(updated.version()).isEqualTo(1L);
  }

  // --- Plant integrity: cross-plant sparepart (review PATCH 1) -------------------

  @Test
  @DisplayName("18.3-LOC-019 P0 upsertAtLocation with a sparepart from another plant → 404 SPAREPART_NOT_FOUND")
  void upsertAtLocationRejectsForeignPlantSparepart() {
    var plantA = plant("STK-P19A");
    var plantB = plant("STK-P19B");
    var locA = location(plantA, "WS-A");
    var foreignPart = sparepart(plantB, "MC-0019");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-xplant@syncro.dev");
    assign(user, plantA);

    assertThatThrownBy(() -> service.upsertAtLocation(user, locA.getId(),
        new LocationUpsertCommand("MC-0019", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO)))
        .isInstanceOf(SparepartNotFoundException.class);
    // Nothing attached: plant A's location has no balance for the foreign part.
    assertThat(balances.findBySparepartIdAndLocationId(foreignPart.getId(), locA.getId()))
        .isEmpty();
  }

  @Test
  @DisplayName("18.3-LOC-020 P0 legacy upsert with a locationId whose plant lacks the sparepart → 404")
  void legacyUpsertWithLocationIdRejectsForeignPlantSparepart() {
    var plantA = plant("STK-P20A");
    var plantB = plant("STK-P20B");
    var locA = location(plantA, "WS-A");
    sparepart(plantB, "MC-0020");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "stk-xplant-legacy@syncro.dev");
    assign(user, plantA);

    // plantId says A, locationId is A's location, but the material belongs to plant B.
    assertThatThrownBy(() -> service.upsert(user, new UpsertBalanceCommand("MC-0020",
        plantA.getId(), BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        locA.getId())))
        .isInstanceOf(SparepartNotFoundException.class);
  }

  // --- Inactive location (review PATCH 2) ----------------------------------------

  @Test
  @DisplayName("18.3-LOC-021 P0 mutation at an inactive location → 404; reads still succeed")
  void inactiveLocationRejectsMutationAllowsRead() {
    var plant = plant("STK-P21");
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0021");
    balance(sparepart.getId(), locB.getId(), "5", "2");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-inactive@syncro.dev");
    assign(user, plant);
    locations.saveAndFlush(new InventoryLocationEntity(locB.getId(), plant.getId(),
        locB.getCode(), locB.getName(), locB.getDescription(), false, locB.getCreatedAt(), NOW));

    assertThatThrownBy(() -> service.adjustAtLocation(user, locB.getId(), "MC-0021",
        BigDecimal.ONE))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    assertThatThrownBy(() -> service.upsertAtLocation(user, locB.getId(),
        new LocationUpsertCommand("MC-0021", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    // Reads still see the historical balance at the inactive location.
    assertThat(service.listAtLocation(user, locB.getId())).hasSize(1);
  }

  // --- Existence oracle on the location-scoped surface (review PATCH 3) ----------

  @Test
  @DisplayName("18.3-LOC-022 P0 location-scoped read/mutation without plant assignment → 404 (not 403)")
  void unassignedPlantIsNotFoundOnLocationSurface() {
    var home = plant("STK-P22A");
    var foreign = plant("STK-P22B");
    var foreignLoc = location(foreign, "WS-F");
    sparepart(foreign, "MC-0022");
    var outsider = persistedUser(ApplicationRole.STOREKEEPER, "stk-oracle@syncro.dev");
    assign(outsider, home);

    // STOREKEEPER has the right ROLE but no assignment to the foreign plant: the
    // foreign location id must be indistinguishable from an unknown one (404).
    assertThatThrownBy(() -> service.listAtLocation(outsider, foreignLoc.getId()))
        .isInstanceOf(InventoryLocationNotFoundException.class);
    assertThatThrownBy(() -> service.upsertAtLocation(outsider, foreignLoc.getId(),
        new LocationUpsertCommand("MC-0022", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  @Test
  @DisplayName("18.3-LOC-023 P0 wrong role on the location mutation surface → 403 before location is resolved")
  void wrongRoleDeniedBeforeLocationProbe() {
    var tech = persistedUser(ApplicationRole.TECHNICIAN, "stk-oracle-role@syncro.dev");

    // Even a random (non-existent) location id yields 403, not 404 — the role gate
    // runs first, so an unauthorized role cannot probe location existence at all.
    assertThatThrownBy(() -> service.upsertAtLocation(tech, UUID.randomUUID(),
        new LocationUpsertCommand("MC-X", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO)))
        .isInstanceOf(InventoryStockForbiddenException.class);
    assertThatThrownBy(() -> service.adjustAtLocation(tech, UUID.randomUUID(), "MC-X",
        BigDecimal.ONE))
        .isInstanceOf(InventoryStockForbiddenException.class);
  }

  // --- Legacy command with explicit locationId (review PATCH 4b) -----------------

  @Test
  @DisplayName("18.3-LOC-024 P0 legacy adjust with locationId touches only that location's row")
  void legacyAdjustWithLocationIdIsScoped() {
    var plant = plant("STK-P24");
    var defaultLoc = location(plant, DEFAULT_LOCATION_CODE);
    var locB = location(plant, "WS-B");
    var sparepart = sparepart(plant, "MC-0024");
    var defaultRow = balance(sparepart.getId(), defaultLoc.getId(), "10", "5");
    var rowB = balance(sparepart.getId(), locB.getId(), "10", "5");
    var user = persistedUser(ApplicationRole.STOREKEEPER, "stk-legacyadjust@syncro.dev");
    assign(user, plant);

    var adjusted = service.adjust(user, "MC-0024",
        new AdjustBalanceCommand(plant.getId(), new BigDecimal("-4"), locB.getId()));

    assertThat(adjusted.locationId()).isEqualTo(locB.getId());
    assertThat(adjusted.available()).isEqualByComparingTo("6");
    assertThat(balances.findById(rowB.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("6");
    // The default-location row is untouched.
    assertThat(balances.findById(defaultRow.getId()).orElseThrow().getAvailable())
        .isEqualByComparingTo("10");
  }

  // --- helpers -----------------------------------------------------------------

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

  private SparepartEntity sparepart(PlantEntity plant, String materialCode) {
    var existing = spareparts.findByMaterialCodeIgnoreCase(materialCode);
    if (existing.isPresent()) {
      return existing.get();
    }
    var n = SEQ.incrementAndGet();
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant,
        "Group " + n, NOW, NOW));
    var machine = machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group,
        "M-" + n, "Machine " + n, MachineStatus.ACTIVE, "Juki", LocalDate.parse("2026-05-27"),
        null, List.of(), NOW, NOW));
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

  private InventoryStockBalanceEntity balance(UUID sparepartId, UUID locationId,
      String available, String minimumStock) {
    return balances.saveAndFlush(new InventoryStockBalanceEntity(UUID.randomUUID(), sparepartId,
        locationId, new BigDecimal(available), BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal(minimumStock), NOW, NOW));
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
