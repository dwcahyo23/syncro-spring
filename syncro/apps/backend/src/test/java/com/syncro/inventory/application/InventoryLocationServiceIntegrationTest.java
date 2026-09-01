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
import com.syncro.inventory.application.InventoryLocationService.CreateLocationCommand;
import com.syncro.inventory.application.InventoryLocationService.DuplicateLocationException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationForbiddenException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationValidationException;
import com.syncro.inventory.application.InventoryLocationService.PlantNotFoundForLocationException;
import com.syncro.inventory.application.InventoryLocationService.UpdateLocationCommand;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Story 18-2: inventory-location lifecycle against a real Postgres (Testcontainers).
 * Covers every row of the spec's I/O matrix: create + audit, case-insensitive
 * duplicate per plant, same code in another plant, update + audit previous/new,
 * default-location deactivation, wrong-role and out-of-scope 403, list ordering and
 * activeOnly filtering, unknown plant 404, and the DB unique constraint as the race
 * backstop.
 */
class InventoryLocationServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  private static final String DEFAULT_LOCATION_CODE = "GUDANG-UTAMA";

  @Autowired
  private InventoryLocationService service;

  @Autowired
  private InventoryLocationRepository locations;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private AuditLogRepository auditLogs;

  @Autowired
  private PasswordEncoder passwordEncoder;

  private final ObjectMapper mapper = new ObjectMapper();

  // --- Create ----------------------------------------------------------------

  @Test
  @DisplayName("18.2-SVC-001 P0 INVENTORY_MAINTENANCE in scope creates an active location and audits CREATE")
  void createPersistsActiveLocationAndAudits() throws Exception {
    var plant = plant("LOC-P1");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-create@syncro.dev");
    assign(user, plant);

    var created = service.create(user, new CreateLocationCommand(plant.getId(), "WS-01", "Workshop 01",
        "  Workshop for forming machines  "));

    assertThat(created.id()).isNotNull();
    assertThat(created.plantId()).isEqualTo(plant.getId());
    assertThat(created.code()).isEqualTo("WS-01");
    assertThat(created.name()).isEqualTo("Workshop 01");
    assertThat(created.description()).isEqualTo("Workshop for forming machines");
    assertThat(created.active()).isTrue();
    var stored = locations.findById(created.id()).orElseThrow();
    assertThat(stored.isActive()).isTrue();
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_LOCATION);
    assertThat(entry.getPlantId()).isEqualTo(plant.getId());
    assertThat(entry.getEntityLabel()).isEqualTo("WS-01 @LOC-P1");
    assertThat(entry.getPreviousValue()).isNull();
    assertThat(mapper.readTree(entry.getNewValue()).get("code").asText()).isEqualTo("WS-01");
    assertThat(mapper.readTree(entry.getNewValue()).get("active").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("18.2-SVC-002 P0 MANAGER_MAINTENANCE in scope may create; SUPER_ADMIN bypasses scope")
  void managerAndSuperAdminMayCreate() {
    var plant = plant("LOC-P2");
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "loc-manager@syncro.dev");
    assign(manager, plant);
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThat(service.create(manager, new CreateLocationCommand(plant.getId(), "ST-A", "Store A", null)).active())
        .isTrue();
    assertThat(service.create(admin, new CreateLocationCommand(plant.getId(), "ST-B", "Store B", null)).active())
        .isTrue();
  }

  @Test
  @DisplayName("18.2-SVC-003 P0 blank code or name is a validation error with no row persisted")
  void blankCodeOrNameRejected() {
    var plant = plant("LOC-P3");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-blank@syncro.dev");
    assign(user, plant);

    assertThatThrownBy(() -> service.create(user, new CreateLocationCommand(plant.getId(), "   ", "Name", null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    assertThatThrownBy(() -> service.create(user, new CreateLocationCommand(plant.getId(), "CODE-1", "", null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    assertThat(locations.findAllByPlantIdOrderByNameAsc(plant.getId())).isEmpty();
  }

  @Test
  @DisplayName("18.2-SVC-004 P0 unknown plant on create → PLANT_NOT_FOUND")
  void createUnknownPlantRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.create(admin, new CreateLocationCommand(UUID.randomUUID(), "X-1", "X", null)))
        .isInstanceOf(PlantNotFoundForLocationException.class);
  }

  // --- Duplicate -------------------------------------------------------------

  @Test
  @DisplayName("18.2-SVC-005 P0 duplicate (plant, code) case-insensitively → DUPLICATE_LOCATION")
  void duplicateCodePerPlantRejectedCaseInsensitively() {
    var plant = plant("LOC-P4");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-dup@syncro.dev");
    assign(user, plant);
    service.create(user, new CreateLocationCommand(plant.getId(), "GUDANG-B", "Store B", null));

    assertThatThrownBy(() -> service.create(user, new CreateLocationCommand(plant.getId(), "gudang-b", "Other", null)))
        .isInstanceOf(DuplicateLocationException.class);
    assertThatThrownBy(() -> service.create(user, new CreateLocationCommand(plant.getId(), " GUDANG-B ", "Other", null)))
        .isInstanceOf(DuplicateLocationException.class);
  }

  @Test
  @DisplayName("18.2-SVC-006 P0 the same code in a different plant succeeds (uniqueness is per-plant)")
  void sameCodeDifferentPlantSucceeds() {
    var first = plant("LOC-P5A");
    var second = plant("LOC-P5B");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-multi@syncro.dev");
    assign(user, first);
    assign(user, second);

    var a = service.create(user, new CreateLocationCommand(first.getId(), "SHARED-1", "Store A", null));
    var b = service.create(user, new CreateLocationCommand(second.getId(), "SHARED-1", "Store B", null));

    assertThat(a.id()).isNotEqualTo(b.id());
    assertThat(b.plantId()).isEqualTo(second.getId());
  }

  @Test
  @DisplayName("18.2-SVC-007 P0 uq_inventory_locations_plant_code is the race backstop for concurrent inserts")
  void dbConstraintBackstopsRace() {
    var plant = plant("LOC-P6");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    service.create(admin, new CreateLocationCommand(plant.getId(), "RACE-1", "First", null));

    // Bypass the service pre-check exactly like a concurrent transaction would:
    // the plain UNIQUE (plant_id, code) must reject the second row.
    assertThatThrownBy(() -> locations.saveAndFlush(new InventoryLocationEntity(
        UUID.randomUUID(), plant.getId(), "RACE-1", "Second", null, true, NOW, NOW)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_inventory_locations_plant_code");
  }

  // --- Update ----------------------------------------------------------------

  @Test
  @DisplayName("18.2-SVC-008 P0 MANAGER_MAINTENANCE renames and audits UPDATE with previous+new values")
  void updateRenamesAndAuditsPreviousAndNew() throws Exception {
    var plant = plant("LOC-P7");
    var manager = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "loc-update@syncro.dev");
    assign(manager, plant);
    var created = service.create(manager, new CreateLocationCommand(plant.getId(), "OLD-CODE", "Old Name", "Old desc"));

    var updated = service.update(manager, created.id(),
        new UpdateLocationCommand("NEW-CODE", "New Name", "New desc", null));

    assertThat(updated.id()).isEqualTo(created.id());
    assertThat(updated.code()).isEqualTo("NEW-CODE");
    assertThat(updated.name()).isEqualTo("New Name");
    assertThat(updated.description()).isEqualTo("New desc");
    assertThat(updated.active()).isTrue();
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_LOCATION);
    var previous = mapper.readTree(entry.getPreviousValue());
    var newValue = mapper.readTree(entry.getNewValue());
    assertThat(previous.get("code").asText()).isEqualTo("OLD-CODE");
    assertThat(previous.get("name").asText()).isEqualTo("Old Name");
    assertThat(newValue.get("code").asText()).isEqualTo("NEW-CODE");
    assertThat(newValue.get("name").asText()).isEqualTo("New Name");
  }

  @Test
  @DisplayName("18.2-SVC-009 P0 isActive-only PUT deactivates and keeps code/name (partial update)")
  void isActiveOnlyUpdateKeepsOtherFields() {
    var plant = plant("LOC-P8");
    var user = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-toggle@syncro.dev");
    assign(user, plant);
    var created = service.create(user, new CreateLocationCommand(plant.getId(), "TOGGLE-1", "Toggle Store", "desc"));

    var deactivated = service.update(user, created.id(), new UpdateLocationCommand(null, null, null, false));
    var reactivated = service.update(user, created.id(), new UpdateLocationCommand(null, null, null, true));

    assertThat(deactivated.active()).isFalse();
    assertThat(deactivated.code()).isEqualTo("TOGGLE-1");
    assertThat(deactivated.name()).isEqualTo("Toggle Store");
    assertThat(deactivated.description()).isEqualTo("desc");
    assertThat(reactivated.active()).isTrue();
  }

  @Test
  @DisplayName("18.2-SVC-010 P0 deactivating the default GUDANG-UTAMA location is allowed (soft) and audited")
  void deactivateDefaultLocationAllowed() {
    var plant = plant("LOC-P9");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var defaultLocation = service.create(admin,
        new CreateLocationCommand(plant.getId(), DEFAULT_LOCATION_CODE, "GUDANG UTAMA", "Default plant store"));

    var deactivated = service.update(admin, defaultLocation.id(),
        new UpdateLocationCommand(null, null, null, false));

    assertThat(deactivated.active()).isFalse();
    assertThat(locations.findById(defaultLocation.id())).isPresent();
    var entry = latestAuditEntryFor(defaultLocation.id());
    assertThat(entry.getAction()).isEqualTo(AuditAction.UPDATE);
    assertThat(entry.getNewValue()).contains("\"active\":false");
  }

  @Test
  @DisplayName("18.2-SVC-011 P0 update of an unknown id → INVENTORY_LOCATION_NOT_FOUND")
  void updateUnknownIdRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.update(admin, UUID.randomUUID(),
        new UpdateLocationCommand("ANY", "Any", null, null)))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  @Test
  @DisplayName("18.2-SVC-012 P1 update renaming onto another location's code (any case) → DUPLICATE_LOCATION")
  void updateRenameOntoExistingCodeRejected() {
    var plant = plant("LOC-P10");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var taken = service.create(admin, new CreateLocationCommand(plant.getId(), "TAKEN-1", "Taken", null));
    var other = service.create(admin, new CreateLocationCommand(plant.getId(), "OTHER-1", "Other", null));

    assertThatThrownBy(() -> service.update(admin, other.id(),
        new UpdateLocationCommand("taken-1", "Other", null, null)))
        .isInstanceOf(DuplicateLocationException.class);
    assertThat(locations.findById(taken.id())).isPresent();
  }

  @Test
  @DisplayName("18.2-SVC-013 P1 update keeping its own code (any case) succeeds and stores it uppercase")
  void updateKeepingOwnCodeSucceeds() {
    var plant = plant("LOC-P11");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = service.create(admin, new CreateLocationCommand(plant.getId(), "SELF-1", "Self", null));

    var updated = service.update(admin, created.id(),
        new UpdateLocationCommand("self-1", "Self Renamed", null, null));

    // PATCH 2: codes are normalized to UPPERCASE on write so the case-sensitive DB
    // UNIQUE cannot be raced by "self-1" vs "SELF-1".
    assertThat(updated.code()).isEqualTo("SELF-1");
    assertThat(updated.name()).isEqualTo("Self Renamed");
    assertThat(locations.findById(created.id()).orElseThrow().getCode()).isEqualTo("SELF-1");
  }

  @Test
  @DisplayName("18.2-SVC-020 P0 create stores a lowercase code normalized to uppercase")
  void createNormalizesCodeToUppercase() {
    var plant = plant("LOC-P20");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = service.create(admin, new CreateLocationCommand(plant.getId(), "ws-01", "Workshop", null));

    assertThat(created.code()).isEqualTo("WS-01");
    assertThat(locations.findByPlantIdAndCodeIgnoreCase(plant.getId(), "WS-01")).isPresent();
  }

  @Test
  @DisplayName("18.2-SVC-021 P0 renaming the default GUDANG-UTAMA location is rejected (400)")
  void defaultLocationRenameRejected() {
    var plant = plant("LOC-P21");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var defaultLocation = service.create(admin,
        new CreateLocationCommand(plant.getId(), DEFAULT_LOCATION_CODE, "GUDANG UTAMA", null));

    assertThatThrownBy(() -> service.update(admin, defaultLocation.id(),
        new UpdateLocationCommand("GUDANG-LAMA", null, null, null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    // The stored code is untouched — InventoryStockService.defaultLocation() still resolves.
    assertThat(locations.findById(defaultLocation.id()).orElseThrow().getCode())
        .isEqualTo(DEFAULT_LOCATION_CODE);
  }

  @Test
  @DisplayName("18.2-SVC-022 P0 default location may keep its code case-insensitively and still rename others")
  void defaultLocationSameCodeUpdateAllowed() {
    var plant = plant("LOC-P22");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var defaultLocation = service.create(admin,
        new CreateLocationCommand(plant.getId(), DEFAULT_LOCATION_CODE, "GUDANG UTAMA", null));

    // Re-submitting the default code (any case) is not a rename — allowed.
    var kept = service.update(admin, defaultLocation.id(),
        new UpdateLocationCommand("gudang-utama", "GUDANG UTAMA", "Main store", null));
    assertThat(kept.code()).isEqualTo(DEFAULT_LOCATION_CODE);
    assertThat(kept.description()).isEqualTo("Main store");
  }

  @Test
  @DisplayName("18.2-SVC-023 P0 empty PUT body is rejected (no no-op audit row)")
  void emptyUpdateBodyRejected() {
    var plant = plant("LOC-P23");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = service.create(admin, new CreateLocationCommand(plant.getId(), "EMPTY-1", "Empty", null));
    var auditCountBefore = auditCountFor(created.id());

    assertThatThrownBy(() -> service.update(admin, created.id(),
        new UpdateLocationCommand(null, null, null, null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    assertThat(auditCountFor(created.id())).isEqualTo(auditCountBefore);
  }

  // --- Gates -----------------------------------------------------------------

  @Test
  @DisplayName("18.2-SVC-014 P0 STOREKEEPER/STAFF_MAINTENANCE/TECHNICIAN cannot mutate locations (403)")
  void storekeeperAndOthersCannotMutate() {
    var plant = plant("LOC-P12");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = service.create(admin, new CreateLocationCommand(plant.getId(), "GATE-1", "Gate Store", null));
    for (var role : List.of(ApplicationRole.STOREKEEPER, ApplicationRole.STAFF_MAINTENANCE,
        ApplicationRole.TECHNICIAN, ApplicationRole.AUDITOR)) {
      var user = persistedUser(role, "loc-gate-" + role.name().toLowerCase() + "@syncro.dev");
      assign(user, plant);

      assertThatThrownBy(() -> service.create(user, new CreateLocationCommand(plant.getId(), "GATE-X", "X", null)))
          .as(role + " must not create locations")
          .isInstanceOf(InventoryLocationForbiddenException.class);
      assertThatThrownBy(() -> service.update(user, created.id(), new UpdateLocationCommand(null, null, null, false)))
          .as(role + " must not update locations")
          .isInstanceOf(InventoryLocationForbiddenException.class);
    }
    assertThat(locations.findById(created.id()).orElseThrow().isActive()).isTrue();
  }

  @Test
  @DisplayName("18.2-SVC-015 P0 INVENTORY_MAINTENANCE outside the plant scope cannot mutate (403)")
  void outOfScopePlantMutationRejected() {
    var home = plant("LOC-P13A");
    var foreign = plant("LOC-P13B");
    var outsider = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-outsider@syncro.dev");
    assign(outsider, home);
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var foreignLocation = service.create(admin, new CreateLocationCommand(foreign.getId(), "FOREIGN-1", "Foreign", null));

    assertThatThrownBy(() -> service.create(outsider, new CreateLocationCommand(foreign.getId(), "NOPE", "Nope", null)))
        .isInstanceOf(InventoryLocationForbiddenException.class);
    assertThatThrownBy(() -> service.update(outsider, foreignLocation.id(),
        new UpdateLocationCommand(null, "Hijacked", null, null)))
        .isInstanceOf(InventoryLocationForbiddenException.class);
    assertThat(locations.findById(foreignLocation.id()).orElseThrow().getName()).isEqualTo("Foreign");
  }

  @Test
  @DisplayName("18.2-SVC-016 P0 reads succeed for any assigned user (STOREKEEPER included) and deny outsiders")
  void readsFollowPlantAssignment() {
    var plant = plant("LOC-P14");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = service.create(admin, new CreateLocationCommand(plant.getId(), "READ-1", "Read Store", null));
    var storekeeper = persistedUser(ApplicationRole.STOREKEEPER, "loc-reader@syncro.dev");
    assign(storekeeper, plant);
    var outsider = persistedUser(ApplicationRole.INVENTORY_MAINTENANCE, "loc-outsider-read@syncro.dev");
    assign(outsider, plant("LOC-P14B"));

    assertThat(service.list(storekeeper, plant.getId(), false)).extracting("id").containsExactly(created.id());
    assertThat(service.get(storekeeper, created.id()).code()).isEqualTo("READ-1");
    assertThat(service.get(admin, created.id()).id()).isEqualTo(created.id());
    assertThatThrownBy(() -> service.list(outsider, plant.getId(), false))
        .isInstanceOf(InventoryLocationForbiddenException.class);
    assertThatThrownBy(() -> service.get(outsider, created.id()))
        .isInstanceOf(InventoryLocationForbiddenException.class);
  }

  // --- List / get --------------------------------------------------------------

  @Test
  @DisplayName("18.2-SVC-017 P0 list returns only the plant's locations ordered by name; activeOnly filters")
  void listIsPlantScopedOrderedAndFilterable() {
    var plant = plant("LOC-P15");
    var other = plant("LOC-P15B");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var zulu = service.create(admin, new CreateLocationCommand(plant.getId(), "Z-1", "Zulu Store", null));
    var alpha = service.create(admin, new CreateLocationCommand(plant.getId(), "A-1", "Alpha Store", null));
    var mike = service.create(admin, new CreateLocationCommand(plant.getId(), "M-1", "Mike Store", null));
    var bravo = service.create(admin, new CreateLocationCommand(plant.getId(), "B-1", "Bravo Store", null));
    service.create(admin, new CreateLocationCommand(other.getId(), "O-1", "Aardvark Other", null));
    service.update(admin, mike.id(), new UpdateLocationCommand(null, null, null, false));

    var all = service.list(admin, plant.getId(), false);
    var activeOnly = service.list(admin, plant.getId(), true);

    assertThat(all).hasSize(4);
    assertThat(all).extracting("id").containsExactly(alpha.id(), bravo.id(), mike.id(), zulu.id());
    assertThat(all).extracting("name").containsExactly("Alpha Store", "Bravo Store", "Mike Store", "Zulu Store");
    assertThat(activeOnly).hasSize(3);
    assertThat(activeOnly).extracting("name").containsExactly("Alpha Store", "Bravo Store", "Zulu Store");
    assertThat(activeOnly).extracting("id").doesNotContain(mike.id());
  }

  @Test
  @DisplayName("18.2-SVC-018 P0 list for an unknown plant → PLANT_NOT_FOUND")
  void listUnknownPlantRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.list(admin, UUID.randomUUID(), false))
        .isInstanceOf(PlantNotFoundForLocationException.class);
  }

  @Test
  @DisplayName("18.2-SVC-019 P1 get of an unknown id → INVENTORY_LOCATION_NOT_FOUND")
  void getUnknownIdRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.get(admin, UUID.randomUUID()))
        .isInstanceOf(InventoryLocationNotFoundException.class);
  }

  // --- helpers -----------------------------------------------------------------

  private PlantEntity plant(String code) {
    return plants.findByCodeIgnoreCase(code)
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, "Plant " + code, NOW, NOW)));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, NOW, NOW));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), NOW));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private AuditLogEntity latestAuditEntryFor(UUID entityId) {
    var entries = auditLogs.findByEntityIdOrderByCreatedAtAsc(entityId);
    assertThat(entries).isNotEmpty();
    return entries.get(entries.size() - 1);
  }

  private long auditCountFor(UUID entityId) {
    return auditLogs.findByEntityIdOrderByCreatedAtAsc(entityId).size();
  }
}
