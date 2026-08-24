package com.syncro.projection.application;

import com.syncro.config.ProjectionProperties;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader.HistoryReadException;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader.TelemetrySample;
import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import com.syncro.telemetry.application.CountingDeltaCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wrap-safe counter-rate estimation over InfluxDB history (story 8-6). rate = Σ consecutive
 * wrap-safe counting deltas ÷ effective operating hours from the resolved shift calendar.
 *
 * <p>Insufficient-data precedence is deterministic: reader failure → STALE_DATA, then
 * NO_TELEMETRY &gt; INSUFFICIENT_SAMPLES &gt; STALE_DATA &gt; NO_OPERATING_TIME &gt;
 * NO_PRODUCTION_DELTA.
 */
@Component
public class CounterRateEstimator {

  private static final Logger log = LoggerFactory.getLogger(CounterRateEstimator.class);

  private final InfluxTelemetryHistoryReader reader;
  private final OperatingCalendarCalculator calendar;
  private final ProjectionProperties properties;
  private final Clock clock;

  public CounterRateEstimator(InfluxTelemetryHistoryReader reader, OperatingCalendarCalculator calendar,
      ProjectionProperties properties, Clock clock) {
    this.reader = reader;
    this.calendar = calendar;
    this.properties = properties;
    this.clock = clock;
  }

  public enum CalculationBasis {
    ROLLING_30_DAY, FULL_HISTORY
  }

  public enum InsufficientReason {
    NO_TELEMETRY, INSUFFICIENT_SAMPLES, STALE_DATA, NO_OPERATING_TIME, NO_PRODUCTION_DELTA
  }

  public record RateEstimate(
      boolean available,
      CalculationBasis calculationBasis,
      Instant windowStartAt,
      Instant windowEndAt,
      Instant firstSampleAt,
      Instant lastSampleAt,
      long totalDelta,
      BigDecimal operatingHours,
      BigDecimal ratePerOperatingHour,
      long lastSampleCounting,
      InsufficientReason insufficientReason) {

    /** Unavailable with window evidence preserved; sample fields stay null when unknown. */
    public static RateEstimate unavailable(InsufficientReason reason, Instant windowStartAt,
        Instant windowEndAt, Instant firstSampleAt, Instant lastSampleAt) {
      return new RateEstimate(false, null, windowStartAt, windowEndAt, firstSampleAt, lastSampleAt,
          0, BigDecimal.ZERO.setScale(2), null, 0, reason);
    }
  }

  public RateEstimate estimate(String machineCode, List<ShiftWindowCommand> windows) {
    Instant now = Instant.now(clock);
    Instant rollingStart = now.minus(properties.windowDays(), java.time.temporal.ChronoUnit.DAYS);
    List<TelemetrySample> samples;
    try {
      samples = reader.readSamples(machineCode, rollingStart, now);
    } catch (HistoryReadException failure) {
      log.warn("projection_history_read_failed machineCode={} error={}", machineCode, failure.getMessage());
      return RateEstimate.unavailable(InsufficientReason.STALE_DATA, rollingStart, now, null, null);
    }
    if (samples.isEmpty()) {
      return RateEstimate.unavailable(InsufficientReason.NO_TELEMETRY, rollingStart, now, null, null);
    }
    if (samples.size() < 2) {
      return RateEstimate.unavailable(InsufficientReason.INSUFFICIENT_SAMPLES, rollingStart, now,
          null, null);
    }
    TelemetrySample first = samples.getFirst();
    TelemetrySample last = samples.getLast();
    if (last.timestamp().isBefore(now.minus(properties.staleness()))) {
      return RateEstimate.unavailable(InsufficientReason.STALE_DATA, rollingStart, now,
          first.timestamp(), last.timestamp());
    }
    boolean fullCoverage = !first.timestamp().isAfter(rollingStart);
    CalculationBasis basis = fullCoverage ? CalculationBasis.ROLLING_30_DAY : CalculationBasis.FULL_HISTORY;
    Instant basisStart = fullCoverage ? rollingStart : first.timestamp();
    // Internal precision well above the scale-2 presentation value so a genuinely tiny but
    // non-zero operating window is never misread as zero (and never divides by a rounded 0.00).
    BigDecimal operatingHours = calendar.effectiveOperatingHours(basisStart, now, windows,
        properties.plantZoneId());
    if (operatingHours.compareTo(BigDecimal.ZERO) <= 0) {
      return RateEstimate.unavailable(InsufficientReason.NO_OPERATING_TIME, basisStart, now,
          first.timestamp(), last.timestamp());
    }
    long totalDelta = totalDelta(samples);
    BigDecimal rateExact = BigDecimal.valueOf(totalDelta)
        .divide(operatingHours, 6, RoundingMode.HALF_UP);
    if (rateExact.signum() == 0) {
      return RateEstimate.unavailable(InsufficientReason.NO_PRODUCTION_DELTA, basisStart, now,
          first.timestamp(), last.timestamp());
    }
    return new RateEstimate(true, basis, basisStart, now, first.timestamp(), last.timestamp(),
        totalDelta, operatingHours.setScale(2, RoundingMode.HALF_UP),
        rateExact.setScale(2, RoundingMode.HALF_UP), last.counting(), null);
  }

  private static long totalDelta(List<TelemetrySample> orderedSamples) {
    long sum = 0;
    for (int i = 1; i < orderedSamples.size(); i++) {
      var previous = orderedSamples.get(i - 1);
      var current = orderedSamples.get(i);
      sum += CountingDeltaCalculator.delta(previous.counting(), current.counting());
    }
    return sum;
  }
}
