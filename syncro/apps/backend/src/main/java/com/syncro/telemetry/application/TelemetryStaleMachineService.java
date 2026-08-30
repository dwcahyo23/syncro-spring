package com.syncro.telemetry.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.domain.MachineStatus;
import java.time.Clock;
import java.util.ArrayList;
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
   * Page size for the ACTIVE-machine listing. {@code MachineService} rejects sizes above its
   * {@code MAX_PAGE_SIZE = 200} with {@code MachineValidationException}, so this must stay
   * within that contract; the service iterates pages until every ACTIVE machine is covered.
   */
  static final int MACHINE_PAGE_SIZE = 200;

  /**
   * Hard safety cap on listing iterations (25 × 200 = 5,000 ACTIVE machines). Phase-1 scale is
   * far below this; if a fleet ever exceeds it, the evidence covers the first 5,000 machines
   * and pagination support should be added to the endpoint instead.
   */
  private static final int MAX_MACHINE_PAGES = 25;

  /** Worst-first: never-received, then stalest lastReceivedAt, then machineCode, then id. */
  private static final Comparator<StaleMachineItem> WORST_FIRST =
      Comparator.comparing(StaleMachineItem::lastReceivedAt,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(StaleMachineItem::machineCode)
          .thenComparing(StaleMachineItem::machineId);

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
    List<MachineService.MachineView> machines = listAllActiveMachines(user);

    List<StaleMachineItem> items = machines.stream()
        .map(this::toStaleItem)
        .flatMap(Optional::stream)
        .sorted(WORST_FIRST)
        .toList();

    return new StaleMachineStatus(clock.instant().toString(), items.size(), items);
  }

  /** Iterates ACTIVE-machine pages until {@code totalElements} is covered (bounded by the page cap). */
  private List<MachineService.MachineView> listAllActiveMachines(AuthenticatedUser user) {
    List<MachineService.MachineView> machines = new ArrayList<>();
    long totalElements = 0;
    for (int page = 0; page < MAX_MACHINE_PAGES; page++) {
      var view = machineService.list(user, null, null, MachineStatus.ACTIVE, null,
          page, MACHINE_PAGE_SIZE, "code,asc");
      machines.addAll(view.items());
      totalElements = view.totalElements();
      if (view.items().isEmpty() || machines.size() >= totalElements) {
        break;
      }
    }
    return machines;
  }

  /**
   * Maps one machine to its stale-evidence item, or empty when the machine is ONLINE.
   * {@code null} telemetry means no readable latest state — reported as OFFLINE with a null
   * timestamp (never-received), matching the calculator's no-data semantics.
   */
  private Optional<StaleMachineItem> toStaleItem(MachineService.MachineView machine) {
    LatestTelemetryDto.TelemetryData telemetry = telemetryQuery.latestTelemetry(
        machine.id(), machine.status());
    // DW-70: a Redis outage must not masquerade as "machine never sent telemetry". When the
    // read itself failed, surface it as a distinct evidence state instead of a false
    // never-received fleet.
    if (telemetry != null && telemetry.readFailure()) {
      return Optional.of(new StaleMachineItem(
          machine.id(),
          machine.code(),
          machine.plantCode(),
          "READ_FAILURE",
          "Latest telemetry state could not be read (Redis unavailable)",
          null,
          true));
    }
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
        telemetry == null ? null : telemetry.lastReceivedAt(),
        false));
  }
}
