package com.syncro.sparepart.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JobScopeService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.common.LikePattern;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.projection.application.ProjectionCacheEvictionEvent;
import com.syncro.sparepart.domain.BomReviewStatus;
import com.syncro.sparepart.domain.SparepartDerivation;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartService {
  private static final int MAX_PAGE_SIZE = 200;
  private static final String DUPLICATE_CODE_CONSTRAINT = "uq_spareparts_lower_code";
  private static final String DUPLICATE_IDENTITY_CONSTRAINT = "uq_spareparts_machine_taxonomy_identity";
  /** Story 18-1: BOM master uniqueness (partial indexes from V1). */
  private static final String DUPLICATE_HIERARCHY_KEY_CONSTRAINT = "uq_spareparts_hierarchy_identity_key";
  private static final String DUPLICATE_BOM_CODE_CONSTRAINT = "uq_spareparts_bom_code";
  private static final String DUPLICATE_BOM_SERIAL_CONSTRAINT = "uq_spareparts_machine_cat_kind_serial";
  private static final String DUPLICATE_MATERIAL_CODE_CONSTRAINT = "uq_spareparts_material_code";
  /** V61 (story 12-4) adds a case-sensitive UNIQUE backing the stock FK; duplicate codes can surface under either name. */
  private static final String DUPLICATE_MATERIAL_CODE_KEY_CONSTRAINT = "uq_spareparts_material_code_key";
  private static final String PROCUREMENT_JOB_SCOPE_LEVEL = "LEADER";
  private static final int MAX_MATERIAL_CODE_LENGTH = 64;

  private final SparepartRepository spareparts;
  private final SparepartTaxonomyRepository taxonomy;
  private final MachineRepository machines;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final JobScopeService jobScopes;
  private final ApplicationEventPublisher events;

  public SparepartService(SparepartRepository spareparts, SparepartTaxonomyRepository taxonomy, MachineRepository machines,
      AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog, Clock clock, JobScopeService jobScopes,
      ApplicationEventPublisher events) {
    this.spareparts = spareparts;
    this.taxonomy = taxonomy;
    this.machines = machines;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
    this.jobScopes = jobScopes;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public SparepartListView list(AuthenticatedUser user, SparepartFilters filters, Pageable pageable) {
    validatePageable(pageable);
    var search = normalizeSearch(filters.search());
    var machineCode = normalizeSearch(filters.machineCode());
    var result = spareparts.search(filters.categoryId(), filters.brandId(), filters.kindId(), filters.typeId(), filters.machineId(), search, machineCode, filters.reviewStatus(), pageable);
    return new SparepartListView(result.stream().map(this::toView).toList(), result.getTotalElements(), pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().toString());
  }

  @Transactional(readOnly = true)
  public SparepartView get(AuthenticatedUser user, UUID sparepartId) {
    return toView(find(sparepartId));
  }

  @Transactional
  public SparepartView create(AuthenticatedUser user, SparepartCommand command) {
    requireMutationRole(user);
    var normalized = normalize(command);
    var now = Instant.now(clock);
    var machine = resolveMachine(user, normalized.machineId());
    var taxonomies = resolveTaxonomies(normalized);
    rejectDuplicateIdentity(machine, taxonomies);
    var entity = newSparepartWithBomIdentity(UUID.randomUUID(), machine, taxonomies, sparepartLabel(taxonomies),
        hierarchyIdentityKey(machine, taxonomies), now);
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART, saved.getId(), saved.getCode(),
        machine.getPlant().getId(), null, SparepartAuditValues.of(saved), null));
    return toView(saved);
  }

  @Transactional
  public SparepartView update(AuthenticatedUser user, UUID sparepartId, SparepartCommand command) {
    requireMutationRole(user);
    // Locked read: a concurrent approve/reject must not interleave with a prefix-changing
    // update (lost update — reopenForReview would overwrite a just-committed ACTIVE/REJECTED).
    var sparepart = findForUpdate(sparepartId);
    var entityLabel = sparepart.getCode();
    var previous = SparepartAuditValues.of(sparepart);
    var normalized = normalize(command);
    var machine = resolveMachine(user, normalized.machineId());
    var taxonomies = resolveTaxonomies(normalized);
    rejectDuplicateIdentity(sparepartId, machine, taxonomies);
    var now = Instant.now(clock);
    var identity = bomIdentityForUpdate(sparepart, machine, taxonomies);
    sparepart.update(
        identity.code(),
        sparepartLabel(taxonomies),
        machine,
        taxonomies.category(),
        taxonomies.brand(),
        taxonomies.kind(),
        taxonomies.type(),
        now);
    sparepart.updateBomIdentity(hierarchyIdentityKey(machine, taxonomies),
        identity.serial(), identity.code(), identity.version(), now);
    if (identity.requeue()) {
      sparepart.reopenForReview(now);
    }
    var saved = save(sparepart);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART, sparepartId, entityLabel,
        machine.getPlant().getId(), previous, SparepartAuditValues.of(saved), null));
    return toView(saved);
  }

  /**
   * Story 18-1: PENDING_REVIEW → ACTIVE. Terminal for this story (no re-open path); a target
   * in any other review status fails with {@link SparepartReviewTransitionException} (409).
   * The row is locked FOR UPDATE so concurrent reviews serialize on the precondition check
   * (no {@code @Version} column exists on spareparts).
   */
  @Transactional
  public SparepartView approve(AuthenticatedUser user, UUID sparepartId) {
    requireMutationRole(user);
    var sparepart = findForUpdate(sparepartId);
    requireSparepartPlantAccess(user, sparepart);
    if (sparepart.getReviewStatus() != BomReviewStatus.PENDING_REVIEW) {
      throw new SparepartReviewTransitionException();
    }
    var entityLabel = sparepart.getCode();
    var previous = SparepartAuditValues.of(sparepart);
    sparepart.approve(Instant.now(clock));
    var saved = save(sparepart);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART, sparepartId, entityLabel,
        saved.getMachine().getPlant().getId(), previous, SparepartAuditValues.of(saved), null));
    return toView(saved);
  }

  /**
   * Story 18-1: PENDING_REVIEW → REJECTED with a required reason. Terminal for this story;
   * a blank reason fails validation (400) and a non-PENDING_REVIEW target fails with 409.
   * Locked FOR UPDATE like {@link #approve}.
   */
  @Transactional
  public SparepartView reject(AuthenticatedUser user, UUID sparepartId, String rejectionReason) {
    requireMutationRole(user);
    var reason = rejectionReason == null ? "" : rejectionReason.trim();
    if (reason.isEmpty()) {
      throw new SparepartValidationException();
    }
    var sparepart = findForUpdate(sparepartId);
    requireSparepartPlantAccess(user, sparepart);
    if (sparepart.getReviewStatus() != BomReviewStatus.PENDING_REVIEW) {
      throw new SparepartReviewTransitionException();
    }
    var entityLabel = sparepart.getCode();
    var previous = SparepartAuditValues.of(sparepart);
    sparepart.reject(reason, Instant.now(clock));
    var saved = save(sparepart);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART, sparepartId, entityLabel,
        saved.getMachine().getPlant().getId(), previous, SparepartAuditValues.of(saved), null));
    return toView(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID sparepartId) {
    requireMutationRole(user);
    var sparepart = find(sparepartId);
    var entityLabel = sparepart.getCode();
    var previous = SparepartAuditValues.of(sparepart);
    try {
      spareparts.delete(sparepart);
      spareparts.flush();
      auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.SPAREPART, sparepartId, entityLabel,
          sparepart.getMachine().getPlant().getId(), previous, null, null));
    } catch (DataIntegrityViolationException exception) {
      throw new SparepartDataIntegrityException();
    }
  }

  /**
   * Replaces only the procurement-readiness subset (material code, lead time) — Story 8-2.
   * Nulls clear values. Enforces the app-role gate, then the LEADER-or-above job scope
   * (SUPER_ADMIN bypasses), then plant access, then global material-code uniqueness.
   */
  @Transactional
  public SparepartView patchProcurement(AuthenticatedUser user, UUID sparepartId, SparepartProcurementCommand command) {
    return patchProcurement(user, sparepartId, command, false);
  }

  /**
   * Story 12-4: completion-path overload of {@link #patchProcurement}. When
   * {@code completingRequest} is true the MANAGER-only app-role gate is relaxed to
   * accept INVENTORY_MAINTENANCE/STOREKEEPER (FR-144 requires inventory/stores to
   * complete a new-item request); the LEADER job-scope check is skipped for the same
   * reason (inventory/stores users typically hold no machine responsibility). Plant
   * access still applies. The completion endpoint itself is the OPA-enforced surface.
   */
  @Transactional
  public SparepartView patchProcurement(AuthenticatedUser user, UUID sparepartId,
      SparepartProcurementCommand command, boolean completingRequest) {
    requireMutationRole(user, completingRequest);
    if (!completingRequest) {
      jobScopes.requireLevelOrAbove(user, PROCUREMENT_JOB_SCOPE_LEVEL);
    }
    var sparepart = find(sparepartId);
    requireSparepartPlantAccess(user, sparepart);
    var materialCode = normalizeMaterialCode(command.materialCode());
    var leadTimeHours = normalizeLeadTimeHours(command.leadTimeHours());
    // No-op (nothing changed): return without writing audit or bumping updatedAt, so
    // immutable audit history does not accumulate noise from repeated identical PATCHes.
    if (java.util.Objects.equals(sparepart.getMaterialCode(), materialCode)
        && java.util.Objects.equals(sparepart.getLeadTimeHours(), leadTimeHours)) {
      return toView(sparepart);
    }
    var entityLabel = sparepart.getCode();
    var previous = SparepartAuditValues.of(sparepart);
    rejectDuplicateMaterialCode(sparepartId, materialCode);
    sparepart.updateProcurement(materialCode, leadTimeHours, Instant.now(clock));
    var saved = save(sparepart);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.SPAREPART, sparepartId, entityLabel,
        saved.getMachine().getPlant().getId(), previous, SparepartAuditValues.of(saved), null));
    events.publishEvent(ProjectionCacheEvictionEvent.all());
    return toView(saved);
  }

  /**
   * Story 12-4: creates a bare (minimal) sparepart record for the PENDING_COMPLETION
   * completion flow when no sparepart yet exists with the given material code.
   * Uses the ELECTRIC category and creates placeholder brand/kind/type taxonomy entries
   * if they do not yet exist — the full taxonomy is out of scope for this story;
   * a later masterdata story can enrich it.
   */
  @Transactional
  public SparepartView createForCompletion(AuthenticatedUser user, UUID machineId, String materialCode) {
    var machine = resolveMachine(user, machineId);
    var electricCat = taxonomy.findByDimensionAndCodeIgnoreCase(SparepartTaxonomyDimension.CATEGORY, "ELECTRIC")
        .orElseThrow(() -> new SparepartTaxonomyReferenceNotFoundException());
    var brand = ensureTaxonomy(SparepartTaxonomyDimension.BRAND, "GENERIC", "Generic", electricCat);
    var kind = ensureTaxonomy(SparepartTaxonomyDimension.KIND, "GENERIC", "Generic", electricCat);
    var type = ensureTaxonomy(SparepartTaxonomyDimension.TYPE, "GENERIC", "Generic", electricCat);

    var now = Instant.now(clock);
    var name = "Part " + materialCode;
    // Completion-path placeholder taxonomy: no hierarchy identity key (see
    // newSparepartWithBomIdentity) — two completions on one machine must not collide.
    var entity = newSparepartWithBomIdentity(UUID.randomUUID(), machine,
        new TaxonomyRefs(electricCat, brand, kind, type), name, null, now);
    // Set the material code on the freshly created sparepart
    entity.updateProcurement(materialCode, null, now);
    var saved = save(entity);

    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART,
        saved.getId(), saved.getCode(), machine.getPlant().getId(), null,
        SparepartAuditValues.of(saved), null));
    return toView(saved);
  }

  /**
   * DW-148: creates a sparepart with full taxonomy during the sparepart-request flow.
   * Role gate is relaxed (the request's own authorization gates the actor). The
   * sparepart entity is returned so the request service can reference it.
   */
  public SparepartEntity createForRequestEntity(AuthenticatedUser user, UUID machineId,
      String materialCode, UUID categoryId, UUID brandId, UUID kindId, UUID typeId) {
    var machine = resolveMachine(user, machineId);
    var taxonomies = resolveTaxonomies(new SparepartCommand(machineId, categoryId, brandId, kindId, typeId));
    rejectDuplicateIdentity(machine, taxonomies);
    var name = sparepartLabel(taxonomies);
    var now = Instant.now(clock);
    var entity = newSparepartWithBomIdentity(UUID.randomUUID(), machine, taxonomies, name,
        hierarchyIdentityKey(machine, taxonomies), now);
    if (materialCode != null && !materialCode.isBlank()) {
      entity.updateProcurement(materialCode, null, now);
    }
    var saved = save(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.SPAREPART,
        saved.getId(), saved.getCode(), machine.getPlant().getId(), null,
        SparepartAuditValues.of(saved), null));
    events.publishEvent(ProjectionCacheEvictionEvent.all());
    return saved;
  }

  /** Finds a taxonomy entry by dimension and code, or creates it if missing. */
  private SparepartTaxonomyEntity ensureTaxonomy(SparepartTaxonomyDimension dimension, String code,
      String name, SparepartTaxonomyEntity category) {
    return taxonomy.findByDimensionAndCodeIgnoreCase(dimension, code)
        .orElseGet(() -> {
          var now = Instant.now(clock);
          var entity = new SparepartTaxonomyEntity(UUID.randomUUID(), dimension, code, name, category, now, now);
          return taxonomy.saveAndFlush(entity);
        });
  }

  private SparepartEntity find(UUID sparepartId) {
    return spareparts.findById(sparepartId).orElseThrow(SparepartNotFoundException::new);
  }

  /** Locked read for review transitions — serializes concurrent approve/reject (Story 18-1). */
  private SparepartEntity findForUpdate(UUID sparepartId) {
    return spareparts.findByIdForUpdate(sparepartId).orElseThrow(SparepartNotFoundException::new);
  }

  private void requireMutationRole(AuthenticatedUser user) {
    requireMutationRole(user, false);
  }

  /**
   * Story 12-4 gate relaxation: the completion path (FR-144) also admits
   * INVENTORY_MAINTENANCE/STOREKEEPER. All other mutations stay
   * SUPER_ADMIN/MANAGER_MAINTENANCE-only.
   */
  private void requireMutationRole(AuthenticatedUser user, boolean completingRequest) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN
        || user.applicationRole() == ApplicationRole.MANAGER_MAINTENANCE) {
      return;
    }
    if (completingRequest && (user.applicationRole() == ApplicationRole.INVENTORY_MAINTENANCE
        || user.applicationRole() == ApplicationRole.STOREKEEPER)) {
      return;
    }
    throw new SparepartMutationForbiddenException();
  }

  private SparepartCommand normalize(SparepartCommand command) {
    return new SparepartCommand(
        requiredId(command.machineId()),
        requiredId(command.categoryId()),
        requiredId(command.brandId()),
        requiredId(command.kindId()),
        requiredId(command.typeId()));
  }

  private String normalizeSearch(String search) {
    if (search == null) {
      return "";
    }
    var trimmed = search.trim().toLowerCase(Locale.ROOT);
    return trimmed.isEmpty() ? "" : escapeLikePattern(trimmed);
  }

  private String escapeLikePattern(String search) {
    // DW-123: shared helper — backslash-first ordering is load-bearing.
    return LikePattern.escape(search);
  }

  private void validatePageable(Pageable pageable) {
    if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1 || pageable.getPageSize() > MAX_PAGE_SIZE) {
      throw new SparepartValidationException();
    }
  }

  /**
   * Story 18-1: builds a fresh BOM master identity for a new sparepart — PENDING_REVIEW status
   * (entity constructor default), derived hierarchy key, allocated serial, code = prefix+serial,
   * version 1. {@code code} and {@code bom_code} coincide by design (design note: the operator
   * identifier and the BOM code carry the same value).
   *
   * <p>{@code hierarchyIdentityKey} may be {@code null}: the request-completion path creates
   * placeholder-taxonomy (ELECTRIC/GENERIC/GENERIC/GENERIC) spareparts whose identity is not a
   * real BOM hierarchy, and a non-null key would collide on
   * {@code uq_spareparts_hierarchy_identity_key} for every completion on the same machine. The
   * partial unique index skips nulls, so null keys coexist; bom_serial/bom_code stay unique via
   * the serial allocation.
   */
  private SparepartEntity newSparepartWithBomIdentity(UUID id, MachineEntity machine, TaxonomyRefs taxonomies,
      String name, String hierarchyIdentityKey, Instant now) {
    var prefix = bomPrefix(machine, taxonomies);
    var serial = nextBomSerial(machine, taxonomies, prefix);
    var code = prefix + serial;
    var entity = new SparepartEntity(id, code, name, machine,
        taxonomies.category(), taxonomies.brand(), taxonomies.kind(), taxonomies.type(), now, now);
    entity.updateBomIdentity(hierarchyIdentityKey, serial, code, 1, now);
    return entity;
  }

  /**
   * Story 18-1: re-derives the BOM identity on update. Stable prefix keeps serial/code/version
   * and leaves the review status untouched; a changed prefix (machine/category/kind/brand)
   * allocates a fresh serial under the new prefix, bumps {@code bom_code_version}, and re-queues
   * the sparepart for review ({@code requeue}). Legacy rows (null BOM fields, e.g. the pilot
   * seed) are back-filled from the existing code without a version bump when the prefix is
   * stable — but only when the code tail is a well-formed 3-digit serial; a malformed tail gets
   * a fresh serial instead of a corrupt one.
   */
  private record BomIdentity(String code, String serial, int version, boolean requeue) {
  }

  private BomIdentity bomIdentityForUpdate(SparepartEntity sparepart, MachineEntity machine, TaxonomyRefs taxonomies) {
    var prefix = bomPrefix(machine, taxonomies);
    var currentVersion = sparepart.getBomCodeVersion() == null ? 1 : sparepart.getBomCodeVersion();
    // Case-insensitive: the code series scan (findCodesByEscapedPrefix) matches upper(code),
    // so a stored code whose casing differs from the derived prefix must still count as
    // stable — otherwise it would fake a prefix change (re-queue + version bump + re-allocation).
    if (sparepart.getCode().regionMatches(true, 0, prefix, 0, prefix.length())) {
      var tail = sparepart.getCode().substring(prefix.length());
      var serial = sparepart.getBomSerial() != null ? sparepart.getBomSerial() : tail;
      if (sparepart.getBomSerial() == null && parseSeries(tail) < 0) {
        // Legacy code with a non-numeric/short tail: back-fill a fresh serial, keep version.
        var fresh = nextBomSerial(machine, taxonomies, prefix);
        return new BomIdentity(prefix + fresh, fresh, currentVersion, false);
      }
      return new BomIdentity(sparepart.getCode(), serial, currentVersion, false);
    }
    var serial = nextBomSerial(machine, taxonomies, prefix);
    return new BomIdentity(prefix + serial, serial, currentVersion + 1, true);
  }

  private void rejectDuplicateIdentity(MachineEntity machine, TaxonomyRefs taxonomies) {
    if (spareparts.existsByIdentity(
        machine.getId(),
        taxonomies.category().getId(),
        taxonomies.brand().getId(),
        taxonomies.kind().getId(),
        taxonomies.type().getId())) {
      throw new DuplicateSparepartException();
    }
  }

  private void rejectDuplicateIdentity(UUID sparepartId, MachineEntity machine, TaxonomyRefs taxonomies) {
    if (spareparts.existsByIdentityExcludingId(
        sparepartId,
        machine.getId(),
        taxonomies.category().getId(),
        taxonomies.brand().getId(),
        taxonomies.kind().getId(),
        taxonomies.type().getId())) {
      throw new DuplicateSparepartException();
    }
  }

  /**
   * Allocates the next 3-digit BOM serial. The code series (per machine+plant+category+kind+brand
   * prefix) keeps the established "code excludes type" increment, while the machine+category+kind
   * serial space (backing {@code uq_spareparts_machine_cat_kind_serial}) is scanned too: two brands
   * of the same kind share that DB uniqueness domain, so the next serial is the max of both series.
   */
  private String nextBomSerial(MachineEntity machine, TaxonomyRefs taxonomies, String prefix) {
    // DW-121: machine codes may contain _ (and defensively %/\); escape them so the
    // LIKE only matches this machine's own BOM series instead of wildcarding.
    var escaped = LikePattern.escape(prefix);
    var maxCodeSeries = spareparts.findCodesByEscapedPrefix(escaped).stream()
        .filter(code -> code.length() == prefix.length() + 3)
        .map(code -> code.substring(prefix.length()))
        .mapToLong(SparepartService::parseSeries)
        .max()
        .orElse(-1L);
    var maxKindSerial = spareparts.findBomSerialsByMachineCategoryKind(
            machine.getId(), taxonomies.category().getId(), taxonomies.kind().getId()).stream()
        .mapToLong(SparepartService::parseSeries)
        .max()
        .orElse(-1L);
    var next = Math.max(maxCodeSeries, maxKindSerial) + 1;
    if (next > 999) {
      throw new DuplicateSparepartException();
    }
    return String.format(Locale.ROOT, "%03d", next);
  }

  private static long parseSeries(String series) {
    if (series.length() != 3 || !series.chars().allMatch(Character::isDigit)) {
      return -1L;
    }
    return Long.parseLong(series);
  }

  /** Column bound: {@code spareparts.hierarchy_identity_key VARCHAR(255)}. */
  private static final int MAX_HIERARCHY_KEY_LENGTH = 255;

  private String hierarchyIdentityKey(MachineEntity machine, TaxonomyRefs taxonomies) {
    var key = SparepartDerivation.hierarchyIdentityKey(machine.getCode(), machine.getPlant().getCode(),
        taxonomies.category().getCode(), taxonomies.kind().getCode(), taxonomies.brand().getCode(),
        taxonomies.type().getCode());
    if (key.length() > MAX_HIERARCHY_KEY_LENGTH) {
      throw new SparepartValidationException();
    }
    return key;
  }

  private String bomPrefix(MachineEntity machine, TaxonomyRefs taxonomies) {
    return SparepartDerivation.bomPrefix(machine.getCode(), machine.getPlant().getCode(),
        taxonomies.category().getCode(), taxonomies.kind().getCode(), taxonomies.brand().getCode());
  }

  private String codePart(SparepartTaxonomyEntity taxonomy) {
    return SparepartDerivation.codePart(taxonomy.getCode());
  }

  private UUID requiredId(UUID id) {
    if (id == null) {
      throw new SparepartValidationException();
    }
    return id;
  }

  private MachineEntity resolveMachine(AuthenticatedUser user, UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(SparepartMachineNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      var assignedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
          .map(assignment -> assignment.getPlantId())
          .toList();
      if (!assignedPlantIds.contains(machine.getPlant().getId())) {
        throw new SparepartMachineNotFoundException();
      }
    }
    return machine;
  }

  /** Same plant-access semantics as {@link #resolveMachine}, derived from an existing sparepart. */
  private void requireSparepartPlantAccess(AuthenticatedUser user, SparepartEntity sparepart) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var assignedPlantIds = assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(assignment -> assignment.getPlantId())
        .toList();
    if (!assignedPlantIds.contains(sparepart.getMachine().getPlant().getId())) {
      throw new SparepartNotFoundException();
    }
  }

  private String normalizeMaterialCode(String materialCode) {
    if (materialCode == null) {
      return null;
    }
    var trimmed = materialCode.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_MATERIAL_CODE_LENGTH) {
      throw new SparepartValidationException();
    }
    return trimmed;
  }

  /** Canonicalizes to scale 2 so {@code 36} and {@code 36.00} are equal for no-op detection. */
  private BigDecimal normalizeLeadTimeHours(BigDecimal leadTimeHours) {
    return leadTimeHours == null ? null : leadTimeHours.stripTrailingZeros();
  }

  private void rejectDuplicateMaterialCode(UUID sparepartId, String materialCode) {
    if (materialCode != null && spareparts.existsByMaterialCodeIgnoreCaseAndIdNot(materialCode, sparepartId)) {
      throw new DuplicateMaterialCodeException();
    }
  }

  private TaxonomyRefs resolveTaxonomies(SparepartCommand command) {
    var refs = new TaxonomyRefs(
        taxonomy(command.categoryId(), SparepartTaxonomyDimension.CATEGORY),
        taxonomy(command.brandId(), SparepartTaxonomyDimension.BRAND),
        taxonomy(command.kindId(), SparepartTaxonomyDimension.KIND),
        taxonomy(command.typeId(), SparepartTaxonomyDimension.TYPE));
    validateLinkedTaxonomy(refs.category(), refs.brand());
    validateLinkedTaxonomy(refs.category(), refs.kind());
    validateLinkedTaxonomy(refs.category(), refs.type());
    return refs;
  }

  private SparepartTaxonomyEntity taxonomy(UUID id, SparepartTaxonomyDimension expectedDimension) {
    var entry = taxonomy.findById(id).orElseThrow(SparepartTaxonomyReferenceNotFoundException::new);
    if (entry.getDimension() != expectedDimension) {
      throw new SparepartTaxonomyDimensionMismatchException();
    }
    return entry;
  }

  private void validateLinkedTaxonomy(SparepartTaxonomyEntity category, SparepartTaxonomyEntity dependent) {
    if (dependent.getCategory() == null || !dependent.getCategory().getId().equals(category.getId())) {
      throw new SparepartTaxonomyDimensionMismatchException();
    }
  }

  private String sparepartLabel(TaxonomyRefs taxonomies) {
    return SparepartDerivation.sparepartLabel(
        taxonomies.category().getName(), taxonomies.kind().getName(),
        taxonomies.brand().getName(), taxonomies.type().getName());
  }

  private SparepartEntity save(SparepartEntity sparepart) {
    try {
      return spareparts.saveAndFlush(sparepart);
    } catch (DataIntegrityViolationException exception) {
      if (isConstraintViolation(exception, DUPLICATE_CODE_CONSTRAINT, DUPLICATE_IDENTITY_CONSTRAINT,
          DUPLICATE_HIERARCHY_KEY_CONSTRAINT, DUPLICATE_BOM_CODE_CONSTRAINT, DUPLICATE_BOM_SERIAL_CONSTRAINT)) {
        throw new DuplicateSparepartException();
      }
      if (isConstraintViolation(exception, DUPLICATE_MATERIAL_CODE_CONSTRAINT,
          DUPLICATE_MATERIAL_CODE_KEY_CONSTRAINT)) {
        throw new DuplicateMaterialCodeException();
      }
      throw new SparepartDataIntegrityException();
    }
  }

  private boolean isConstraintViolation(DataIntegrityViolationException exception, String... constraintNames) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof ConstraintViolationException constraint) {
        for (var name : constraintNames) {
          if (name.equalsIgnoreCase(constraint.getConstraintName())) {
            return true;
          }
        }
      }
      cause = cause.getCause();
    }
    return false;
  }

  private SparepartView toView(SparepartEntity sparepart) {
    return new SparepartView(
        sparepart.getId(),
        sparepart.getCode(),
        toMachineRef(sparepart.getMachine()),
        toTaxonomyRef(sparepart.getCategory()),
        toTaxonomyRef(sparepart.getBrand()),
        toTaxonomyRef(sparepart.getKind()),
        toTaxonomyRef(sparepart.getType()),
        sparepart.getMaterialCode(),
        sparepart.getLeadTimeHours(),
        sparepart.getHierarchyIdentityKey(),
        sparepart.getBomSerial(),
        sparepart.getBomCode(),
        sparepart.getBomCodeVersion(),
        sparepart.getReviewStatus(),
        sparepart.getRejectionReason(),
        sparepart.getCreatedAt(),
        sparepart.getUpdatedAt());
  }

  private SparepartMachineRefView toMachineRef(MachineEntity machine) {
    var plant = machine.getPlant();
    return new SparepartMachineRefView(machine.getId(), machine.getCode(), machine.getName(), plant.getId(), plant.getCode(), plant.getName());
  }

  private SparepartTaxonomyRefView toTaxonomyRef(SparepartTaxonomyEntity taxonomy) {
    return new SparepartTaxonomyRefView(taxonomy.getId(), taxonomy.getCode(), taxonomy.getName());
  }

  private record TaxonomyRefs(
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type) {
  }

  public record SparepartCommand(UUID machineId, UUID categoryId, UUID brandId, UUID kindId, UUID typeId) {
  }

  /** Procurement-readiness subset for PATCH. Null clears a value (Story 8-2). */
  public record SparepartProcurementCommand(String materialCode, BigDecimal leadTimeHours) {
  }

  public record SparepartFilters(UUID categoryId, UUID brandId, UUID kindId, UUID typeId, String search, String machineCode, UUID machineId, BomReviewStatus reviewStatus) {
  }

  public record SparepartListView(List<SparepartView> items, long totalElements, int page, int size, String sort) {
  }

  public record SparepartMachineRefView(UUID id, String code, String name, UUID plantId, String plantCode, String plantName) {
  }

  public record SparepartTaxonomyRefView(UUID id, String code, String name) {
  }

  public record SparepartView(
      UUID id,
      String code,
      SparepartMachineRefView machine,
      SparepartTaxonomyRefView category,
      SparepartTaxonomyRefView brand,
      SparepartTaxonomyRefView kind,
      SparepartTaxonomyRefView type,
      String materialCode,
      BigDecimal leadTimeHours,
      String hierarchyIdentityKey,
      String bomSerial,
      String bomCode,
      Integer bomCodeVersion,
      BomReviewStatus reviewStatus,
      String rejectionReason,
      Instant createdAt,
      Instant updatedAt) {
  }

  public static class DuplicateSparepartException extends RuntimeException {
  }

  /** Global material-code uniqueness violation (DB index {@code uq_spareparts_material_code}). */
  public static class DuplicateMaterialCodeException extends RuntimeException {
  }

  public static class SparepartDataIntegrityException extends RuntimeException {
  }

  public static class SparepartMutationForbiddenException extends RuntimeException {
  }

  /** Review transition attempted on a sparepart that is not PENDING_REVIEW (Story 18-1, 409). */
  public static class SparepartReviewTransitionException extends RuntimeException {
  }

  public static class SparepartNotFoundException extends RuntimeException {
  }

  public static class SparepartMachineNotFoundException extends RuntimeException {
  }

  public static class SparepartTaxonomyDimensionMismatchException extends RuntimeException {
  }

  public static class SparepartTaxonomyReferenceNotFoundException extends RuntimeException {
  }

  public static class SparepartValidationException extends RuntimeException {
  }
}
