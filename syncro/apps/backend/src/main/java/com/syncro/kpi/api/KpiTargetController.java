package com.syncro.kpi.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.kpi.application.KpiQueryService;
import com.syncro.kpi.application.KpiTargetService;
import com.syncro.kpi.domain.KpiType;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KPI REST surface (story 20-1): plant-scoped target configuration and scope-filtered
 * materialized reads. Controllers only bind/validate and delegate — no business rules,
 * no transactions, no repository access (spine API rule).
 */
@RestController
@RequestMapping("/api/v1/kpi")
public class KpiTargetController {

  private final KpiTargetService targetService;
  private final KpiQueryService queryService;

  public KpiTargetController(KpiTargetService targetService, KpiQueryService queryService) {
    this.targetService = targetService;
    this.queryService = queryService;
  }

  @Operation(operationId = "listKpiTargets", summary = "List KPI targets for a plant")
  @GetMapping("/targets")
  public List<KpiTargetService.TargetView> listTargets(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId) {
    return targetService.list(user, plantId);
  }

  @Operation(operationId = "upsertKpiTarget", summary = "Create or update a plant/month KPI target")
  @PutMapping("/targets")
  public KpiTargetService.TargetView upsertTarget(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody UpsertTargetRequest request) {
    return targetService.upsert(user, new KpiTargetService.TargetCommand(
        request.plantId(), request.month(), request.monthlyBreakdownTarget(),
        request.mtbfTargetDays(), request.mttrTargetMinutes(), request.oeeQualityPercent(),
        request.oeePerformancePercent()));
  }

  @Operation(operationId = "getMaterializedKpi", summary = "Read materialized monthly KPI rows in scope")
  @GetMapping("/materialized/{type}")
  public KpiQueryService.MaterializedResponse materialized(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String type,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month,
      @RequestParam(required = false) UUID plantId) {
    var kpiType = KpiType.fromPath(type);
    if (kpiType == null) {
      throw new UnknownKpiTypeException();
    }
    return queryService.materialized(user, kpiType, month.withDayOfMonth(1), plantId);
  }

  public record UpsertTargetRequest(
      @NotNull UUID plantId,
      @NotNull LocalDate month,
      @PositiveOrZero Integer monthlyBreakdownTarget,
      @PositiveOrZero BigDecimal mtbfTargetDays,
      @PositiveOrZero BigDecimal mttrTargetMinutes,
      @DecimalMin("0") @DecimalMax("100") BigDecimal oeeQualityPercent,
      @DecimalMin("0") @DecimalMax("100") BigDecimal oeePerformancePercent) {
  }

  /** Unknown materialized type path segment. */
  public static class UnknownKpiTypeException extends RuntimeException {
  }
}
