package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelemetryDataQualityServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");

  @Mock
  private TelemetryDataQualityTracker tracker;

  private TelemetryDataQualityService service;

  @BeforeEach
  void setUp() {
    service = new TelemetryDataQualityService(tracker, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void snapshot(long accepted, long quarantined, long anomaly, long deadLetter,
      long lastLatencyMs) {
    when(tracker.snapshot()).thenReturn(
        new TelemetryDataQualityTracker.Snapshot(accepted, quarantined, anomaly, deadLetter,
            lastLatencyMs));
  }

  @Test
  void healthyTrafficProducesGoodStatusWithAllSuccessSeverities() {
    when(tracker.effectiveWindowSeconds()).thenReturn(3600L);
    snapshot(1000, 2, 0, 0, 800);

    var status = service.status();

    assertThat(status.status()).isEqualTo(TelemetryDataQualityState.GOOD);
    assertThat(status.statusSeverity()).isEqualTo("SUCCESS");
    assertThat(status.statusReason()).isNull();
    assertThat(status.windowSeconds()).isEqualTo(3600);
    assertThat(status.quarantinedCount()).isEqualTo(2);
    assertThat(status.receivedCount()).isEqualTo(1002);
    assertThat(status.rejectionRatePct()).isCloseTo(0.2, within(0.001));
    assertThat(status.quarantinedSeverity()).isEqualTo("SUCCESS");
    assertThat(status.rejectionRateSeverity()).isEqualTo("SUCCESS");
    assertThat(status.anomalySeverity()).isEqualTo("SUCCESS");
    assertThat(status.deadLetterSeverity()).isEqualTo("SUCCESS");
    assertThat(status.lastLatencyMs()).isEqualTo(800);
    assertThat(status.latencyState()).isEqualTo(LatencyState.NORMAL);
    assertThat(status.latencySeverity()).isEqualTo("SUCCESS");
    assertThat(status.timestamp()).isEqualTo("2026-08-22T10:00:00Z");
  }

  @Test
  void noTrafficYieldsZeroRateAndNoDataLatency() {
    snapshot(0, 0, 0, 0, TelemetryDataQualityTracker.NO_LATENCY_SAMPLE);

    var status = service.status();

    assertThat(status.status()).isEqualTo(TelemetryDataQualityState.GOOD);
    assertThat(status.rejectionRatePct()).isZero();
    assertThat(status.receivedCount()).isZero();
    assertThat(status.lastLatencyMs()).isNull();
    assertThat(status.latencyState()).isEqualTo(LatencyState.NO_DATA);
    assertThat(status.latencySeverity()).isEqualTo("NEUTRAL");
  }

  @Test
  void quarantineCountThresholdsFollowPageSpecBoundaries() {
    snapshot(10_000, 10, 0, 0, 100);
    assertThat(service.status().quarantinedSeverity()).isEqualTo("SUCCESS");

    snapshot(10_000, 11, 0, 0, 100);
    assertThat(service.status().quarantinedSeverity()).isEqualTo("WARNING");
    assertThat(service.status().status()).isEqualTo(TelemetryDataQualityState.DEGRADED);

    snapshot(10_000, 50, 0, 0, 100);
    assertThat(service.status().quarantinedSeverity()).isEqualTo("WARNING");

    snapshot(10_000, 51, 0, 0, 100);
    assertThat(service.status().quarantinedSeverity()).isEqualTo("CRITICAL");
    assertThat(service.status().status()).isEqualTo(TelemetryDataQualityState.CRITICAL);
  }

  @Test
  void rejectionRateThresholdsFollowPageSpecBoundaries() {
    // exactly 1%: 100 / (9900 + 100) stays SUCCESS
    snapshot(9_900, 100, 0, 0, 100);
    assertThat(service.status().rejectionRateSeverity()).isEqualTo("SUCCESS");

    // > 1%: 101 / (9900 + 101)
    snapshot(9_900, 101, 0, 0, 100);
    assertThat(service.status().rejectionRateSeverity()).isEqualTo("WARNING");

    // exactly 5%: 100 / (1900 + 100) stays WARNING — 5% is not "> 5%"
    snapshot(1_900, 100, 0, 0, 100);
    assertThat(service.status().rejectionRateSeverity()).isEqualTo("WARNING");

    // > 5%: 6 / (100 + 6)
    snapshot(100, 6, 0, 0, 100);
    assertThat(service.status().rejectionRateSeverity()).isEqualTo("CRITICAL");
    assertThat(service.status().status()).isEqualTo(TelemetryDataQualityState.CRITICAL);
    assertThat(service.status().statusReason()).contains("rejection rate").contains("above 5.0");
  }

  @Test
  void rejectionRateSeverityUsesRawRateNotRoundedDisplay() {
    // raw 1.0008% (13 / 1299) rounds to 1.0 for display but is a genuine >1% breach; the
    // quarantine count (13) is itself only WARNING, so the rate is the deciding metric.
    snapshot(1_286, 13, 0, 0, 100);

    var status = service.status();

    assertThat(status.rejectionRatePct()).isEqualTo(1.0);
    assertThat(status.rejectionRateSeverity()).isEqualTo("WARNING");
    assertThat(status.status()).isEqualTo(TelemetryDataQualityState.DEGRADED);
  }

  @Test
  void windowSecondsReportsEffectiveMinuteGranularityWindow() {
    // A PT90S configured window truncates to a 1-minute tracker window; the payload must
    // report the tracker's effective 60s, not the configured 90s.
    when(tracker.effectiveWindowSeconds()).thenReturn(60L);
    snapshot(10, 0, 0, 0, 100);

    assertThat(service.status().windowSeconds()).isEqualTo(60);
  }

  @Test
  void anomalyCountAboveZeroIsWarning() {
    snapshot(1000, 2, 1, 0, 100);

    var status = service.status();

    assertThat(status.anomalySeverity()).isEqualTo("WARNING");
    assertThat(status.status()).isEqualTo(TelemetryDataQualityState.DEGRADED);
    assertThat(status.statusReason()).contains("anomal");
  }

  @Test
  void deadLetterCountAboveZeroIsCritical() {
    snapshot(1000, 0, 0, 1, 100);

    var status = service.status();

    assertThat(status.deadLetterSeverity()).isEqualTo("CRITICAL");
    assertThat(status.status()).isEqualTo(TelemetryDataQualityState.CRITICAL);
    assertThat(status.statusReason()).contains("dead-lettered");
  }

  @Test
  void latencyBoundariesMatchSm008Bands() {
    snapshot(1000, 0, 0, 0, 4_999);
    assertThat(service.status().latencyState()).isEqualTo(LatencyState.NORMAL);

    snapshot(1000, 0, 0, 0, 5_000);
    assertThat(service.status().latencyState()).isEqualTo(LatencyState.ELEVATED);
    assertThat(service.status().status()).isEqualTo(TelemetryDataQualityState.DEGRADED);

    snapshot(1000, 0, 0, 0, 15_000);
    assertThat(service.status().latencyState()).isEqualTo(LatencyState.ELEVATED);

    snapshot(1000, 0, 0, 0, 15_001);
    assertThat(service.status().latencyState()).isEqualTo(LatencyState.CRITICAL);
    assertThat(service.status().status()).isEqualTo(TelemetryDataQualityState.CRITICAL);
    assertThat(service.status().statusReason()).contains("latency state critical");
  }

  @Test
  void worstSeverityAcrossMetricsWins() {
    // dead-letter (CRITICAL) outranks quarantine count (WARNING) and anomaly (WARNING)
    snapshot(1000, 11, 1, 1, 100);

    var status = service.status();

    assertThat(status.status()).isEqualTo(TelemetryDataQualityState.CRITICAL);
    assertThat(status.statusReason()).contains("quarantine count 11").contains("dead-lettered");
  }

  @Test
  void rateIsRoundedToTwoDecimals() {
    // 2 / 3 * 100 = 66.666... → 66.67
    snapshot(1, 2, 0, 0, 100);

    assertThat(service.status().rejectionRatePct()).isEqualTo(66.67);
  }
}
