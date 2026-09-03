package com.syncro.maintenance.preventive.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.infrastructure.db.CalibrationInstrumentRepository;
import com.syncro.maintenance.preventive.application.PmChecksheetService.InvalidChecksheetTransitionException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.MachineNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.PmChecksheetForbiddenException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.PmChecksheetNotFoundException;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecklistCategoryEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecklistCategoryRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecklistItemEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecklistItemRepository;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecksheetEntity;
import com.syncro.maintenance.preventive.infrastructure.db.PmChecksheetRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
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
 * PM checklist categories &amp; items service (story 19-2, blueprint F4). Defines and
 * edits the actual check content — ordered categories and items (MEASUREMENT with
 * unit/lsl/nominal/usl bounds or OK_NG) — against an UNAPPROVED checksheet revision.
 * Approved revisions are frozen: every mutation first re-checks the owning checksheet's
 * {@code approvedBy == null} and surfaces 409 INVALID_CHECKSHEET_TRANSITION otherwise
 * (corrections go through 19-1's revise flow; the revision chain preserves history).
 *
 * <p>Gates: same four-role set + machine plant/group scope as 19-1 create/revise
 * (SUPER_ADMIN bypass), resolved through the checksheet's machine via the
 * requireMutationAccess pattern; reads are scope-filtered the same way 19-1 list/get
 * filter, so scoped data never leaks across plant/group boundaries.
 *
 * <p>Item sequence defaults to max+1 within the checksheet, computed in-transaction.
 * V1 carries no unique constraint on (checksheet_id, sequence), so concurrent creates
 * may produce duplicate sequence values — tolerated by design (ordering is by sequence
 * asc, stable-enough for 19-5 rendering).
 *
 * <p>Category delete nulls {@code item.categoryId} explicitly service-side (rather than
 * relying on the FK's ON DELETE SET NULL) so the audit trail records which items were
 * orphaned.
 */
@Service
public class PmChecklistService {

  private final PmChecksheetRepository checksheets;
  private final PmChecklistCategoryRepository categories;
  private final PmChecklistItemRepository items;
  private final MachineRepository machines;
  private final CalibrationInstrumentRepository calibrationInstruments;
  private final AuditLogWriter auditLog;
  private final OperationalScopeService scopes;
  private final Clock clock;

  public PmChecklistService(PmChecksheetRepository checksheets,
      PmChecklistCategoryRepository categories, PmChecklistItemRepository items,
      MachineRepository machines, CalibrationInstrumentRepository calibrationInstruments,
      AuditLogWriter auditLog, OperationalScopeService scopes, Clock clock) {
    this.checksheets = checksheets;
    this.categories = categories;
    this.items = items;
    this.machines = machines;
    this.calibrationInstruments = calibrationInstruments;
    this.auditLog = auditLog;
    this.scopes = scopes;
    this.clock = clock;
  }

  // -------------------------------------------------------------------------
  // Categories
  // -------------------------------------------------------------------------

  @Transactional
  public CategoryView createCategory(AuthenticatedUser user, CreateCategoryCommand command) {
    var context = requireMutationContext(user, command.checksheetId());
    var name = requireText(command.name(), "name", 200);
    var sortOrder = command.sortOrder() != null ? command.sortOrder() : 0;
    requireNonNegativeSortOrder(sortOrder);
    var now = Instant.now(clock);
    var entity = new PmChecklistCategoryEntity(UUID.randomUUID(), context.checksheet().getId(),
        name, sortOrder, now, now);
    var saved = categories.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE,
        AuditEntityType.PM_CHECKLIST_CATEGORY, saved.getId(), saved.getName(),
        context.machine().getPlant().getId(), null, categoryValues(saved), null));
    return toCategoryView(saved);
  }

  @Transactional(readOnly = true)
  public List<CategoryView> listCategories(AuthenticatedUser user, UUID checksheetId) {
    var checksheet = loadChecksheet(checksheetId);
    var machine = loadMachine(checksheet.getMachineId());
    // 19-1 list idiom: out-of-scope rows are invisible (empty), not a 403 probe.
    if (!canRead(user, machine)) {
      return List.of();
    }
    return categories.findByChecksheetIdOrderBySortOrderAsc(checksheet.getId()).stream()
        .map(PmChecklistService::toCategoryView)
        .toList();
  }

  @Transactional
  public CategoryView updateCategory(AuthenticatedUser user, UUID id,
      UpdateCategoryCommand command) {
    var category = loadCategory(id);
    var context = requireMutationContext(user, category.getChecksheetId());
    var name = requireText(command.name(), "name", 200);
    // Null-merge parity with 19-1 frequency update: an omitted sortOrder keeps the
    // current value.
    var sortOrder = command.sortOrder() != null ? command.sortOrder() : category.getSortOrder();
    requireNonNegativeSortOrder(sortOrder);
    var previous = categoryValues(category);
    category.update(name, sortOrder, Instant.now(clock));
    var saved = categories.saveAndFlush(category);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
        AuditEntityType.PM_CHECKLIST_CATEGORY, saved.getId(), saved.getName(),
        context.machine().getPlant().getId(), previous, categoryValues(saved), null));
    return toCategoryView(saved);
  }

  @Transactional
  public void deleteCategory(AuthenticatedUser user, UUID id) {
    var category = loadCategory(id);
    var context = requireMutationContext(user, category.getChecksheetId());
    var now = Instant.now(clock);
    // Orphan referencing items explicitly (service-side, before the delete) so each
    // orphaning is audit-logged — the FK's ON DELETE SET NULL would do it silently.
    for (var item : items.findByCategoryId(category.getId())) {
      var itemPrevious = itemValues(item);
      item.clearCategory(now);
      items.saveAndFlush(item);
      auditLog.record(user, new AuditRecord(AuditAction.UPDATE,
          AuditEntityType.PM_CHECKLIST_ITEM, item.getId(), itemLabel(item),
          context.machine().getPlant().getId(), itemPrevious, itemValues(item), null));
    }
    var previous = categoryValues(category);
    categories.delete(category);
    categories.flush();
    auditLog.record(user, new AuditRecord(AuditAction.DELETE,
        AuditEntityType.PM_CHECKLIST_CATEGORY, category.getId(), category.getName(),
        context.machine().getPlant().getId(), previous, null, null));
  }

  // -------------------------------------------------------------------------
  // Items
  // -------------------------------------------------------------------------

  @Transactional
  public ItemView createItem(AuthenticatedUser user, CreateItemCommand command) {
    var context = requireMutationContext(user, command.checksheetId());
    var parameterText = requireText(command.parameterText(), "parameterText", 500);
    if (command.inputType() == null) {
      throw new ChecklistValidationException(Map.of("inputType", "inputType is required."));
    }
    var categoryId = resolveCategoryId(command.checksheetId(), command.categoryId());
    var calibrationInstrumentId = resolveCalibrationInstrument(command.calibrationInstrumentId());
    requireBounds(command.inputType(), command.lsl(), command.usl());
    requireNonNegativeSequence(command.sequence());
    // PUT-style explicit sequence wins; otherwise default to max+1 within the checksheet.
    // Overflow guard: at Integer.MAX_VALUE a max+1 would wrap to a negative sequence.
    var maxSequence = items.findMaxSequence(context.checksheet().getId()).orElse(0);
    if (maxSequence == Integer.MAX_VALUE) {
      throw new ChecklistValidationException(Map.of("sequence", "sequence exhausted."));
    }
    var sequence = command.sequence() != null ? command.sequence() : maxSequence + 1;
    var now = Instant.now(clock);
    var entity = new PmChecklistItemEntity(UUID.randomUUID(), context.checksheet().getId(),
        categoryId, sequence, parameterText, normalizeOptional(command.checkMethod()),
        command.inputType(), normalizeOptional(command.unit()), command.lsl(), command.nominal(),
        command.usl(), command.isCriticalFlag() != null && command.isCriticalFlag(),
        normalizeOptional(command.referenceDocument()), calibrationInstrumentId, now, now);
    var saved = items.saveAndFlush(entity);
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.PM_CHECKLIST_ITEM,
        saved.getId(), itemLabel(saved), context.machine().getPlant().getId(), null,
        itemValues(saved), null));
    return toItemView(saved);
  }

  @Transactional(readOnly = true)
  public List<ItemView> listItems(AuthenticatedUser user, UUID checksheetId, UUID categoryId) {
    var checksheet = loadChecksheet(checksheetId);
    var machine = loadMachine(checksheet.getMachineId());
    if (!canRead(user, machine)) {
      return List.of();
    }
    // A provided filter must resolve against THIS checksheet (foreign/unknown → 400/404),
    // matching the create-path validation — an unknown filter should not silently
    // return an empty 200.
    if (categoryId != null) {
      resolveCategoryId(checksheet.getId(), categoryId);
    }
    List<PmChecklistItemEntity> rows = categoryId != null
        ? items.findByChecksheetIdAndCategoryIdOrderBySequenceAsc(checksheet.getId(), categoryId)
        : items.findByChecksheetIdOrderBySequenceAsc(checksheet.getId());
    return rows.stream().map(PmChecklistService::toItemView).toList();
  }

  @Transactional(readOnly = true)
  public ItemView getItem(AuthenticatedUser user, UUID id) {
    var item = loadItem(id);
    var machine = loadMachine(loadChecksheet(item.getChecksheetId()).getMachineId());
    // 19-1 get idiom: direct access to an out-of-scope resource is a 403.
    if (!canRead(user, machine)) {
      throw new PmChecksheetForbiddenException();
    }
    return toItemView(item);
  }

  @Transactional
  public ItemView updateItem(AuthenticatedUser user, UUID id, UpdateItemCommand command) {
    var item = loadItem(id);
    var context = requireMutationContext(user, item.getChecksheetId());
    var parameterText = requireText(command.parameterText(), "parameterText", 500);
    if (command.inputType() == null) {
      throw new ChecklistValidationException(Map.of("inputType", "inputType is required."));
    }
    // Null-merge (19-1 frequency-update idiom): an omitted optional field keeps its
    // current value; a provided value replaces it (blank optional text clears).
    var categoryId = command.categoryId() != null
        ? resolveCategoryId(context.checksheet().getId(), command.categoryId())
        : item.getCategoryId();
    var calibrationInstrumentId = command.calibrationInstrumentId() != null
        ? resolveCalibrationInstrument(command.calibrationInstrumentId())
        : item.getCalibrationInstrumentId();
    var sequence = command.sequence() != null ? command.sequence() : item.getSequence();
    var lsl = command.lsl() != null ? command.lsl() : item.getLsl();
    var usl = command.usl() != null ? command.usl() : item.getUsl();
    requireBounds(command.inputType(), lsl, usl);
    var previous = itemValues(item);
    item.update(categoryId, sequence, parameterText,
        mergeOptionalText(command.checkMethod(), item.getCheckMethod()), command.inputType(),
        mergeOptionalText(command.unit(), item.getUnit()), lsl,
        command.nominal() != null ? command.nominal() : item.getNominal(), usl,
        command.isCriticalFlag() != null ? command.isCriticalFlag() : item.isCriticalFlag(),
        mergeOptionalText(command.referenceDocument(), item.getReferenceDocument()),
        calibrationInstrumentId, Instant.now(clock));
    var saved = items.saveAndFlush(item);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PM_CHECKLIST_ITEM,
        saved.getId(), itemLabel(saved), context.machine().getPlant().getId(), previous,
        itemValues(saved), null));
    return toItemView(saved);
  }

  @Transactional
  public void deleteItem(AuthenticatedUser user, UUID id) {
    var item = loadItem(id);
    var context = requireMutationContext(user, item.getChecksheetId());
    var previous = itemValues(item);
    items.delete(item);
    items.flush();
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.PM_CHECKLIST_ITEM,
        item.getId(), itemLabel(item), context.machine().getPlant().getId(), previous, null,
        null));
  }

  // -------------------------------------------------------------------------
  // Gates & context
  // -------------------------------------------------------------------------

  /**
   * Mutation precondition: checksheet exists (404), the actor passes the 19-1
   * four-role + machine-scope gate (403), and the revision is unapproved (409).
   */
  private MutationContext requireMutationContext(AuthenticatedUser user, UUID checksheetId) {
    var checksheet = loadChecksheet(checksheetId);
    var machine = loadMachine(checksheet.getMachineId());
    requireMutationAccess(user, machine);
    if (checksheet.getApprovedBy() != null) {
      throw new InvalidChecksheetTransitionException();
    }
    return new MutationContext(checksheet, machine);
  }

  /** Read gate: SUPER_ADMIN unrestricted; everyone else needs the machine in scope. */
  private boolean canRead(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return true;
    }
    return inScope(scopes.derive(user), machine);
  }

  /** Gate: same four-role set + machine-plant/group scope as 19-1 (SUPER_ADMIN bypass). */
  private void requireMutationAccess(AuthenticatedUser user, MachineEntity machine) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return;
    }
    var scope = scopes.derive(user);
    switch (user.applicationRole()) {
      case SECTION_LEADER -> {
        if (!groupInScope(scope, machine)) {
          throw new PmChecksheetForbiddenException();
        }
      }
      case MAINTENANCE_LEADER, MANAGER_MAINTENANCE -> {
        if (!plantInScope(scope, machine) && !groupInScope(scope, machine)) {
          throw new PmChecksheetForbiddenException();
        }
      }
      case STAFF_MAINTENANCE -> {
        if (!plantInScope(scope, machine)) {
          throw new PmChecksheetForbiddenException();
        }
      }
      default -> throw new PmChecksheetForbiddenException();
    }
  }

  private boolean inScope(OperationalScope scope, MachineEntity machine) {
    return plantInScope(scope, machine) || groupInScope(scope, machine);
  }

  private boolean plantInScope(OperationalScope scope, MachineEntity machine) {
    return scope.plantIds() != null && scope.plantIds().contains(machine.getPlant().getId());
  }

  private boolean groupInScope(OperationalScope scope, MachineEntity machine) {
    return scope.machineGroupIds().contains(machine.getMachineGroup().getId())
        || scope.activeTeamIds().contains(machine.getMachineGroup().getId());
  }

  // -------------------------------------------------------------------------
  // Validation helpers
  // -------------------------------------------------------------------------

  /** null → null; blank → null; otherwise trimmed. */
  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /** PUT null-merge for optional text: omitted keeps the current value, provided (even blank) replaces. */
  private static String mergeOptionalText(String provided, String current) {
    return provided != null ? normalizeOptional(provided) : current;
  }

  /**
   * Blank-after-trim and over-long values are rejected here rather than at the DB
   * CHECK (ck_pm_checklist_categories_name_not_blank /
   * ck_pm_checklist_items_parameter_not_blank), which would surface as a 500 —
   * @NotBlank passes on "   ", so the service is the last boundary that can
   * produce a 400 VALIDATION_ERROR (19-1 lesson).
   */
  private static String requireText(String value, String field, int maxLength) {
    var trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      throw new ChecklistValidationException(Map.of(field, field + " must not be blank."));
    }
    if (trimmed.length() > maxLength) {
      throw new ChecklistValidationException(
          Map.of(field, field + " must be at most " + maxLength + " characters."));
    }
    return trimmed;
  }

  private static void requireNonNegativeSortOrder(int sortOrder) {
    if (sortOrder < 0) {
      throw new ChecklistValidationException(
          Map.of("sortOrder", "sortOrder must be zero or greater."));
    }
  }

  private static void requireNonNegativeSequence(Integer sequence) {
    if (sequence != null && sequence < 0) {
      throw new ChecklistValidationException(
          Map.of("sequence", "sequence must be zero or greater."));
    }
  }

  /** MEASUREMENT with both bounds present → lsl ≤ usl (BigDecimal compare, never float). */
  private static void requireBounds(PmItemInputType inputType, BigDecimal lsl, BigDecimal usl) {
    if (inputType == PmItemInputType.MEASUREMENT && lsl != null && usl != null
        && lsl.compareTo(usl) > 0) {
      throw new ChecklistValidationException(
          Map.of("lsl", "lsl must be less than or equal to usl."));
    }
  }

  /**
   * Optional category reference: unknown id → 404; an existing category from another
   * checksheet → 400 (items and their category must share one revision).
   */
  private UUID resolveCategoryId(UUID checksheetId, UUID categoryId) {
    if (categoryId == null) {
      return null;
    }
    var category = categories.findById(categoryId)
        .orElseThrow(PmChecklistCategoryNotFoundException::new);
    if (!category.getChecksheetId().equals(checksheetId)) {
      throw new ChecklistValidationException(
          Map.of("categoryId", "Category belongs to a different checksheet."));
    }
    return category.getId();
  }

  /** Optional calibration reference: present but unknown → 404 CALIBRATION_INSTRUMENT_NOT_FOUND. */
  private UUID resolveCalibrationInstrument(UUID calibrationInstrumentId) {
    if (calibrationInstrumentId == null) {
      return null;
    }
    // Cross-module repo read (MachineRepository precedent): existence check only.
    if (!calibrationInstruments.existsById(calibrationInstrumentId)) {
      throw new CalibrationInstrumentNotFoundException();
    }
    return calibrationInstrumentId;
  }

  // -------------------------------------------------------------------------
  // Loaders
  // -------------------------------------------------------------------------

  private PmChecksheetEntity loadChecksheet(UUID id) {
    return checksheets.findById(id).orElseThrow(PmChecksheetNotFoundException::new);
  }

  private PmChecklistCategoryEntity loadCategory(UUID id) {
    return categories.findById(id).orElseThrow(PmChecklistCategoryNotFoundException::new);
  }

  private PmChecklistItemEntity loadItem(UUID id) {
    return items.findById(id).orElseThrow(PmChecklistItemNotFoundException::new);
  }

  private MachineEntity loadMachine(UUID machineId) {
    return machines.findByIdWithPlantAndGroup(machineId)
        .orElseThrow(MachineNotFoundException::new);
  }

  // -------------------------------------------------------------------------
  // Audit value maps & views (all UUIDs stringified — 19-1 pattern)
  // -------------------------------------------------------------------------

  private static Map<String, Object> categoryValues(PmChecklistCategoryEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("checksheetId", entity.getChecksheetId().toString());
    values.put("name", entity.getName());
    values.put("sortOrder", entity.getSortOrder());
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  private static Map<String, Object> itemValues(PmChecklistItemEntity entity) {
    var values = new LinkedHashMap<String, Object>();
    values.put("id", entity.getId().toString());
    values.put("checksheetId", entity.getChecksheetId().toString());
    values.put("categoryId", entity.getCategoryId() != null ? entity.getCategoryId().toString() : null);
    values.put("sequence", entity.getSequence());
    values.put("parameterText", entity.getParameterText());
    values.put("checkMethod", entity.getCheckMethod());
    values.put("inputType", entity.getInputType().name());
    values.put("unit", entity.getUnit());
    values.put("lsl", entity.getLsl() != null ? entity.getLsl().toPlainString() : null);
    values.put("nominal", entity.getNominal() != null ? entity.getNominal().toPlainString() : null);
    values.put("usl", entity.getUsl() != null ? entity.getUsl().toPlainString() : null);
    values.put("isCriticalFlag", entity.isCriticalFlag());
    values.put("referenceDocument", entity.getReferenceDocument());
    values.put("calibrationInstrumentId", entity.getCalibrationInstrumentId() != null
        ? entity.getCalibrationInstrumentId().toString() : null);
    values.put("createdAt", entity.getCreatedAt().toString());
    values.put("updatedAt", entity.getUpdatedAt().toString());
    return values;
  }

  /** entity_label is VARCHAR(255) NOT NULL — parameterText (≤500) is truncated code-point-safe. */
  private static String itemLabel(PmChecklistItemEntity entity) {
    var text = entity.getParameterText();
    if (text.length() <= 255) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, 255));
  }

  private static CategoryView toCategoryView(PmChecklistCategoryEntity entity) {
    return new CategoryView(entity.getId(), entity.getChecksheetId(), entity.getName(),
        entity.getSortOrder(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  private static ItemView toItemView(PmChecklistItemEntity entity) {
    return new ItemView(entity.getId(), entity.getChecksheetId(), entity.getCategoryId(),
        entity.getSequence(), entity.getParameterText(), entity.getCheckMethod(),
        entity.getInputType(), entity.getUnit(), entity.getLsl(), entity.getNominal(),
        entity.getUsl(), entity.isCriticalFlag(), entity.getReferenceDocument(),
        entity.getCalibrationInstrumentId(), entity.getCreatedAt(), entity.getUpdatedAt());
  }

  // -------------------------------------------------------------------------
  // Commands & views
  // -------------------------------------------------------------------------

  private record MutationContext(PmChecksheetEntity checksheet, MachineEntity machine) {
  }

  public record CreateCategoryCommand(UUID checksheetId, String name, Integer sortOrder) {
  }

  public record UpdateCategoryCommand(String name, Integer sortOrder) {
  }

  public record CategoryView(UUID id, UUID checksheetId, String name, int sortOrder,
      Instant createdAt, Instant updatedAt) {
  }

  public record CreateItemCommand(UUID checksheetId, UUID categoryId, Integer sequence,
      String parameterText, String checkMethod, PmItemInputType inputType, String unit,
      BigDecimal lsl, BigDecimal nominal, BigDecimal usl, Boolean isCriticalFlag,
      String referenceDocument, UUID calibrationInstrumentId) {
  }

  public record UpdateItemCommand(UUID categoryId, Integer sequence, String parameterText,
      String checkMethod, PmItemInputType inputType, String unit, BigDecimal lsl,
      BigDecimal nominal, BigDecimal usl, Boolean isCriticalFlag, String referenceDocument,
      UUID calibrationInstrumentId) {
  }

  public record ItemView(UUID id, UUID checksheetId, UUID categoryId, int sequence,
      String parameterText, String checkMethod, PmItemInputType inputType, String unit,
      BigDecimal lsl, BigDecimal nominal, BigDecimal usl, boolean criticalFlag,
      String referenceDocument, UUID calibrationInstrumentId, Instant createdAt,
      Instant updatedAt) {
  }

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  public static class PmChecklistCategoryNotFoundException extends RuntimeException {
  }

  public static class PmChecklistItemNotFoundException extends RuntimeException {
  }

  public static class CalibrationInstrumentNotFoundException extends RuntimeException {
  }

  /** Blank/over-long/bounds/category-mismatch fields → 400 VALIDATION_ERROR. */
  public static class ChecklistValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public ChecklistValidationException(Map<String, String> fieldErrors) {
      this.fieldErrors = new LinkedHashMap<>(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
      return fieldErrors;
    }
  }
}
