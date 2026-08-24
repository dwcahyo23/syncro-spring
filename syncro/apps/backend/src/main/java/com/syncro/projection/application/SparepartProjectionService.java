package com.syncro.projection.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.ProjectionProperties;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.projection.api.ProjectionDtos.InstallationProjection;
import com.syncro.projection.api.ProjectionDtos.MachineSparepartProjectionsView;
import com.syncro.projection.application.CounterRateEstimator.RateEstimate;
import com.syncro.projection.infrastructure.ProjectionRedisCache;
import com.syncro.shiftconfig.application.ShiftConfigService;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineShiftConfigView;
import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.telemetry.application.CountingDeltaCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computed read model for sparepart depletion projections (story 8-6). Look-aside cached in
 * Redis under an explicit TTL; computed reads are never audited and never persisted to Postgres.
 * Scope gate order mirrors the machine-module house convention: unknown machine 404 first, then
 * plant access 403 for non-SUPER_ADMIN, before any computation or cache lookup.
 */
@Service
public class SparepartProjectionService {

  private final MachineRepository machines;
  private final MachineSparepartInstallationRepository installations;
  private final ShiftConfigService shiftConfigs;
  private final PlantScopeService plantScopes;
  private final CounterRateEstimator estimator;
  private final OperatingCalendarCalculator calendar;
  private final ProjectionRedisCache cache;
  private final Clock clock;
  private final ProjectionProperties properties;

  public SparepartProjectionService(MachineRepository machines,
      MachineSparepartInstallationRepository installations, ShiftConfigService shiftConfigs,
      PlantScopeService plantScopes, CounterRateEstimator estimator, OperatingCalendarCalculator calendar,
      ProjectionRedisCache cache, Clock clock, ProjectionProperties properties) {
    this.machines = machines;
    this.installations = installations;
    this.shiftConfigs = shiftConfigs;
    this.plantScopes = plantScopes;
    this.estimator = estimator;
    this.calendar = calendar;
    this.cache = cache;
    this.clock = clock;
    this.properties = properties;
  }

  public MachineSparepartProjectionsView getProjections(AuthenticatedUser user, UUID machineId) {
    var machine = findScopedMachine(user, machineId);
    return cache.get(machineId, MachineSparepartProjectionsView.class).orElseGet(
        () -> computeAndCache(machine));
  }

  private MachineSparepartProjectionsView computeAndCache(MachineEntity machine) {
    MachineShiftConfigView resolved = shiftConfigs.resolveByMachine(machine);
    List<ShiftWindowCommand> windows = toWindows(resolved);
    RateEstimate estimate = estimator.estimate(machine.getCode(), windows);
    BigDecimal dailyOperatingHours = calendar.dailyOperatingHours(windows);
    List<MachineSparepartInstallationEntity> rows =
        installations.findAllByMachineIdWithSparepart(machine.getId());
    List<InstallationProjection> projections = estimate.available()
        ? projectInstallations(rows, estimate, dailyOperatingHours)
        : unavailableInstallations(rows, estimate.insufficientReason());
    var view = new MachineSparepartProjectionsView(machine.getId(), estimate.available(),
        estimate.calculationBasis(), estimate.windowStartAt(), estimate.windowEndAt(),
        estimate.firstSampleAt(), estimate.lastSampleAt(), estimate.insufficientReason(),
        estimate.ratePerOperatingHour(), resolved.source(), dailyOperatingHours, projections);
    cache.put(machine.getId(), view);
    return view;
  }

  /**
   * Projection consumed uses the LAST SAMPLE counting from the estimation window rather than the
   * Redis latest hash, so remaining counters and the rate share one consistent data point.
   */
  private List<InstallationProjection> projectInstallations(List<MachineSparepartInstallationEntity> rows,
      RateEstimate estimate, BigDecimal dailyOperatingHours) {
    Instant now = Instant.now(clock);
    return rows.stream()
        .map(row -> projectRow(row, estimate, dailyOperatingHours, now))
        .toList();
  }

  private InstallationProjection projectRow(MachineSparepartInstallationEntity row, RateEstimate estimate,
      BigDecimal dailyOperatingHours, Instant now) {
    long consumed = CountingDeltaCalculator.delta(
        row.getBaselineCounter(), estimate.lastSampleCounting());
    long remaining = Math.max(0, row.getExpectedProductionCount() - consumed);
    var leadTimeHours = row.getSparepart().getLeadTimeHours();
    Long consumptionDuringLeadTime = leadTimeHours == null ? null
        : estimate.ratePerOperatingHour().multiply(leadTimeHours).setScale(0, RoundingMode.HALF_UP).longValue();
    Instant projectedDepletionAt = remaining == 0
        ? now
        : calendarDepletion(remaining, estimate, dailyOperatingHours, now);
    return new InstallationProjection(row.getId(), row.getSparepart().getId(), row.getFunctionName(),
        true, null, remaining, projectedDepletionAt, leadTimeHours, consumptionDuringLeadTime);
  }

  /**
   * Calendar conversion uses the exact window totals (totalDelta ÷ operatingHours) rather than
   * the scale-2 display rate, so a tiny-but-real rate can never divide by a rounded 0.00.
   */
  private Instant calendarDepletion(long remainingCounters, RateEstimate estimate,
      BigDecimal dailyOperatingHours, Instant now) {
    if (dailyOperatingHours.compareTo(BigDecimal.ZERO) <= 0) {
      return now;
    }
    var zone = properties.plantZoneId();
    var operatingSeconds = BigDecimal.valueOf(remainingCounters)
        .multiply(estimate.operatingHours())
        .divide(BigDecimal.valueOf(estimate.totalDelta()), 0, RoundingMode.HALF_UP);
    var dailySeconds = dailyOperatingHours.multiply(BigDecimal.valueOf(3600)).setScale(0, RoundingMode.HALF_UP);
    long wholeDays = operatingSeconds.longValue() / dailySeconds.longValue();
    long remainderSeconds = operatingSeconds.longValue() % dailySeconds.longValue();
    return LocalDateTime.ofInstant(now, zone)
        .plusDays(wholeDays)
        .plusSeconds(remainderSeconds)
        .atZone(zone)
        .toInstant();
  }

  private List<InstallationProjection> unavailableInstallations(List<MachineSparepartInstallationEntity> rows,
      CounterRateEstimator.InsufficientReason reason) {
    return rows.stream()
        .map(row -> new InstallationProjection(row.getId(), row.getSparepart().getId(), row.getFunctionName(),
            false, reason, null, null, row.getSparepart().getLeadTimeHours(), null))
        .toList();
  }

  private List<ShiftWindowCommand> toWindows(MachineShiftConfigView resolved) {
    return resolved.shifts().stream()
        .map(window -> new ShiftWindowCommand(window.startTime(), window.endTime()))
        .toList();
  }

  private MachineEntity findScopedMachine(AuthenticatedUser user, UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machine.getPlant().getId());
    }
    return machine;
  }

  public static class MachineNotFoundException extends RuntimeException {
  }
}
