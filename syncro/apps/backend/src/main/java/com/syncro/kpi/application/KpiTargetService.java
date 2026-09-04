package com.syncro.kpi.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.kpi.infrastructure.db.KpiTargetEntity;
import com.syncro.kpi.infrastructure.db.KpiTargetRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KPI target CRUD per plant/month (story 20-1, blueprint G1). Mutations are
 * SUPER_ADMIN/MANAGER_MAINTENANCE with plant access and audit-logged with previous
 * and new values (spec "Always": target mutations audit-logged). Reads are any
 * authenticated user within plant scope. {@code month} is normalized to the first of
 * the month — the same key the materialized rows use for actual-vs-target joins.
 */
@Service
public class KpiTargetService {

  private final KpiTargetRepository targets;
  private final PlantRepository plants;
  private final PlantScopeService plantScopes;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public KpiTargetService(KpiTargetRepository targets, PlantRepository plants,
      PlantScopeService plantScopes, AuditLogWriter auditLog, Clock clock) {
    this.targets = targets;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  public record TargetCommand(UUID plantId, LocalDate month, Integer monthlyBreakdownTarget,
      BigDecimal mtbfTargetDays, BigDecimal mttrTargetMinutes, BigDecimal oeeQualityPercent,
      BigDecimal oeePerformancePercent) {
  }

  public record TargetView(UUID id, UUID plantId, String plantCode, LocalDate month,
      Integer monthlyBreakdownTarget, BigDecimal mtbfTargetDays, BigDecimal mttrTargetMinutes,
      BigDecimal oeeQualityPercent, BigDecimal oeePerformancePercent, UUID createdBy,
      Instant createdAt, Instant updatedAt) {
  }

  @Transactional(readOnly = true)
  public List<TargetView> list(AuthenticatedUser user, UUID plantId) {
    requirePlantOrSuperAdmin(user, plantId);
    return targets.findByPlantIdOrderByMonthAsc(plantId).stream()
        .map(t -> toView(t, plantCode(t.getPlantId())))
        .toList();
  }

  @Transactional
  public TargetView upsert(AuthenticatedUser user, TargetCommand command) {
    requireMutationRole(user);
    requirePlantOrSuperAdmin(user, command.plantId());
    if (!plants.existsById(command.plantId())) {
      throw new PlantNotFoundForTargetException();
    }
    var month = command.month().withDayOfMonth(1);
    var now = Instant.now(clock);
    // Review 20-1: locked read serializes concurrent updates on the same (plant, month);
    // an insert-vs-insert race still hits the unique constraint and maps to 409 below.
    var existing = targets.findByPlantIdAndMonthForUpdate(command.plantId(), month);
    if (existing.isPresent()) {
      var previous = existing.get();
      var previousValues = auditValues(previous);
      // Review 20-1: partial update — null request fields keep the stored value.
      var updated = new KpiTargetEntity(previous.getId(), previous.getPlantId(), month,
          command.monthlyBreakdownTarget() != null ? command.monthlyBreakdownTarget()
              : previous.getMonthlyBreakdownTarget(),
          command.mtbfTargetDays() != null ? command.mtbfTargetDays() : previous.getMtbfTargetDays(),
          command.mttrTargetMinutes() != null ? command.mttrTargetMinutes()
              : previous.getMttrTargetMinutes(),
          command.oeeQualityPercent() != null ? command.oeeQualityPercent()
              : previous.getOeeQualityPercent(),
          command.oeePerformancePercent() != null ? command.oeePerformancePercent()
              : previous.getOeePerformancePercent(),
          previous.getCreatedBy(), previous.getCreatedAt(), now);
      var saved = targets.saveAndFlush(updated);
      auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.KPI_TARGET,
          saved.getId(), saved.getPlantId() + ":" + month, saved.getPlantId(), previousValues,
          auditValues(saved), null));
      return toView(saved, plantCode(saved.getPlantId()));
    }
    try {
      var created = targets.saveAndFlush(new KpiTargetEntity(UUID.randomUUID(), command.plantId(),
          month, command.monthlyBreakdownTarget(), command.mtbfTargetDays(),
          command.mttrTargetMinutes(), command.oeeQualityPercent(), command.oeePerformancePercent(),
          UUID.fromString(user.id()), now, now));
      auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.KPI_TARGET,
          created.getId(), created.getPlantId() + ":" + month, created.getPlantId(), null,
          auditValues(created), null));
      return toView(created, plantCode(created.getPlantId()));
    } catch (DataIntegrityViolationException race) {
      // Concurrent create won the unique constraint — surface a stable conflict code.
      throw new TargetConflictException();
    }
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new TargetMutationForbiddenException();
    }
  }

  private void requirePlantOrSuperAdmin(AuthenticatedUser user, UUID plantId) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, plantId);
    }
  }

  private String plantCode(UUID plantId) {
    return plants.findById(plantId).map(p -> p.getCode()).orElse(null);
  }

  private static Map<String, Object> auditValues(KpiTargetEntity t) {
    var values = new LinkedHashMap<String, Object>();
    values.put("plantId", t.getPlantId().toString());
    values.put("month", t.getMonth().toString());
    values.put("monthlyBreakdownTarget", t.getMonthlyBreakdownTarget());
    values.put("mtbfTargetDays", t.getMtbfTargetDays());
    values.put("mttrTargetMinutes", t.getMttrTargetMinutes());
    values.put("oeeQualityPercent", t.getOeeQualityPercent());
    values.put("oeePerformancePercent", t.getOeePerformancePercent());
    return values;
  }

  private static TargetView toView(KpiTargetEntity t, String plantCode) {
    return new TargetView(t.getId(), t.getPlantId(), plantCode, t.getMonth(),
        t.getMonthlyBreakdownTarget(), t.getMtbfTargetDays(), t.getMttrTargetMinutes(),
        t.getOeeQualityPercent(), t.getOeePerformancePercent(), t.getCreatedBy(),
        t.getCreatedAt(), t.getUpdatedAt());
  }

  public static class TargetMutationForbiddenException extends RuntimeException {
  }

  public static class PlantNotFoundForTargetException extends RuntimeException {
  }

  /** Concurrent create on the same (plant, month) hit the unique constraint. */
  public static class TargetConflictException extends RuntimeException {
  }
}
