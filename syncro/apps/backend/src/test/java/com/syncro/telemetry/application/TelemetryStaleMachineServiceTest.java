package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.application.MachineService;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.telemetry.application.LatestTelemetryDto.FreshnessState;
import com.syncro.telemetry.application.LatestTelemetryDto.TelemetryData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelemetryStaleMachineServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-08-21T08:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  @Mock
  private MachineService machineService;

  @Mock
  private LatestTelemetryQueryService telemetryQuery;

  private final AuthenticatedUser superAdmin = new AuthenticatedUser(
      UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

  private TelemetryStaleMachineService service;

  @BeforeEach
  void setUp() {
    service = new TelemetryStaleMachineService(machineService, telemetryQuery, FIXED_CLOCK);
  }

  @Test
  void offlineMachineIsIncluded() {
    UUID machineId = UUID.randomUUID();
    stubMachines(machine(machineId, "BF-08410", "GM1"));
    Instant tenMinutesAgo = FIXED_NOW.minusSeconds(600);
    stubTelemetry(machineId, telemetry(tenMinutesAgo, FreshnessState.OFFLINE));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    assertThat(status.staleMachineCount()).isEqualTo(1);
    assertThat(status.items()).hasSize(1);
    assertThat(status.items().get(0).machineCode()).isEqualTo("BF-08410");
    assertThat(status.items().get(0).plantCode()).isEqualTo("GM1");
    assertThat(status.items().get(0).freshnessState()).isEqualTo("OFFLINE");
    assertThat(status.items().get(0).statusLabel()).isEqualTo("offline");
    assertThat(status.items().get(0).lastReceivedAt()).isEqualTo(tenMinutesAgo);
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
  }

  @Test
  void staleMachineIsIncluded() {
    UUID machineId = UUID.randomUUID();
    stubMachines(machine(machineId, "JBF19", "GM1"));
    stubTelemetry(machineId, telemetry(FIXED_NOW.minusSeconds(1200), FreshnessState.STALE));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    assertThat(status.items()).extracting(StaleMachineItem::freshnessState).containsExactly("STALE");
  }

  @Test
  void onlineMachineIsExcluded() {
    UUID machineId = UUID.randomUUID();
    stubMachines(machine(machineId, "BF-08410", "GM1"));
    stubTelemetry(machineId, telemetry(FIXED_NOW.minusSeconds(120), FreshnessState.ONLINE));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    assertThat(status.staleMachineCount()).isZero();
    assertThat(status.items()).isEmpty();
  }

  @Test
  void neverReceivedMachineIsReportedOfflineWithNullTimestampSortedFirst() {
    UUID neverReceivedId = UUID.randomUUID();
    UUID staleId = UUID.randomUUID();
    // Reverse insertion order (stale first) so the sort assertion proves reordering.
    stubMachines(machine(staleId, "ZZ-01", "GM1"), machine(neverReceivedId, "AA-01", "GM1"));
    stubTelemetry(neverReceivedId, null);
    stubTelemetry(staleId, telemetry(FIXED_NOW.minusSeconds(1200), FreshnessState.STALE));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    assertThat(status.items()).hasSize(2);
    StaleMachineItem first = status.items().get(0);
    assertThat(first.machineCode()).isEqualTo("AA-01");
    assertThat(first.freshnessState()).isEqualTo("OFFLINE");
    assertThat(first.lastReceivedAt()).isNull();
    assertThat(status.items().get(1).machineCode()).isEqualTo("ZZ-01");
  }

  @Test
  void sortIsWorstFirstThenStalestThenCode() {
    UUID oldestId = UUID.randomUUID();
    UUID newerId = UUID.randomUUID();
    UUID sameAgeA = UUID.randomUUID();
    UUID sameAgeB = UUID.randomUUID();
    stubMachines(
        machine(newerId, "NEW-1", "GM1"),
        machine(sameAgeB, "SAME-B", "GM1"),
        machine(oldestId, "OLD-1", "GM1"),
        machine(sameAgeA, "SAME-A", "GM1"));
    stubTelemetry(oldestId, telemetry(FIXED_NOW.minusSeconds(1800), FreshnessState.STALE));
    stubTelemetry(sameAgeA, telemetry(FIXED_NOW.minusSeconds(600), FreshnessState.OFFLINE));
    stubTelemetry(sameAgeB, telemetry(FIXED_NOW.minusSeconds(600), FreshnessState.OFFLINE));
    stubTelemetry(newerId, telemetry(FIXED_NOW.minusSeconds(360), FreshnessState.OFFLINE));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    assertThat(status.items()).extracting(StaleMachineItem::machineCode)
        .containsExactly("OLD-1", "SAME-A", "SAME-B", "NEW-1");
  }

  @Test
  void noActiveMachinesYieldsEmptyEvidence() {
    when(machineService.list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(0), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc")))
        .thenReturn(new MachineService.MachineListView(List.of(), 0, 0,
            TelemetryStaleMachineService.MACHINE_PAGE_SIZE, "code,asc"));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    assertThat(status.staleMachineCount()).isZero();
    assertThat(status.items()).isEmpty();
    assertThat(status.timestamp()).isEqualTo(FIXED_NOW.toString());
  }

  @Test
  void iteratesPagesUntilTotalElementsCovered() {
    UUID pageOneId = UUID.randomUUID();
    UUID pageTwoId = UUID.randomUUID();
    when(machineService.list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(0), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc")))
        .thenReturn(new MachineService.MachineListView(
            List.of(machine(pageOneId, "OLD-1", "GM1")), 2, 0,
            TelemetryStaleMachineService.MACHINE_PAGE_SIZE, "code,asc"));
    when(machineService.list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(1), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc")))
        .thenReturn(new MachineService.MachineListView(
            List.of(machine(pageTwoId, "NEW-1", "GM1")), 2, 1,
            TelemetryStaleMachineService.MACHINE_PAGE_SIZE, "code,asc"));
    stubTelemetry(pageOneId, telemetry(FIXED_NOW.minusSeconds(1800), FreshnessState.STALE));
    stubTelemetry(pageTwoId, telemetry(FIXED_NOW.minusSeconds(360), FreshnessState.OFFLINE));

    StaleMachineStatus status = service.staleMachines(superAdmin);

    // Both pages' machines are freshness-checked; the count is the fleet count, not page 0's.
    assertThat(status.staleMachineCount()).isEqualTo(2);
    assertThat(status.items()).extracting(StaleMachineItem::machineCode)
        .containsExactly("OLD-1", "NEW-1");
    verify(machineService).list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(1), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc"));
  }

  @Test
  void stopsWhenAPageComesBackEmpty() {
    when(machineService.list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(0), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc")))
        .thenReturn(new MachineService.MachineListView(List.of(), 0, 0,
            TelemetryStaleMachineService.MACHINE_PAGE_SIZE, "code,asc"));

    service.staleMachines(superAdmin);

    verify(machineService).list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(0), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc"));
    verifyNoMoreInteractions(machineService);
  }

  @Test
  void queriesOnlyActiveMachinesWithinTheMachineServicePageSizeContract() {
    stubMachines();

    service.staleMachines(superAdmin);

    // 200 is MachineService's MAX_PAGE_SIZE; larger sizes throw MachineValidationException.
    assertThat(TelemetryStaleMachineService.MACHINE_PAGE_SIZE).isLessThanOrEqualTo(200);
    verify(machineService).list(superAdmin, null, null, MachineStatus.ACTIVE, null, 0,
        TelemetryStaleMachineService.MACHINE_PAGE_SIZE, "code,asc");
  }

  private void stubMachines(MachineService.MachineView... machines) {
    when(machineService.list(any(), isNull(), isNull(), eq(MachineStatus.ACTIVE), isNull(),
        eq(0), eq(TelemetryStaleMachineService.MACHINE_PAGE_SIZE), eq("code,asc")))
        .thenReturn(new MachineService.MachineListView(List.of(machines), machines.length, 0,
            TelemetryStaleMachineService.MACHINE_PAGE_SIZE, "code,asc"));
  }

  private void stubTelemetry(UUID machineId, TelemetryData telemetry) {
    when(telemetryQuery.latestTelemetry(machineId, MachineStatus.ACTIVE)).thenReturn(telemetry);
  }

  private static MachineService.MachineView machine(UUID id, String code, String plantCode) {
    return new MachineService.MachineView(id, UUID.randomUUID(), plantCode, "Plant " + plantCode,
        UUID.randomUUID(), "Forming", code, "Machine " + code, MachineStatus.ACTIVE, null, null,
        null, FIXED_NOW, FIXED_NOW, List.of());
  }

  private static TelemetryData telemetry(Instant lastReceivedAt, FreshnessState state) {
    return new TelemetryData(UUID.randomUUID(), true, 1.0, 10L, lastReceivedAt, state,
        Map.of(), false);
  }
}
