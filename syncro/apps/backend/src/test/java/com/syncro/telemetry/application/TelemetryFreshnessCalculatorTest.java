package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.machine.domain.MachineStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TelemetryFreshnessCalculatorTest {

  private static final Instant NOW = Instant.parse("2026-08-10T05:00:00Z");

  private final TelemetryFreshnessCalculator calculator = new TelemetryFreshnessCalculator(Clock.fixed(NOW, ZoneOffset.UTC));

  @ParameterizedTest(name = "{0} minutes ago -> {1}")
  @CsvSource({
      "4.9833, ONLINE",
      "5.0, ONLINE",
      "5.0167, OFFLINE",
      "14.9833, OFFLINE",
      "15.0, OFFLINE",
      "15.0167, STALE",
      "0.0, ONLINE"
  })
  @DisplayName("3-7-CALC-001 freshness thresholds: online <=5min, offline <=15min, stale >15min")
  void freshnessThresholdsUseInclusiveBoundaries(double minutesAgo, String expected) {
    Instant receivedAt = NOW.minus(Duration.ofMillis((long) (minutesAgo * 60_000)));

    LatestTelemetryDto.FreshnessState state = calculator.calculate(receivedAt, MachineStatus.ACTIVE);

    assertThat(state.name()).isEqualTo(expected);
  }

  @Test
  @DisplayName("3-7-CALC-002 missing telemetry for ACTIVE machine is OFFLINE")
  void missingTelemetryForActiveMachineIsOffline() {
    assertThat(calculator.calculate(null, MachineStatus.ACTIVE))
        .isEqualTo(LatestTelemetryDto.FreshnessState.OFFLINE);
  }

  @Test
  @DisplayName("3-7-CALC-003 missing telemetry for INACTIVE machine is STALE")
  void missingTelemetryForInactiveMachineIsStale() {
    assertThat(calculator.calculate(null, MachineStatus.INACTIVE))
        .isEqualTo(LatestTelemetryDto.FreshnessState.STALE);
  }

  @Test
  @DisplayName("3-7-CALC-004 manual INACTIVE is always STALE regardless of received-data freshness")
  void manualInactiveIsAlwaysStale() {
    Instant recent = NOW.minus(Duration.ofMinutes(1));

    assertThat(calculator.calculate(recent, MachineStatus.INACTIVE))
        .isEqualTo(LatestTelemetryDto.FreshnessState.STALE);
  }
}
