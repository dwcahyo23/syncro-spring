package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.config.TelemetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelemetryFreshnessServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

  private final TelemetryIngestTracker tracker = new TelemetryIngestTracker(FIXED_CLOCK);

  private TelemetryFreshnessService service() {
    return new TelemetryFreshnessService(tracker, properties(), FIXED_CLOCK);
  }

  private static TelemetryProperties properties() {
    return new TelemetryProperties(
        Duration.ofMinutes(5),
        Duration.ofSeconds(30),
        new TelemetryProperties.Ingest(1000, 2, STALE_THRESHOLD),
        new TelemetryProperties.DataQuality(Duration.ofHours(1)));
  }

  @Test
  void noTelemetryReportsNoDataWithEmptyStateReasonAndNullFields() {
    TelemetryFreshnessStatus status = service().freshness();

    assertThat(status.status()).isEqualTo(TelemetryFreshnessState.NO_DATA);
    assertThat(status.statusLabel()).isEqualTo("No data");
    assertThat(status.statusSeverity()).isEqualTo("NEUTRAL");
    assertThat(status.statusReason()).isEqualTo(TelemetryFreshnessService.NO_DATA_REASON);
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.lastAcceptedAt()).isNull();
    assertThat(status.staleSince()).isNull();
  }

  @Test
  void freshTelemetryReportsLive() {
    tracker.recordAccepted();

    TelemetryFreshnessStatus status = service().freshness();

    assertThat(status.status()).isEqualTo(TelemetryFreshnessState.LIVE);
    assertThat(status.statusLabel()).isEqualTo("Live");
    assertThat(status.statusSeverity()).isEqualTo("SUCCESS");
    assertThat(status.statusReason()).isNull();
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.lastAcceptedAt()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.staleSince()).isNull();
  }

  @Test
  void exactlyAtStaleThresholdIsStillLive() {
    TelemetryIngestTracker boundaryTracker = new TelemetryIngestTracker(
        Clock.fixed(FIXED_NOW.minus(STALE_THRESHOLD), ZoneOffset.UTC));
    boundaryTracker.recordAccepted();
    TelemetryFreshnessService boundaryService = new TelemetryFreshnessService(
        boundaryTracker, properties(), FIXED_CLOCK);

    TelemetryFreshnessStatus status = boundaryService.freshness();

    assertThat(status.status()).isEqualTo(TelemetryFreshnessState.LIVE);
    assertThat(status.statusLabel()).isEqualTo("Live");
    assertThat(status.statusSeverity()).isEqualTo("SUCCESS");
    assertThat(status.statusReason()).isNull();
    assertThat(status.lastAcceptedAt()).isEqualTo(FIXED_NOW.minus(STALE_THRESHOLD).toString());
    assertThat(status.staleSince()).isNull();
  }

  @Test
  void futureLastAcceptedAtReportsLiveNotStale() {
    TelemetryIngestTracker skewTracker = new TelemetryIngestTracker(
        Clock.fixed(FIXED_NOW.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
    skewTracker.recordAccepted();
    TelemetryFreshnessService skewService = new TelemetryFreshnessService(
        skewTracker, properties(), FIXED_CLOCK);

    TelemetryFreshnessStatus status = skewService.freshness();

    assertThat(status.status()).isEqualTo(TelemetryFreshnessState.LIVE);
    assertThat(status.statusLabel()).isEqualTo("Live");
    assertThat(status.statusSeverity()).isEqualTo("SUCCESS");
    assertThat(status.statusReason()).isNull();
    assertThat(status.lastAcceptedAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(10)).toString());
    assertThat(status.staleSince()).isNull();
  }

  @Test
  void beyondStaleThresholdReportsStaleWithReasonAndStaleSince() {
    TelemetryIngestTracker staleTracker = new TelemetryIngestTracker(
        Clock.fixed(FIXED_NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC));
    staleTracker.recordAccepted();
    TelemetryFreshnessService staleService = new TelemetryFreshnessService(
        staleTracker, properties(), FIXED_CLOCK);

    TelemetryFreshnessStatus status = staleService.freshness();

    assertThat(status.status()).isEqualTo(TelemetryFreshnessState.STALE);
    assertThat(status.statusLabel()).isEqualTo("Stale");
    assertThat(status.statusSeverity()).isEqualTo("WARNING");
    assertThat(status.statusReason())
        .contains("No telemetry accepted since")
        .contains(FIXED_NOW.minus(Duration.ofMinutes(10)).toString());
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
    assertThat(status.lastAcceptedAt()).isEqualTo(FIXED_NOW.minus(Duration.ofMinutes(10)).toString());
    assertThat(status.staleSince()).isEqualTo(FIXED_NOW.minus(Duration.ofMinutes(5)).toString());
  }
}
