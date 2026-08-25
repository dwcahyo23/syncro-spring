package com.syncro.machine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.application.MachineService.DuplicateMachineCodeException;
import com.syncro.machine.application.MachineService.MachineCommand;
import com.syncro.machine.application.MachineService.MachineDataIntegrityException;
import com.syncro.machine.application.MachineService.MachineGroupPlantMismatchException;
import com.syncro.machine.application.MachineService.MachineMutationForbiddenException;
import com.syncro.machine.application.MachineService.MachineValidationException;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class MachineServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private MachineService machineService;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("2.3-SVC-001 P1 MANAGER_MAINTENANCE creates normalized active machine under assigned plant")
  void manageCreatesMachineUnderAssignedPlant() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-machine@syncro.dev");
    assign(user, plant);

    var created = machineService.create(user, command(plant.getId(), group.getId(), " bf-08410 ", MachineStatus.ACTIVE));

    assertThat(created.plantId()).isEqualTo(plant.getId());
    assertThat(created.machineGroupId()).isEqualTo(group.getId());
    assertThat(created.code()).isEqualTo("BF-08410");
    assertThat(created.status()).isEqualTo(MachineStatus.ACTIVE);
    assertThat(machines.findById(created.id())).isPresent();
  }

  @Test
  @DisplayName("2.3-SVC-002 P1 duplicate same-plant machine code is rejected case-insensitively")
  void duplicateSamePlantCodeIsRejectedCaseInsensitively() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "bf-08410", MachineStatus.INACTIVE)))
        .isInstanceOf(DuplicateMachineCodeException.class);
  }

  @Test
  @DisplayName("2.3-SVC-003 P1 same machine code is allowed across different plants")
  void sameCodeAllowedAcrossDifferentPlants() {
    var firstPlant = plant("GM1", "Plant GM1");
    var secondPlant = plant("GM2", "Plant GM2");
    var firstGroup = group(firstPlant, "Forming");
    var secondGroup = group(secondPlant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var first = machineService.create(admin, command(firstPlant.getId(), firstGroup.getId(), "BF-08410", MachineStatus.ACTIVE));
    var second = machineService.create(admin, command(secondPlant.getId(), secondGroup.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(machines.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("2.3-SVC-004 P0 database rejects case-insensitive duplicate machine codes")
  void databaseRejectsCaseInsensitiveDuplicateCodes() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var now = Instant.parse("2026-05-27T00:00:00Z");
    machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19", MachineStatus.ACTIVE,
        "Juki", LocalDate.parse("2026-05-27"), null, List.of(), now, now));

    assertThatThrownBy(() -> machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "bf-08410", "JBF20",
        MachineStatus.INACTIVE, null, null, null, List.of(), now, now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("2.3-SVC-005 P0 update cannot move machine to another plant")
  void updateCannotMoveMachineToAnotherPlant() {
    var sourcePlant = plant("GM1", "Plant GM1");
    var targetPlant = plant("GM2", "Plant GM2");
    var sourceGroup = group(sourcePlant, "Forming");
    var targetGroup = group(targetPlant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(sourcePlant.getId(), sourceGroup.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThatThrownBy(() -> machineService.update(admin, machine.id(), command(targetPlant.getId(), targetGroup.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(MachineDataIntegrityException.class);

    assertThat(machines.findById(machine.id())).get().extracting(entity -> entity.getPlant().getId()).isEqualTo(sourcePlant.getId());
  }

  @Test
  @DisplayName("2.3-SVC-006 P0 machine group must belong to machine plant")
  void machineGroupMustBelongToMachinePlant() {
    var plant = plant("GM1", "Plant GM1");
    var otherPlant = plant("GM2", "Plant GM2");
    var otherGroup = group(otherPlant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), otherGroup.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(MachineGroupPlantMismatchException.class);
  }

  @Test
  @DisplayName("2.3-SVC-007 P1 AUDITOR lists assigned plant machines only")
  void viewerListsAssignedPlantMachinesOnly() {    var assigned = plant("GM1", "Plant GM1");
    var other = plant("GM2", "Plant GM2");
    var assignedGroup = group(assigned, "Forming");
    var otherGroup = group(other, "Packing");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-machine@syncro.dev");
    assign(viewer, assigned);
    var assignedMachine = machineService.create(admin, command(assigned.getId(), assignedGroup.getId(), "BF-08410", MachineStatus.ACTIVE));
    machineService.create(admin, command(other.getId(), otherGroup.getId(), "PK-001", MachineStatus.ACTIVE));

    var result = machineService.list(viewer, null, null, null);

    assertThat(result.items()).extracting(machine -> machine.id()).containsExactly(assignedMachine.id());
  }

  // --- DW-120: search LIKE escape ---

  @Test
  @DisplayName("DW-120 underscore in search term matches literally instead of acting as wildcard")
  void searchUnderscoreMatchesLiterally() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    machineService.create(admin, command(plant.getId(), group.getId(), "BF_08410", MachineStatus.ACTIVE));

    var literal = machineService.list(admin, null, null, null, "BF_084", null);
    assertThat(literal.items()).extracting(m -> m.code()).containsExactly("BF_08410");

    // A % must not widen the match either.
    var percent = machineService.list(admin, null, null, null, "BF%", null);
    assertThat(percent.items()).isEmpty();

    // The escape character itself must stay literal (normalizeSearch doubles it first).
    var backslash = machineService.list(admin, null, null, null, "\\", null);
    assertThat(backslash.items()).isEmpty();

    // Name-clause coverage: the same escape applies to the machine.name predicate.
    machineService.create(admin, new MachineCommand(plant.getId(), group.getId(), "BF-9000", "pump_one",
        MachineStatus.ACTIVE, "Juki", LocalDate.parse("2026-05-27"), "Name-clause probe", List.of()));
    var byName = machineService.list(admin, null, null, null, "pump_one", null);
    assertThat(byName.items()).extracting(m -> m.name()).containsExactly("pump_one");
  }

  @Test
  @DisplayName("2.3-SVC-008 P0 MANAGER_MAINTENANCE cannot list out-of-scope plant machines")
  void manageCannotListOutOfScopePlantMachines() {
    var target = plant("GM1", "Plant GM1");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-out-of-scope-machine-list@syncro.dev");

    assertThatThrownBy(() -> machineService.list(user, target.getId(), null, null))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.3-SVC-009 P0 unassigned MANAGER_MAINTENANCE cannot list with out-of-scope machine group")
  void unassignedManageCannotListWithOutOfScopeMachineGroup() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-unassigned-machine-group-list@syncro.dev");

    assertThatThrownBy(() -> machineService.list(user, null, group.getId(), null))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.3-SVC-010 P0 MANAGER_MAINTENANCE cannot use out-of-scope machine group")
  void manageCannotUseOutOfScopeMachineGroup() {
    var assigned = plant("GM1", "Plant GM1");
    var other = plant("GM2", "Plant GM2");
    var otherGroup = group(other, "Packing");
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE, "manage-out-of-scope-machine-group@syncro.dev");
    assign(user, assigned);

    assertThatThrownBy(() -> machineService.create(user, command(assigned.getId(), otherGroup.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> machineService.list(user, assigned.getId(), otherGroup.getId(), null))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("2.3-SVC-011 P0 AUDITOR cannot mutate machines")
  void viewerCannotMutateMachines() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var viewer = persistedUser(ApplicationRole.AUDITOR, "viewer-mutates-machine@syncro.dev");
    assign(viewer, plant);

    assertThatThrownBy(() -> machineService.create(viewer, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE)))
        .isInstanceOf(MachineMutationForbiddenException.class);
  }

  @Test
  @DisplayName("2.3-SVC-012 P1 delete removes machine without dependents")
  void deleteRemovesMachineWithoutDependents() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));

    machineService.delete(admin, machine.id());

    assertThat(machines.findById(machine.id())).isEmpty();
  }

  @Test
  @DisplayName("2.3-SVC-013 P1 delete dependency conflict returns data integrity exception")
  void deleteDependencyConflictReturnsDataIntegrityException() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));
    jdbc.execute("""
        CREATE TABLE machine_delete_dependencies (
          id UUID PRIMARY KEY,
          machine_id UUID NOT NULL REFERENCES machines(id) ON DELETE RESTRICT
        )
        """);
    jdbc.update("INSERT INTO machine_delete_dependencies (id, machine_id) VALUES (?, ?)", UUID.randomUUID(), machine.id());

    assertThatThrownBy(() -> machineService.delete(admin, machine.id()))
        .isInstanceOf(MachineDataIntegrityException.class);
  }

  @Test
  @DisplayName("3.6-SVC-001 P1 create round-trips configured optional telemetry fields")
  void createRoundTripsConfiguredOptionalTelemetryFields() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE,
        List.of("vibration", "rpm")));

    assertThat(created.optionalTelemetryFields()).containsExactly("vibration", "rpm");
    assertThat(machines.findById(created.id()).orElseThrow().getOptionalTelemetryFields())
        .containsExactly("vibration", "rpm");
  }

  @Test
  @DisplayName("3.6-SVC-002 P1 update round-trips configured optional telemetry fields")
  void updateRoundTripsConfiguredOptionalTelemetryFields() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var machine = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE,
        List.of("vibration")));

    var updated = machineService.update(admin, machine.id(), command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("rpm", "heaterOn")));

    assertThat(updated.optionalTelemetryFields()).containsExactly("rpm", "heaterOn");
    assertThat(machines.findById(machine.id()).orElseThrow().getOptionalTelemetryFields())
        .containsExactly("rpm", "heaterOn");
  }

  @Test
  @DisplayName("3.6-SVC-003 P1 null optional telemetry fields round-trip as empty list")
  void nullOptionalTelemetryFieldsRoundTripAsEmptyList() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE));

    assertThat(created.optionalTelemetryFields()).isEmpty();
  }

  @Test
  @DisplayName("3.6-SVC-004 P0 duplicate names are deduped preserving first occurrence")
  void duplicateNamesAreDedupedPreservingFirstOccurrence() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE,
        List.of("vibration", "vibration", "rpm", "vibration")));

    assertThat(created.optionalTelemetryFields()).containsExactly("vibration", "rpm");
  }

  @Test
  @DisplayName("3.6-SVC-005 P0 config with more than 10 names is rejected")
  void configOverTenNamesIsRejected() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var overLimit = List.of("f01", "f02", "f03", "f04", "f05", "f06", "f07", "f08", "f09", "f10", "f11");

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, overLimit)))
        .isInstanceOf(MachineValidationException.class);
  }

  @Test
  @DisplayName("3.6-SVC-006 P0 reserved base contract name in config is rejected")
  void reservedNameInConfigIsRejected() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("counting"))))
        .isInstanceOfSatisfying(MachineValidationException.class, ex ->
            assertThat(ex.getFieldErrors())
                .containsEntry("optionalTelemetryFields", "'counting' is reserved by the base telemetry contract."));
  }

  @Test
  @DisplayName("DW-118 reserved-name check is case-insensitive (COUNTING rejected too)")
  void reservedNameCheckIsCaseInsensitive() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("COUNTING"))))
        .isInstanceOfSatisfying(MachineValidationException.class, ex ->
            assertThat(ex.getFieldErrors())
                .containsEntry("optionalTelemetryFields", "'COUNTING' is reserved by the base telemetry contract."));
  }

  @Test
  @DisplayName("DW-118 case-differing duplicates collapse to the first casing")
  void caseDifferingDuplicatesCollapse() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("Temp", "TEMP", "temp", "rpm")));

    assertThat(created.optionalTelemetryFields()).containsExactly("Temp", "rpm");
  }

  @Test
  @DisplayName("3.6-SVC-007 P0 name outside allowed pattern in config is rejected")
  void badNamePatternInConfigIsRejected() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("bad-name"))))
        .isInstanceOfSatisfying(MachineValidationException.class, ex ->
            assertThat(ex.getFieldErrors())
                .containsEntry("optionalTelemetryFields", "'bad-name' may only contain letters, numbers and underscores."));
  }

  @Test
  @DisplayName("DW-30 duplicates collapse before the over-count check")
  void duplicateEntriesCollapseBeforeOverCount() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    // 20 raw entries collapse to exactly 10 unique fields -> accepted
    var withDuplicates = new java.util.ArrayList<String>();
    for (int i = 1; i <= 10; i++) {
      withDuplicates.add(String.format("field%02d", i));
      withDuplicates.add(String.format("field%02d", i));
    }

    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, withDuplicates));

    assertThat(created.optionalTelemetryFields()).hasSize(10);
  }

  @Test
  @DisplayName("3.6-SVC-008 P1 blank and null config entries are dropped")
  void blankAndNullConfigEntriesAreDropped() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410", MachineStatus.ACTIVE,
        Arrays.asList("vibration", " ", null, "rpm", "")));

    assertThat(created.optionalTelemetryFields()).containsExactly("vibration", "rpm");
  }

  @Test
  @DisplayName("3.6-SVC-009 P0 underscore-prefixed name in config is rejected")
  void underscorePrefixedNameInConfigIsRejected() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("_vibration"))))
        .isInstanceOfSatisfying(MachineValidationException.class, ex ->
            assertThat(ex.getFieldErrors())
                .containsEntry("optionalTelemetryFields", "'_vibration' must not start with an underscore."));
  }

  // --- DW-30: field-aware validation errors ---

  @Test
  @DisplayName("DW-30 pattern rejection blames optionalTelemetryFields with reason")
  void patternRejectionBlamesConfigField() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, List.of("bad-name"))))
        .isInstanceOfSatisfying(MachineValidationException.class, ex ->
            assertThat(ex.getFieldErrors())
                .containsEntry("optionalTelemetryFields", "'bad-name' may only contain letters, numbers and underscores."));
  }

  @Test
  @DisplayName("DW-30 over-count rejection blames optionalTelemetryFields with limit")
  void overCountRejectionBlamesConfigField() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var overLimit = List.of("f01", "f02", "f03", "f04", "f05", "f06", "f07", "f08", "f09", "f10", "f11");

    assertThatThrownBy(() -> machineService.create(admin, command(plant.getId(), group.getId(), "BF-08410",
        MachineStatus.ACTIVE, overLimit)))
        .isInstanceOfSatisfying(MachineValidationException.class, ex ->
            assertThat(ex.getFieldErrors())
                .containsEntry("optionalTelemetryFields", "At most 10 optional telemetry fields are allowed."));
  }

  @Test
  @DisplayName("DW-30 multiple missing required command fields are collected together")
  void missingCommandFieldsAreCollected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> machineService.create(admin,
        new MachineCommand(null, null, null, "JBF19", MachineStatus.ACTIVE, "Juki",
            LocalDate.parse("2026-05-27"), "Pilot machine", List.of())))
        .isInstanceOfSatisfying(MachineValidationException.class, ex -> {
          assertThat(ex.getFieldErrors()).containsEntry("plantId", "Plant is required.");
          assertThat(ex.getFieldErrors()).containsEntry("machineGroupId", "Machine group is required.");
          assertThat(ex.getFieldErrors()).doesNotContainKey("status");
        });
  }

  // --- DW-32: update semantics for optionalTelemetryFields ---

  @Test
  @DisplayName("DW-32 absent optionalTelemetryFields on update preserves the stored config")
  void updateWithAbsentFieldPreservesStoredConfig() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    machineService.create(admin, command(plant.getId(), group.getId(), "BF_0001", MachineStatus.ACTIVE,
        List.of("vibration", "rpm")));

    // JSON key omitted -> null in the bound command
    var updated = machineService.update(admin, machineByCode("BF_0001"),
        new MachineCommand(plant.getId(), group.getId(), "BF_0001", "Renamed", MachineStatus.ACTIVE,
            "Juki", LocalDate.parse("2026-05-27"), "notes", null));

    assertThat(updated.optionalTelemetryFields()).containsExactly("vibration", "rpm");
  }

  @Test
  @DisplayName("DW-32 explicit empty array clears the stored config")
  void updateWithEmptyArrayClearsStoredConfig() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF_0002", MachineStatus.ACTIVE,
        List.of("vibration")));

    var updated = machineService.update(admin, created.id(),
        new MachineCommand(plant.getId(), group.getId(), "BF_0002", "BF_0002", MachineStatus.ACTIVE,
            "Juki", LocalDate.parse("2026-05-27"), "notes", List.of()));

    assertThat(updated.optionalTelemetryFields()).isEmpty();
  }

  @Test
  @DisplayName("DW-32 populated list replaces the stored config")
  void updateWithPopulatedListReplacesStoredConfig() {
    var plant = plant("GM1", "Plant GM1");
    var group = group(plant, "Forming");
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var created = machineService.create(admin, command(plant.getId(), group.getId(), "BF_0003", MachineStatus.ACTIVE,
        List.of("vibration")));

    var updated = machineService.update(admin, created.id(),
        new MachineCommand(plant.getId(), group.getId(), "BF_0003", "BF_0003", MachineStatus.ACTIVE,
            "Juki", LocalDate.parse("2026-05-27"), "notes", List.of("temperature", "pressure")));

    assertThat(updated.optionalTelemetryFields()).containsExactly("temperature", "pressure");
  }

  private UUID machineByCode(String code) {
    return machines.findAll().stream()
        .filter(m -> code.equalsIgnoreCase(m.getCode()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("machine not found: " + code))
        .getId();
  }

  private MachineCommand command(UUID plantId, UUID groupId, String code, MachineStatus status) {
    return new MachineCommand(plantId, groupId, code, "JBF19", status, "Juki", LocalDate.parse("2026-05-27"),
        "Pilot machine", List.of());
  }

  private MachineCommand command(UUID plantId, UUID groupId, String code, MachineStatus status,
      List<String> optionalTelemetryFields) {
    return new MachineCommand(plantId, groupId, code, "JBF19", status, "Juki", LocalDate.parse("2026-05-27"),
        "Pilot machine", optionalTelemetryFields);
  }

  private PlantEntity plant(String code, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), code, name, now, now));
  }

  private MachineGroupEntity group(PlantEntity plant, String name) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    return machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, name, now, now));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-27T00:00:00Z")));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
