package com.syncro.projection.api;

import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.projection.application.CounterRateEstimator.InsufficientReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projection contract (story 8-6). {@code windowStartAt}/{@code windowEndAt} are
 * always populated; {@code calculationBasis}, {@code firstSampleAt}, {@code lastSampleAt}, and
 * {@code ratePerOperatingHour} are populated only when {@code rateAvailable=true}. Installation
 * rows mirror {@code leadTimeHours} in every state; {@code consumptionDuringLeadTime} appears
 * only when the sparepart has lead time (treated as OPERATING hours to pair with the
 * per-operating-hour rate). {@code projectedDepletionAt} is a daily-average approximation: it
 * can land outside shift windows, and it is computed in the single configured plant timezone.
 */
public final class ProjectionDtos {

  private ProjectionDtos() {
  }

  public record InstallationProjection(
      UUID installationId,
      UUID sparepartId,
      String functionName,
      boolean available,
      InsufficientReason reason,
      Long remainingCounters,
      Instant projectedDepletionAt,
      BigDecimal leadTimeHours,
      Long consumptionDuringLeadTime) {
  }

  public record MachineSparepartProjectionsView(
      UUID machineId,
      boolean rateAvailable,
      CalculationBasis calculationBasis,
      Instant windowStartAt,
      Instant windowEndAt,
      Instant firstSampleAt,
      Instant lastSampleAt,
      InsufficientReason insufficientReason,
      BigDecimal ratePerOperatingHour,
      String shiftSource,
      BigDecimal dailyOperatingHours,
      List<InstallationProjection> projections) {
  }
}
