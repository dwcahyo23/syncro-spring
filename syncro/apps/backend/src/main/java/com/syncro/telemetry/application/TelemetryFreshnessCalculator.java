package com.syncro.telemetry.application;

import com.syncro.machine.domain.MachineStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class TelemetryFreshnessCalculator {

  private static final Duration ONLINE_THRESHOLD = Duration.ofMinutes(5);
  private static final Duration STALE_THRESHOLD = Duration.ofMinutes(15);

  private final Clock clock;

  public TelemetryFreshnessCalculator(Clock clock) {
    this.clock = clock;
  }

   public LatestTelemetryDto.FreshnessState calculate(Instant receivedAt, MachineStatus manualStatus) {
     if (receivedAt == null) {
       return ManualStatusInactivityDetector.isInactive(manualStatus)
           ? LatestTelemetryDto.FreshnessState.STALE
           : LatestTelemetryDto.FreshnessState.OFFLINE;
     }
     Instant now = clock.instant();
     Duration sinceReceived = Duration.between(receivedAt, now);
     if (ManualStatusInactivityDetector.isInactive(manualStatus)) {
       return LatestTelemetryDto.FreshnessState.STALE;
     }
     if (sinceReceived.compareTo(ONLINE_THRESHOLD) <= 0) {
       return LatestTelemetryDto.FreshnessState.ONLINE;
     } else if (sinceReceived.compareTo(STALE_THRESHOLD) <= 0) {
       return LatestTelemetryDto.FreshnessState.OFFLINE;
     } else {
       return LatestTelemetryDto.FreshnessState.STALE;
     }
   }

  public record FreshnessSnapshot(
      Instant now,
      Instant receivedAt,
      Duration sinceReceived,
      LatestTelemetryDto.FreshnessState state) {
  }

  public static class ManualStatusInactivityDetector {
    private ManualStatusInactivityDetector() {
    }

    public static boolean isInactive(MachineStatus status) {
      return status == MachineStatus.INACTIVE;
    }
  }
}
