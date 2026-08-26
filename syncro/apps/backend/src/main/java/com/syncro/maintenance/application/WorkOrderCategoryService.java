package com.syncro.maintenance.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.domain.workorder.WorkOrderCategory;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Work-order category CRUD (FR-112). Categories are global config — the mutation gate
 * is {@code SUPER_ADMIN|MANAGER_MAINTENANCE|MAINTENANCE_LEADER|SECTION_LEADER} with no
 * further plant/machine-group scoping. The in-service gate mirrors the OPA
 * {@code category_mutation_paths} set exactly (9-5 parity pattern).
 */
@Service
public class WorkOrderCategoryService {

  private final WorkOrderCategoryRepository categories;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public WorkOrderCategoryService(WorkOrderCategoryRepository categories, AuditLogWriter auditLog, Clock clock) {
    this.categories = categories;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<WorkOrderCategory> list() {
    return categories.findAllByOrderByCodeAsc().stream()
        .map(WorkOrderCategoryMapper::toDomain)
        .toList();
  }

  @Transactional
  public WorkOrderCategory create(AuthenticatedUser user, CreateWorkOrderCategoryCommand command) {
    requireCategoryRole(user);
    var code = normalizeCode(command.code());
    var label = command.label().trim();
    if (categories.existsByCode(code)) {
      throw new DuplicateWorkOrderCategoryCodeException();
    }
    var now = Instant.now(clock);
    var saved = saveWithIntegrityCheck(new WorkOrderCategoryEntity(
        UUID.randomUUID(), code, label, UUID.fromString(user.id()), now, now, command.targetResponseMinutes()));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.WORK_ORDER_CATEGORY,
        saved.getId(), saved.getCode(), null, null, auditValues(saved), null));
    return WorkOrderCategoryMapper.toDomain(saved);
  }

  @Transactional
  public WorkOrderCategory update(AuthenticatedUser user, String code, UpdateWorkOrderCategoryCommand command) {
    requireCategoryRole(user);
    var entity = categories.findByCode(normalizeCode(code)).orElseThrow(WorkOrderCategoryNotFoundException::new);
    var previous = auditValues(entity);
    var newCode = normalizeCode(command.code());
    var label = command.label().trim();
    if (!newCode.equals(entity.getCode()) && categories.existsByCode(newCode)) {
      throw new DuplicateWorkOrderCategoryCodeException();
    }
    entity.update(newCode, label, command.targetResponseMinutes(), Instant.now(clock));
    var saved = saveWithIntegrityCheck(entity);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.WORK_ORDER_CATEGORY,
        saved.getId(), saved.getCode(), null, previous, auditValues(saved), null));
    return WorkOrderCategoryMapper.toDomain(saved);
  }

  private void requireCategoryRole(AuthenticatedUser user) {
    var role = user.applicationRole();
    if (role != ApplicationRole.SUPER_ADMIN
        && role != ApplicationRole.MANAGER_MAINTENANCE
        && role != ApplicationRole.MAINTENANCE_LEADER
        && role != ApplicationRole.SECTION_LEADER) {
      throw new WorkOrderCategoryMutationForbiddenException();
    }
  }

  /** Uppercases and trims the code so uniqueness is effectively case-insensitive. */
  private String normalizeCode(String code) {
    return code.trim().toUpperCase();
  }

  private WorkOrderCategoryEntity saveWithIntegrityCheck(WorkOrderCategoryEntity entity) {
    try {
      return categories.saveAndFlush(entity);
    } catch (DataIntegrityViolationException exception) {
      if (isUniqueCodeViolation(exception)) {
        throw new DuplicateWorkOrderCategoryCodeException();
      }
      // Any other integrity violation (column length, NOT NULL, FK) is NOT a duplicate
      // code — let it surface as the generic 500 so it is never mislabeled.
      throw exception;
    }
  }

  private boolean isUniqueCodeViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
          && "uq_work_order_categories_code".equalsIgnoreCase(constraint.getConstraintName())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private Map<String, Object> auditValues(WorkOrderCategoryEntity entity) {
    var values = new HashMap<String, Object>();
    values.put("code", entity.getCode());
    values.put("label", entity.getLabel());
    values.put("targetResponseMinutes", entity.getTargetResponseMinutes());
    return values;
  }

  public record CreateWorkOrderCategoryCommand(String code, String label, Integer targetResponseMinutes) {
  }

  public record UpdateWorkOrderCategoryCommand(String code, String label, Integer targetResponseMinutes) {
  }

  public static class WorkOrderCategoryMutationForbiddenException extends RuntimeException {
  }

  public static class DuplicateWorkOrderCategoryCodeException extends RuntimeException {
  }

  public static class WorkOrderCategoryNotFoundException extends RuntimeException {
  }
}
