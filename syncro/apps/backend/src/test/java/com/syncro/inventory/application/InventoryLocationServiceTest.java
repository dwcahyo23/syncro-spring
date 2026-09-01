package com.syncro.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.inventory.application.InventoryLocationService.CreateLocationCommand;
import com.syncro.inventory.application.InventoryLocationService.DuplicateLocationException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationValidationException;
import com.syncro.inventory.application.InventoryLocationService.UpdateLocationCommand;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 18-2 unit tests for the constraint-mapping and validation branches that the
 * Testcontainers integration test cannot isolate: the {@code save()} race backstop
 * (uq_inventory_locations_plant_code → DUPLICATE_LOCATION, other violations rethrown)
 * and the default-location rename guard.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryLocationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
  private static final String DUPLICATE_CONSTRAINT = "uq_inventory_locations_plant_code";

  @Mock
  private InventoryLocationRepository locations;
  @Mock
  private PlantRepository plants;
  @Mock
  private AuthUserPlantAssignmentRepository assignments;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID plantId = UUID.randomUUID();
  private final UUID locationId = UUID.randomUUID();

  private InventoryLocationService service;

  @BeforeEach
  void setUp() {
    service = new InventoryLocationService(locations, plants, assignments, auditLog, clock);
    lenient().when(plants.findById(plantId))
        .thenReturn(Optional.of(new PlantEntity(plantId, "PLANT-1", "Plant 1", NOW, NOW)));
    lenient().when(locations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private AuthenticatedUser adminUser() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
  }

  private InventoryLocationEntity location(String code) {
    return new InventoryLocationEntity(locationId, plantId, code, "Store", null, true, NOW, NOW);
  }

  private static DataIntegrityViolationException constraintViolation(String constraintMessage) {
    return new DataIntegrityViolationException("could not execute statement",
        new RuntimeException(constraintMessage));
  }

  @Test
  @DisplayName("18.2-UNIT-001 P0 create save() uq_inventory_locations_plant_code → DuplicateLocationException")
  void createConstraintViolationMapsToDuplicate() {
    var user = adminUser();
    when(locations.findByPlantIdAndCodeIgnoreCase(plantId, "WS-01")).thenReturn(Optional.empty());
    when(locations.saveAndFlush(any())).thenThrow(constraintViolation(
        "Unique constraint violation for table \"inventory_locations\": " + DUPLICATE_CONSTRAINT));

    assertThatThrownBy(() -> service.create(user,
        new CreateLocationCommand(plantId, "ws-01", "Workshop", null)))
        .isInstanceOf(DuplicateLocationException.class);
  }

  @Test
  @DisplayName("18.2-UNIT-002 P0 create save() unrelated violation is rethrown unchanged")
  void createUnrelatedViolationRethrown() {
    var user = adminUser();
    var violation = constraintViolation("violates foreign key constraint fk_inventory_locations_plant");
    when(locations.findByPlantIdAndCodeIgnoreCase(plantId, "WS-01")).thenReturn(Optional.empty());
    when(locations.saveAndFlush(any())).thenThrow(violation);

    assertThatThrownBy(() -> service.create(user,
        new CreateLocationCommand(plantId, "WS-01", "Workshop", null)))
        .isSameAs(violation);
  }

  @Test
  @DisplayName("18.2-UNIT-003 P0 update save() uq_inventory_locations_plant_code → DuplicateLocationException")
  void updateConstraintViolationMapsToDuplicate() {
    var user = adminUser();
    when(locations.findById(locationId)).thenReturn(Optional.of(location("OLD-1")));
    when(locations.existsByPlantIdAndCodeIgnoreCaseAndIdNot(plantId, "NEW-1", locationId))
        .thenReturn(false);
    when(locations.saveAndFlush(any())).thenThrow(constraintViolation(DUPLICATE_CONSTRAINT));

    assertThatThrownBy(() -> service.update(user, locationId,
        new UpdateLocationCommand("NEW-1", null, null, null)))
        .isInstanceOf(DuplicateLocationException.class);
  }

  @Test
  @DisplayName("18.2-UNIT-004 P0 renaming the default location is rejected before save")
  void defaultLocationRenameRejected() {
    var user = adminUser();
    when(locations.findById(locationId)).thenReturn(Optional.of(location("GUDANG-UTAMA")));

    assertThatThrownBy(() -> service.update(user, locationId,
        new UpdateLocationCommand("GUDANG-BARU", null, null, null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    verify(locations, org.mockito.Mockito.never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("18.2-UNIT-005 P0 deactivating the default location (no code change) is allowed")
  void defaultLocationDeactivateAllowed() {
    var user = adminUser();
    when(locations.findById(locationId)).thenReturn(Optional.of(location("GUDANG-UTAMA")));

    var deactivated = service.update(user, locationId, new UpdateLocationCommand(null, null, null, false));

    assertThat(deactivated.active()).isFalse();
    assertThat(deactivated.code()).isEqualTo("GUDANG-UTAMA");
  }

  @Test
  @DisplayName("18.2-UNIT-006 P0 all-absent update body is rejected")
  void emptyUpdateBodyRejected() {
    var user = adminUser();
    when(locations.findById(locationId)).thenReturn(Optional.of(location("WS-01")));

    assertThatThrownBy(() -> service.update(user, locationId,
        new UpdateLocationCommand(null, null, null, null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    verify(locations, org.mockito.Mockito.never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("18.2-UNIT-008 P0 update with a blank code hits the requireText branch (400)")
  void updateBlankCodeRejected() {
    var user = adminUser();
    when(locations.findById(locationId)).thenReturn(Optional.of(location("WS-01")));

    assertThatThrownBy(() -> service.update(user, locationId,
        new UpdateLocationCommand("   ", null, null, null)))
        .isInstanceOf(InventoryLocationValidationException.class);
    verify(locations, org.mockito.Mockito.never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("18.2-UNIT-007 P1 create normalizes the code to uppercase before the duplicate pre-check")
  void createNormalizesCodeBeforePreCheck() {
    var user = adminUser();
    when(locations.findByPlantIdAndCodeIgnoreCase(eq(plantId), eq("WS-01"))).thenReturn(Optional.empty());

    var created = service.create(user, new CreateLocationCommand(plantId, "  ws-01 ", "Workshop", null));

    assertThat(created.code()).isEqualTo("WS-01");
    verify(locations).findByPlantIdAndCodeIgnoreCase(plantId, "WS-01");
  }
}
