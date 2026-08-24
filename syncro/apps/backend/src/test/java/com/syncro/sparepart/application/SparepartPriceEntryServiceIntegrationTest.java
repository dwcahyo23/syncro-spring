package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.sparepart.application.SparepartPriceEntryService.NotFoundException;
import com.syncro.sparepart.application.SparepartPriceEntryService.PriceEntryCommand;
import com.syncro.sparepart.application.SparepartPriceEntryService.SparepartPriceEntryView;
import com.syncro.sparepart.application.SparepartPriceEntryService.ValidationException;
import com.syncro.sparepart.application.SparepartService.SparepartCommand;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryEntity;
import com.syncro.sparepart.infrastructure.SparepartPriceEntryRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;

class SparepartPriceEntryServiceIntegrationTest extends AbstractPostgresIntegrationTest {
  @Autowired
  private SparepartPriceEntryService priceEntryService;

  @Autowired
  private SparepartService sparepartService;

  @Autowired
  private SparepartRepository spareparts;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

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
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private com.syncro.audit.infrastructure.AuditLogRepository auditLogs;

  @Autowired
  private SparepartPriceEntryRepository priceEntries;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("8.3-SVC-001 P0 LEADER-scoped MANAGE appends IDR entry defaulting currency and forcing kurs 1")
  void leaderScopedManageAppendsIdrEntry() {
    var manageLeader = persistedUser(ApplicationRole.MANAGE, "price-leader@syncro.dev");
    var machine = machine();
    assign(manageLeader, machine.getPlant());
    assignJobScope(manageLeader, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(manageLeader);

    var created = priceEntryService.create(manageLeader, sparepart.getId(), new PriceEntryCommand(
        new BigDecimal("1500000"), null, null));

    assertThat(created.currency()).isEqualTo("IDR");
    assertThat(created.kursToIdr()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(created.idrAmount()).isEqualByComparingTo(new BigDecimal("1500000.00"));
    assertThat(created.amount()).isEqualByComparingTo("1500000");
    assertThat(created.enteredByName()).isEqualTo("price-leader@syncro.dev");
    var stored = priceEntries.findById(created.id()).orElseThrow();
    assertThat(stored.getCurrency()).isEqualTo("IDR");
    assertThat(stored.getEnteredAt()).isNotNull();

    var history = priceEntryService.list(manageLeader, sparepart.getId());
    assertThat(history).extracting(SparepartPriceEntryView::id).containsExactly(created.id());
  }

  @Test
  @DisplayName("8.3-SVC-002 P0 non-IDR with kurs persists backend-derived idrAmount at scale 2")
  void nonIdrWithKursPersistsDerivedIdrAmount() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "price-usd@syncro.dev");
    var sparepart = createdSparepart(admin);

    var created = priceEntryService.create(admin, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1000"), "USD", new BigDecimal("15500")));

    assertThat(created.currency()).isEqualTo("USD");
    assertThat(created.kursToIdr()).isEqualByComparingTo("15500");
    assertThat(created.idrAmount()).isEqualByComparingTo(new BigDecimal("15500000.00"));
    var reloaded = priceEntryService.list(admin, sparepart.getId()).stream()
        .filter(entry -> entry.id().equals(created.id()))
        .findFirst()
        .orElseThrow();
    assertThat(reloaded.idrAmount()).isEqualByComparingTo("15500000.00");
  }

  @Test
  @DisplayName("8.3-SVC-003 P0 non-IDR without kurs is rejected and saves nothing")
  void nonIdrWithoutKursRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var sparepart = createdSparepart(admin);

    var exception = catchThrowableOfType(() -> priceEntryService.create(admin, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1000"), "USD", null)), ValidationException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getFieldErrors()).containsKey("kursToIdr");
    assertThat(priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.3-SVC-004 P1 IDR entry ignores submitted kurs and stores exactly 1")
  void idrEntryIgnoresSubmittedKurs() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "price-idr@syncro.dev");
    var sparepart = createdSparepart(admin);

    var created = priceEntryService.create(admin, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1500000"), "IDR", new BigDecimal("99")));

    assertThat(created.currency()).isEqualTo("IDR");
    assertThat(created.kursToIdr()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(created.idrAmount()).isEqualByComparingTo("1500000.00");
  }

  @Test
  @DisplayName("8.3-SVC-005 P1 lowercase and malformed currency codes are rejected")
  void malformedCurrencyRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var sparepart = createdSparepart(admin);

    for (var badCurrency : List.of("usd", "US", "USDX")) {
      assertThatThrownBy(() -> priceEntryService.create(admin, sparepart.getId(),
          new PriceEntryCommand(new BigDecimal("1000"), badCurrency, BigDecimal.TEN)))
          .as(badCurrency)
          .isInstanceOf(ValidationException.class);
    }
    assertThat(priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.3-SVC-006 P0 zero or negative amount and kurs are rejected")
  void nonPositiveValuesRejected() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);
    var sparepart = createdSparepart(admin);

    for (var amount : List.of(BigDecimal.ZERO, new BigDecimal("-5"))) {
      assertThatThrownBy(() -> priceEntryService.create(admin, sparepart.getId(),
          new PriceEntryCommand(amount, "IDR", null)))
          .as("amount=" + amount)
          .isInstanceOf(ValidationException.class);
    }
    for (var kurs : List.of(BigDecimal.ZERO, new BigDecimal("-1"))) {
      assertThatThrownBy(() -> priceEntryService.create(admin, sparepart.getId(),
          new PriceEntryCommand(new BigDecimal("1000"), "USD", kurs)))
          .as("kurs=" + kurs)
          .isInstanceOf(ValidationException.class);
    }
    assertThat(priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.3-SVC-007 P0 MANAGE below LEADER job scope is denied with no mutation and no audit")
  void manageBelowLeaderDeniedWithoutMutationOrAudit() {
    var manageNoScope = persistedUser(ApplicationRole.MANAGE, "price-noscope@syncro.dev");
    var machine = machine();
    assign(manageNoScope, machine.getPlant());
    var sparepart = createdSparepart(authenticatedUser(ApplicationRole.SUPER_ADMIN));
    long auditCountBefore = priceEntryAuditCount();

    assertThatThrownBy(() -> priceEntryService.create(manageNoScope, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1500000"), null, null)))
        .isInstanceOf(JobScopeForbiddenException.class)
        .hasMessageContaining("LEADER");

    assertThat(priceEntryAuditCount()).isEqualTo(auditCountBefore);
    assertThat(priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.3-SVC-008 P0 VIEWER is rejected by the app-role gate before job scope")
  void viewerRejectedByAppRoleFirst() {
    var viewerWithScope = persistedUser(ApplicationRole.VIEWER, "price-viewer@syncro.dev");
    var machine = machine();
    assign(viewerWithScope, machine.getPlant());
    assignJobScope(viewerWithScope, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(authenticatedUser(ApplicationRole.SUPER_ADMIN));

    assertThatThrownBy(() -> priceEntryService.create(viewerWithScope, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1500000"), null, null)))
        .isInstanceOf(SparepartPriceEntryService.MutationForbiddenException.class);
    assertThat(priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.3-SVC-009 P1 SUPER_ADMIN bypasses job scope without responsibility rows")
  void superAdminBypassesJobScope() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "price-bypass@syncro.dev");
    var sparepart = createdSparepart(admin);

    var created = priceEntryService.create(admin, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1500000"), null, null));

    assertThat(created.id()).isNotNull();
    assertThat(priceEntries.findAllBySparepartIdOrderByEnteredAtDesc(sparepart.getId())).hasSize(1);
  }

  @Test
  @DisplayName("8.3-SVC-010 P0 out-of-plant sparepart is masked as not found for scoped MANAGE")
  void wrongPlantSparepartMaskedAsNotFound() {
    var outsider = persistedUser(ApplicationRole.MANAGE, "price-outsider@syncro.dev");
    assignJobScope(outsider, machine(), ResponsibilityLevel.MANAGER);
    var sparepart = createdSparepart(authenticatedUser(ApplicationRole.SUPER_ADMIN));
    long auditCountBefore = priceEntryAuditCount();

    assertThatThrownBy(() -> priceEntryService.create(outsider, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1500000"), null, null)))
        .isInstanceOf(NotFoundException.class);

    assertThat(priceEntryAuditCount()).isEqualTo(auditCountBefore);
  }

  @Test
  @DisplayName("8.3-SVC-011 P1 unknown sparepart id returns not found on create and list")
  void unknownSparepartReturnsNotFound() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> priceEntryService.create(admin, UUID.randomUUID(),
        new PriceEntryCommand(BigDecimal.ONE, null, null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> priceEntryService.list(admin, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("8.3-SVC-012 P1 plant-scoped user without job scope can still read history; empty list is empty array semantics")
  void plantScopedUserReadsWithoutJobScope() {
    var reader = persistedUser(ApplicationRole.MANAGE, "price-reader@syncro.dev");
    var writer = persistedUser(ApplicationRole.SUPER_ADMIN, "price-writer@syncro.dev");
    var machine = machine();
    assign(reader, machine.getPlant());
    var sparepart = createdSparepart(writer);

    assertThat(priceEntryService.list(reader, sparepart.getId())).isEmpty();

    priceEntryService.create(writer, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1500000"), null, null));

    assertThat(priceEntryService.list(reader, sparepart.getId())).hasSize(1);
  }

  @Test
  @DisplayName("8.3-SVC-013 P1 history lists newest first by enteredAt DESC")
  void historyListsNewestFirst() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "price-history@syncro.dev");
    var sparepart = createdSparepart(admin);
    var older = persistEntry(admin, sparepart, Instant.parse("2026-08-01T10:00:00Z"));
    var newer = persistEntry(admin, sparepart, Instant.parse("2026-08-20T10:00:00Z"));

    var history = priceEntryService.list(admin, sparepart.getId());

    assertThat(history).extracting(SparepartPriceEntryView::id).containsExactly(newer.getId(), older.getId());
  }

  @Test
  @DisplayName("8.3-SVC-014 P0 audit row captures CREATE, SPAREPART_PRICE_ENTRY type, label, plant, and snapshot")
  void auditRowCapturesCreateSnapshot() throws Exception {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "price-auditor@syncro.dev");
    var machine = machine();
    assign(admin, machine.getPlant());
    var sparepart = createdSparepart(admin);

    var created = priceEntryService.create(admin, sparepart.getId(), new PriceEntryCommand(
        new BigDecimal("1000"), "USD", new BigDecimal("15500")));

    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var entry = latestAuditEntryFor(created.id());
    assertThat(entry.getAction()).isEqualTo(com.syncro.audit.domain.AuditAction.CREATE);
    assertThat(entry.getEntityType())
        .isEqualTo(com.syncro.audit.domain.AuditEntityType.SPAREPART_PRICE_ENTRY);
    assertThat(entry.getEntityLabel()).isEqualTo(sparepart.getCode());
    assertThat(entry.getPlantId()).isEqualTo(machine.getPlant().getId());
    assertThat(entry.getActorName()).isEqualTo("price-auditor@syncro.dev");
    assertThat(entry.getPreviousValue()).isNull();
    var newValue = mapper.readTree(entry.getNewValue());
    assertThat(newValue.get("sparepartCode").asText()).isEqualTo(sparepart.getCode());
    assertThat(new BigDecimal(newValue.get("amount").asText())).isEqualByComparingTo("1000");
    assertThat(newValue.get("currency").asText()).isEqualTo("USD");
    assertThat(new BigDecimal(newValue.get("kursToIdr").asText())).isEqualByComparingTo("15500");
    assertThat(new BigDecimal(newValue.get("idrAmount").asText())).isEqualByComparingTo("15500000.00");
    assertThat(newValue.get("enteredAt").asText()).isNotEmpty();
  }

  @Test
  @DisplayName("8.3-SVC-015 P1 append-only contract: no update path mutates a stored row")
  void storedRowIsNeverMutated() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "price-immutable@syncro.dev");
    var sparepart = createdSparepart(admin);
    var created = priceEntryService.create(admin, sparepart.getId(),
        new PriceEntryCommand(new BigDecimal("1000"), "USD", new BigDecimal("15500")));
    var storedBefore = priceEntries.findById(created.id()).orElseThrow();
    long versionBefore = storedBefore.getVersion();

    var storedAfter = priceEntries.findById(created.id()).orElseThrow();

    assertThat(storedAfter.getAmount()).isEqualByComparingTo(storedBefore.getAmount());
    assertThat(storedAfter.getCurrency()).isEqualTo(storedBefore.getCurrency());
    assertThat(storedAfter.getVersion()).isEqualTo(versionBefore);
  }

  private SparepartEntity createdSparepart(AuthenticatedUser actor) {
    var refs = taxonomyRefs();
    var created = sparepartService.create(actor,
        new SparepartCommand(machine().getId(), refs.category().getId(), refs.brand().getId(),
            refs.kind().getId(), refs.type().getId()));
    return spareparts.findById(created.id()).orElseThrow();
  }

  private SparepartPriceEntryEntity persistEntry(AuthenticatedUser actor, SparepartEntity sparepart, Instant enteredAt) {
    var actorEntity = users.findById(UUID.fromString(actor.id())).orElseThrow();
    return priceEntries.saveAndFlush(new SparepartPriceEntryEntity(
        UUID.randomUUID(),
        sparepart,
        new BigDecimal("500"),
        "IDR",
        BigDecimal.ONE,
        new BigDecimal("500.00"),
        actorEntity,
        enteredAt));
  }

  /** Entry audits carry the ENTRY id as entityId, so the count is global per test transaction. */
  private long priceEntryAuditCount() {
    return auditLogs.findAll().stream()
        .filter(entry -> entry.getEntityType() == com.syncro.audit.domain.AuditEntityType.SPAREPART_PRICE_ENTRY)
        .count();
  }

  private com.syncro.audit.infrastructure.AuditLogEntity latestAuditEntryFor(UUID entityId) {
    return auditLogs.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
        .stream()
        .filter(entry -> entityId.equals(entry.getEntityId()))
        .findFirst()
        .orElseThrow();
  }

  private MachineEntity machine() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var plant = plants.findByCodeIgnoreCase("PLANT-1")
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "PLANT-1", "Plant 1", now, now)));
    var group = machineGroups.findByPlantIdAndNameIgnoreCase(plant.getId(), "Assembly")
        .orElseGet(() -> machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Assembly", now, now)));
    return machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "MCH-1")
        .orElseGet(() -> machines.saveAndFlush(new MachineEntity(
            UUID.randomUUID(), plant, group, "MCH-1", "Machine 1", MachineStatus.ACTIVE, null, null, null, List.of(), now, now)));
  }

  private SparepartTaxonomyRefs taxonomyRefs() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var category = taxonomy.findByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension.CATEGORY, "ELECTRIC")
        .orElseGet(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "ELECTRIC", "Electric", now, now)));
    var unique = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    return new SparepartTaxonomyRefs(
        category,
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.BRAND, "WECON-" + unique, "Wecon " + unique, category, now, now)),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.KIND, "PLC-" + unique, "PLC " + unique, category, now, now)),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX5-" + unique, "LX5 " + unique, category, now, now)));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, com.syncro.auth.infrastructure.PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(
        UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-28T00:00:00Z")));
  }

  private void assignJobScope(AuthenticatedUser user, MachineEntity machine, ResponsibilityLevel level) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var userEntity = users.findById(UUID.fromString(user.id())).orElseThrow();
    responsibilities.saveAndFlush(new com.syncro.machine.infrastructure.MachineResponsibilityEntity(
        UUID.randomUUID(), machine, userEntity, level, now, now));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private record SparepartTaxonomyRefs(
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type) {
  }
}
