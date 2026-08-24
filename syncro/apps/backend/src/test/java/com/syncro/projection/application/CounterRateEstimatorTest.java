package com.syncro.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.syncro.config.ProjectionProperties;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.projection.application.CounterRateEstimator.InsufficientReason;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader.HistoryReadException;
import com.syncro.projection.infrastructure.InfluxTelemetryHistoryReader.TelemetrySample;
import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CounterRateEstimatorTest {

  private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
  private static final String MACHINE_CODE = "BF-08410";
  private static final List<ShiftWindowCommand> SHIFTS =
      List.of(new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0)));

  @Mock
  private InfluxTelemetryHistoryReader reader;

  private CounterRateEstimator estimator;

  @BeforeEach
  void setUp() {
    var properties = new ProjectionProperties(30, Duration.parse("PT48H"), Duration.parse("PT5M"),
        "Asia/Jakarta");
    estimator = new CounterRateEstimator(reader, new OperatingCalendarCalculator(), properties,
        Clock.fixed(NOW, java.time.ZoneOffset.UTC));
  }

  private static TelemetrySample sample(Instant timestamp, long counting) {
    return new TelemetrySample(timestamp, counting);
  }

  @Test
  @DisplayName("8.6-RATE-001 full 30-day coverage yields ROLLING_30_DAY with window evidence")
  void happyRollingThirtyDayBasis() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(Duration.ofDays(30)), 0),
        sample(NOW.minus(Duration.ofDays(10)), 4500),
        sample(NOW, 9000)));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isTrue();
    assertThat(estimate.calculationBasis()).isEqualTo(CalculationBasis.ROLLING_30_DAY);
    assertThat(estimate.totalDelta()).isEqualTo(9000L);
    // 30 covered local shift days x 8h = 240 operating hours.
    assertThat(estimate.operatingHours()).isEqualByComparingTo("240.00");
    assertThat(estimate.ratePerOperatingHour()).isEqualByComparingTo("37.50");
    assertThat(estimate.windowStartAt()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    assertThat(estimate.windowEndAt()).isEqualTo(NOW);
    assertThat(estimate.firstSampleAt()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    assertThat(estimate.lastSampleAt()).isEqualTo(NOW);
    assertThat(estimate.lastSampleCounting()).isEqualTo(9000L);
    assertThat(estimate.insufficientReason()).isNull();
  }

  @Test
  @DisplayName("8.6-RATE-002 first sample newer than window start falls back to FULL_HISTORY")
  void fullHistoryFallback() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(Duration.ofDays(10)), 100),
        sample(NOW, 2500)));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isTrue();
    assertThat(estimate.calculationBasis()).isEqualTo(CalculationBasis.FULL_HISTORY);
    assertThat(estimate.windowStartAt()).isEqualTo(NOW.minus(Duration.ofDays(10)));
    assertThat(estimate.totalDelta()).isEqualTo(2400L);
    assertThat(estimate.operatingHours()).isEqualByComparingTo("80.00");
    assertThat(estimate.ratePerOperatingHour()).isEqualByComparingTo("30.00");
  }

  @Test
  @DisplayName("8.6-WRAP-001 multiple counter wraps within the window sum wrap-safe deltas")
  void wrapAroundMultiWrapSum() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(3, ChronoUnit.HOURS), 60000),
        sample(NOW.minus(2, ChronoUnit.HOURS), 500),
        sample(NOW.minus(1, ChronoUnit.HOURS), 61000),
        sample(NOW, 300)));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isTrue();
    long expected = 6036 + 60500 + 4836;
    assertThat(estimate.totalDelta()).isEqualTo(expected);
  }

  @Test
  @DisplayName("8.6-INSUF-001 zero samples is NO_TELEMETRY")
  void noTelemetry() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of());

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isFalse();
    assertThat(estimate.insufficientReason()).isEqualTo(InsufficientReason.NO_TELEMETRY);
  }

  @Test
  @DisplayName("8.6-INSUF-002 a single sample is INSUFFICIENT_SAMPLES even when stale")
  void insufficientSamplesTakesPrecedenceOverStale() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(Duration.ofHours(72)), 500)));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isFalse();
    assertThat(estimate.insufficientReason()).isEqualTo(InsufficientReason.INSUFFICIENT_SAMPLES);
  }

  @Test
  @DisplayName("8.6-STALE-001 last sample older than the staleness threshold is STALE_DATA")
  void staleDataGate() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(Duration.ofHours(50)), 100),
        sample(NOW.minus(Duration.ofHours(49)), 200)));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isFalse();
    assertThat(estimate.insufficientReason()).isEqualTo(InsufficientReason.STALE_DATA);
  }

  @Test
  @DisplayName("8.6-INSUF-003 fresh samples with no shifts configured is NO_OPERATING_TIME")
  void noOperatingTimeWithoutShifts() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(2, ChronoUnit.HOURS), 100),
        sample(NOW, 200)));

    var estimate = estimator.estimate(MACHINE_CODE, List.of());

    assertThat(estimate.available()).isFalse();
    assertThat(estimate.insufficientReason()).isEqualTo(InsufficientReason.NO_OPERATING_TIME);
  }

  @Test
  @DisplayName("8.6-STALE-002 a history reader failure degrades to STALE_DATA instead of a 500")
  void readerFailureDegradesToStaleData() {
    when(reader.readSamples(anyString(), any(), any()))
        .thenThrow(new HistoryReadException("boom", new IllegalStateException("down")));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isFalse();
    assertThat(estimate.insufficientReason()).isEqualTo(InsufficientReason.STALE_DATA);
  }

  @Test
  @DisplayName("8.6-RATE-002 a flat counter over operating time is NO_PRODUCTION_DELTA, never a zero-rate divide")
  void flatCounterIsNoProductionDelta() {
    when(reader.readSamples(eq(MACHINE_CODE), any(), any())).thenReturn(List.of(
        sample(NOW.minus(Duration.ofHours(3)), 500),
        sample(NOW.minus(Duration.ofHours(1)), 500)));

    var estimate = estimator.estimate(MACHINE_CODE, SHIFTS);

    assertThat(estimate.available()).isFalse();
    assertThat(estimate.insufficientReason()).isEqualTo(InsufficientReason.NO_PRODUCTION_DELTA);
    // Evidence survives the unavailable state for operator debugging.
    assertThat(estimate.firstSampleAt()).isEqualTo(NOW.minus(Duration.ofHours(3)));
    assertThat(estimate.lastSampleAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
  }
}
