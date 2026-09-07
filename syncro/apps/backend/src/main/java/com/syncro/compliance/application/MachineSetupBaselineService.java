package com.syncro.compliance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.infrastructure.db.MachineSetupBaselineEntity;
import com.syncro.compliance.infrastructure.db.MachineSetupBaselineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned machine setup baselines (story 21-3, blueprint H5). Mutations are
 * gated to the 21-1 six-role set (rego {@code compliance_baseline_paths} carries
 * the identical coarse set) and audit-logged with previous/new values and the
 * machine's plant dimension. Reads filter by machine scope with the 21-1
 * {@code findScoped} predicate minus the null-machine clause ({@code machine_id}
 * is NOT NULL); SUPER_ADMIN sees all. An out-of-scope detail/mutation target is
 * 404 — existence is not leaked.
 *
 * <p>{@code version} is server-assigned per machine (max+1 in the create
 * transaction, spec Design Notes — never client-supplied). Creating a baseline
 * activates it and deactivates the machine's other active baselines in the same
 * transaction (PmChecksheetService.approve precedent); {@code /{id}/activate}
 * re-points the active flag at a specific version. The {@code parameters} JSONB
 * carries the whole setup standard including tolerances and references (no
 * separate columns, no schema validation beyond non-null — 8D precedent). The
 * entity carries {@code @Version} on {@code lock_version} (V18) — lost updates
 * surface as 409 VERSION_CONFLICT, as does a concurrent same-machine create
 * colliding on {@code uq_machine_setup_baselines_machine_ecn_version}.
 */
@Service
public class MachineSetupBaselineService {

  private final MachineSetupBaselineRepository baselines;
  private final OperationalScopeService scopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public MachineSetupBaselineService(MachineSetupBaselineRepository baselines,
      OperationalScopeService scopes, AuditLogWriter auditLog, Clock clock) {
    this.baselines = baselines;
    this.scopes = scopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  public record CreateBaselineCommand(UUID machineId, UUID ecnId, Map<String, Object> parameters) {
  }

  /**
   * {@code version} is the server-assigned business version; {@code lockVersion}
   * is exposed (21-2 L11 precedent) so clients can interpret VERSION_CONFLICT.
   */
  public record BaselineView(UUID id, UUID machineId, UUID ecnId, int version,
      Map<String, Object> parameters, UUID validatedBy, Instant validatedAt, boolean active,
      Instant createdAt, Instant updatedAt, long lockVersion) {
  }

  @Transactional(readOnly = true)
  public List<BaselineView> list(AuthenticatedUser user, UUID machineId, boolean activeOnly) {
    var scope = scopes.derive(user);
    return baselines.findScoped(scope.plantIds() == null,
        NonConformanceService.plantIds(scope), NonConformanceService.groupIds(scope),
        machineId, activeOnly).stream()
        .map(MachineSetupBaselineService::toView)
        .toList();
  }

  @Transactional(readOnly = true)
  public BaselineView get(AuthenticatedUser user, UUID id) {
    return toView(loadVisible(user, id));
  }

  @Transactional
  public BaselineView create(AuthenticatedUser user, CreateBaselineCommand command) {
    NonConformanceService.requireMutationRole(user);
    var scope = scopes.derive(user);
    // machine_id is NOT NULL — the reference must resolve, and a non-admin may
    // only file against a machine they can see (21-1 P3 parity).
    var machineScope = baselines.findMachineScope(command.machineId())
        .orElseThrow(() -> new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
            "Referenced machine was not found."));
    if (scope.plantIds() != null && !machineInScope(scope, machineScope)) {
      throw new ComplianceForbiddenException();
    }
    // Review 21-3 M1: the ECN link must resolve AND be visible in the caller's
    // scope — otherwise a user could pre-empt another plant's ECN through
    // uq_machine_setup_baselines_ecn (existence oracle rejected for links the
    // caller can see; out-of-scope reads as 404, 21-1 P1 posture).
    if (command.ecnId() != null && baselines.countEcnVisible(command.ecnId(),
        scope.plantIds() == null, NonConformanceService.plantIds(scope),
        NonConformanceService.groupIds(scope)) == 0) {
      throw new ComplianceReferenceNotFoundException("ECN_NOT_FOUND",
          "Referenced equipment change notice was not found.");
    }
    var now = Instant.now(clock);
    var version = baselines.maxVersion(command.machineId()) + 1;

    // Activate-supersedes-siblings in the same transaction (PmChecksheetService
    // .approve precedent): each flip is its own UPDATE audit so the pointer move
    // is fully traceable.
    for (var sibling : baselines.findByMachineIdAndActiveTrue(command.machineId())) {
      var siblingPrevious = auditValues(sibling);
      sibling.deactivate(now);
      baselines.saveAndFlush(sibling);
      auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
          AuditEntityType.MACHINE_SETUP_BASELINE, sibling.getId(), sibling.getId().toString(),
          machineScope.getPlantId(), siblingPrevious, auditValues(sibling), null));
    }

    // validated_by/validated_at stay null at create — the validation stamp is a
    // later lifecycle step outside this story's surface (21-2 ECN precedent:
    // actor columns are only stamped by the transition that owns them).
    var entity = new MachineSetupBaselineEntity(UUID.randomUUID(), command.machineId(),
        command.ecnId(), version, command.parameters(), null, null, true, now, now);
    try {
      var saved = baselines.saveAndFlush(entity);
      auditLog.record(user, new AuditRecord(AuditAction.CREATE,
          AuditEntityType.MACHINE_SETUP_BASELINE, saved.getId(), saved.getId().toString(),
          machineScope.getPlantId(), null, auditValues(saved), null));
      return toView(saved);
    } catch (DataIntegrityViolationException race) {
      throw classifyWriteRace(race, command.machineId());
    }
  }

  /**
   * Write-race classification shared by create and activate (review 21-3 H1/H2/F2,
   * 21-1 P5 chain walk):
   * <ul>
   *   <li>{@code uq_machine_setup_baselines_machine_version} (V18 partial index —
   *   the old (machine_id, ecn_id, version) uq is NULLS DISTINCT and never fires
   *   for concurrent no-ECN creates): a concurrent same-machine create computed
   *   the same max+1 → 409 VERSION_CONFLICT, never an unclassified 500.</li>
   *   <li>{@code uq_one_active_baseline_per_machine} (V18): a concurrent
   *   supersede/activate raced the sibling deactivation → 409 VERSION_CONFLICT.</li>
   *   <li>{@code uq_machine_setup_baselines_ecn}: one baseline per ECN — a reused
   *   link is a duplicate business key → 409 DUPLICATE_IDENTIFIER.</li>
   *   <li>TOCTOU FKs (the referenced row was deleted between validation and
   *   insert): machine/ECN links surface as their stable 404 codes.</li>
   * </ul>
   */
  private static RuntimeException classifyWriteRace(DataIntegrityViolationException race,
      UUID machineId) {
    if (NonConformanceService.causedBy(race, "uq_machine_setup_baselines_machine_version")
        || NonConformanceService.causedBy(race, "uq_machine_setup_baselines_machine_ecn_version")
        || NonConformanceService.causedBy(race,
            "uq_one_active_baseline_per_machine")) {
      return new ObjectOptimisticLockingFailureException(
          MachineSetupBaselineEntity.class.getName(), machineId);
    }
    if (NonConformanceService.causedBy(race, "uq_machine_setup_baselines_ecn")) {
      return new DuplicateIdentifierException();
    }
    if (NonConformanceService.causedBy(race, "fk_machine_setup_baselines_machine")) {
      return new ComplianceReferenceNotFoundException("MACHINE_NOT_FOUND",
          "Referenced machine was not found.");
    }
    if (NonConformanceService.causedBy(race, "fk_machine_setup_baselines_ecn")) {
      return new ComplianceReferenceNotFoundException("ECN_NOT_FOUND",
          "Referenced equipment change notice was not found.");
    }
    return race;
  }

  /** Points the machine's active flag at a specific version, superseding siblings. */
  @Transactional
  public BaselineView activate(AuthenticatedUser user, UUID id) {
    NonConformanceService.requireMutationRole(user);
    var entity = loadVisible(user, id);
    // Review 21-3 L3: activating an already-active baseline is a no-op — no
    // audit row, no lock_version bump (an idempotent repeat must not look like
    // a state change in the trail).
    if (entity.isActive()) {
      return toView(entity);
    }
    var now = Instant.now(clock);
    var plantId = resolvePlantId(entity);
    for (var sibling : baselines.findByMachineIdAndActiveTrue(entity.getMachineId())) {
      var siblingPrevious = auditValues(sibling);
      sibling.deactivate(now);
      baselines.saveAndFlush(sibling);
      auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
          AuditEntityType.MACHINE_SETUP_BASELINE, sibling.getId(), sibling.getId().toString(),
          plantId, siblingPrevious, auditValues(sibling), null));
    }
    var previous = auditValues(entity);
    entity.activate(now);
    MachineSetupBaselineEntity saved;
    try {
      saved = baselines.saveAndFlush(entity);
    } catch (DataIntegrityViolationException race) {
      // Review 21-3 H2: a concurrent create/activate raced the sibling
      // deactivation — uq_one_active_baseline_per_machine is the DB backstop.
      throw classifyWriteRace(race, entity.getMachineId());
    }
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.MACHINE_SETUP_BASELINE, saved.getId(), saved.getId().toString(),
        plantId, previous, auditValues(saved), null));
    return toView(saved);
  }

  // -------------------------------------------------------------------------
  // Gates & scope
  // -------------------------------------------------------------------------

  /** Loads the baseline and enforces machine-scope visibility (404 when filtered out). */
  MachineSetupBaselineEntity loadVisible(AuthenticatedUser user, UUID id) {
    var entity = baselines.findById(id)
        .orElseThrow(MachineSetupBaselineNotFoundException::new);
    var scope = scopes.derive(user);
    if (baselines.countVisible(id, scope.plantIds() == null,
        NonConformanceService.plantIds(scope), NonConformanceService.groupIds(scope)) == 0) {
      throw new MachineSetupBaselineNotFoundException();
    }
    return entity;
  }

  private static boolean machineInScope(OperationalScope scope,
      MachineSetupBaselineRepository.MachineScopeView machine) {
    return scope.plantIds().contains(machine.getPlantId())
        || NonConformanceService.groupIds(scope).contains(machine.getGroupId());
  }

  /** Audit plant dimension (21-1 P2 parity): the linked machine's plant. */
  UUID resolvePlantId(MachineSetupBaselineEntity entity) {
    return baselines.findMachineScope(entity.getMachineId())
        .map(MachineSetupBaselineRepository.MachineScopeView::getPlantId)
        .orElse(null);
  }

  private static Map<String, Object> auditValues(MachineSetupBaselineEntity b) {
    var values = new LinkedHashMap<String, Object>();
    values.put("machineId", b.getMachineId().toString());
    values.put("ecnId", b.getEcnId() != null ? b.getEcnId().toString() : null);
    values.put("version", b.getVersion());
    values.put("parameters", b.getParameters());
    values.put("validatedBy", b.getValidatedBy() != null ? b.getValidatedBy().toString() : null);
    values.put("validatedAt", b.getValidatedAt() != null ? b.getValidatedAt().toString() : null);
    values.put("active", b.isActive());
    return values;
  }

  static BaselineView toView(MachineSetupBaselineEntity b) {
    return new BaselineView(b.getId(), b.getMachineId(), b.getEcnId(), b.getVersion(),
        b.getParameters(), b.getValidatedBy(), b.getValidatedAt(), b.isActive(),
        b.getCreatedAt(), b.getUpdatedAt(), b.getLockVersion());
  }

  // -------------------------------------------------------------------------
  // Exceptions (mapped by ComplianceExceptionHandler to stable codes)
  // -------------------------------------------------------------------------

  /** Unknown or out-of-scope baseline. → 404 BASELINE_NOT_FOUND. */
  public static class MachineSetupBaselineNotFoundException extends RuntimeException {
  }
}
