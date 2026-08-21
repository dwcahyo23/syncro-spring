package com.syncro.telemetry.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.domain.MachineStatus;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Assembles the per-machine stale-telemetry evidence list for the SUPER_ADMIN health dashboard.
 *
 * <p>An ACTIVE machine is stale evidence when its per-machine freshness (from
 * {@link TelemetryFreshnessCalculator} via {@link LatestTelemetryQueryService}) is
 * {@code OFFLINE} (5–15 min since last accepted telemetry, or none at all) or {@code STALE}
 * (&gt;15 min). {@code ONLINE} machines (≤5 min) are excluded. A machine whose latest state
 * cannot be read (Redis empty or read failure → {@code latestTelemetry} returns {@code null})
 * is reported as never-received OFFLINE evidence, never an error.
 *
 * <p>Cross-module access goes through the machine module's application service
 * ({@link MachineService#list}); no repository from another module is touched.
 */
@Service
public class TelemetryStaleMachineService {

  /**
   * Single bounded page of ACTIVE machines. Phase-1 active-machine count is far below this cap;
   * the evidence panel is SUPER_ADMIN-only and polled every 30s, so revisit pagination only when
   * active-machine volume makes the payload meaningful.
   */
  static final int MACHINE_PAGE_SIZE = 500;

  /** Worst-first: never-received, then stalest lastReceivedAt, then machineCode ascending. */
  private static final Comparator<StaleMachineItem> WORST_FIRST =
      Comparator.comparing(StaleMachineItem::lastReceivedAt,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(StaleMachineItem::machineCode);

  private final MachineService machineService;
  private final LatestTelemetryQueryService telemetryQuery;
  private final Clock clock;

  public TelemetryStaleMachineService(MachineService machineService,
      LatestTelemetryQueryService telemetryQuery, Clock clock) {
    this.machineService = machineService;
    this.telemetryQuery = telemetryQuery;
    this.clock = clock;
  }

  public StaleMachineStatus staleMachines(AuthenticatedUser user) {
    var machines = machineService.list(user, null, null, MachineStatus.ACTIVE, null,
        0, MACHINE_PAGE_SIZE, "code,asc");

    List<StaleMachineItem> items = machines.items().stream()
        .map(this::toStaleItem)
        .flatMap(Optional::stream)
        .sorted(WORST_FIRST)
        .toList();

    return new StaleMachineStatus(clock.instant().toString(), items.size(), items);
  }

  /**
   * Maps one machine to its stale-evidence item, or empty when the machine is ONLINE.
   * {@code null} telemetry means no readable latest state — reported as OFFLINE with a null
   * timestamp (never-received), matching the calculator's no-data semantics.
   */
  private Optional<StaleMachineItem> toStaleItem(MachineService.MachineView machine) {
    LatestTelemetryDto.TelemetryData telemetry = telemetryQuery.latestTelemetry(
        machine.id(), machine.status());
    LatestTelemetryDto.FreshnessState state = telemetry == null
        ? LatestTelemetryDto.FreshnessState.OFFLINE
        : telemetry.freshnessState();
    if (state == LatestTelemetryDto.FreshnessState.ONLINE) {
      return Optional.empty();
    }
    return Optional.of(new StaleMachineItem(
        machine.id(),
        machine.code(),
        machine.plantCode(),
        state.name(),
        state.label(),
        telemetry == null ? null : telemetry.lastReceivedAt()));
  }
}
