package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syncro.machine.domain.MachineStatus;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LatestTelemetryQueryServiceTest {

  private static final UUID MACHINE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
  private static final Instant NOW = Instant.parse("2026-08-10T05:00:00Z");

  private final RedisLatestTelemetryWriter redisWriter = mock(RedisLatestTelemetryWriter.class);

  private LatestTelemetryQueryService service;

  @BeforeEach
  void setUp() {
    TelemetryFreshnessCalculator calculator = new TelemetryFreshnessCalculator(Clock.fixed(NOW, ZoneOffset.UTC));
    service = new LatestTelemetryQueryService(redisWriter, calculator);
  }

  @Test
  @DisplayName("3-7-QUERY-001 full latest hash maps to telemetry data with optional fields")
  void fullLatestHashMapsToTelemetryData() {
    Map<String, String> latest = new LinkedHashMap<>();
    latest.put("machineId", MACHINE_ID.toString());
    latest.put("running", "true");
    latest.put("runtimeHours", "12.5");
    latest.put("counting", "4200");
    latest.put("receivedAt", NOW.minusSeconds(60).toString());
    latest.put("traceId", "trace-1");
    latest.put("optional.vibration", "0.42");
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenReturn(latest);

    LatestTelemetryDto.TelemetryData telemetry = service.latestTelemetry(MACHINE_ID, MachineStatus.ACTIVE);

    assertThat(telemetry).isNotNull();
    assertThat(telemetry.machineId()).isEqualTo(MACHINE_ID);
    assertThat(telemetry.running()).isTrue();
    assertThat(telemetry.runtimeHours()).isEqualTo(12.5);
    assertThat(telemetry.counting()).isEqualTo(4200L);
    assertThat(telemetry.freshnessState()).isEqualTo(LatestTelemetryDto.FreshnessState.ONLINE);
    assertThat(telemetry.hasOptionalFields()).isTrue();
    assertThat(telemetry.optionalFields()).containsExactly(Map.entry("vibration", "0.42"));
  }

  @Test
  @DisplayName("3-7-QUERY-002 empty redis hash returns null")
  void emptyRedisHashReturnsNull() {
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenReturn(Map.of());

    assertThat(service.latestTelemetry(MACHINE_ID, MachineStatus.ACTIVE)).isNull();
  }

  @Test
  @DisplayName("3-7-QUERY-003 malformed receivedAt returns null")
  void malformedReceivedAtReturnsNull() {
    Map<String, String> latest = new LinkedHashMap<>();
    latest.put("receivedAt", "not-a-timestamp");
    latest.put("running", "true");
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenReturn(latest);

    assertThat(service.latestTelemetry(MACHINE_ID, MachineStatus.ACTIVE)).isNull();
  }

  @Test
  @DisplayName("3-7-QUERY-004 missing receivedAt returns null")
  void missingReceivedAtReturnsNull() {
    Map<String, String> latest = new LinkedHashMap<>();
    latest.put("running", "true");
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenReturn(latest);

    assertThat(service.latestTelemetry(MACHINE_ID, MachineStatus.ACTIVE)).isNull();
  }

  @Test
  @DisplayName("3-7-QUERY-005 malformed numeric fields degrade to null instead of failing")
  void malformedNumericFieldsDegradeToNull() {
    Map<String, String> latest = new LinkedHashMap<>();
    latest.put("receivedAt", NOW.minusSeconds(60).toString());
    latest.put("running", "not-a-boolean");
    latest.put("runtimeHours", "garbage");
    latest.put("counting", "garbage");
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenReturn(latest);

    LatestTelemetryDto.TelemetryData telemetry = service.latestTelemetry(MACHINE_ID, MachineStatus.ACTIVE);

    assertThat(telemetry).isNotNull();
    assertThat(telemetry.running()).isFalse();
    assertThat(telemetry.runtimeHours()).isNull();
    assertThat(telemetry.counting()).isNull();
    assertThat(telemetry.freshnessState()).isEqualTo(LatestTelemetryDto.FreshnessState.ONLINE);
  }

  @Test
  @DisplayName("3-7-QUERY-006 redis read failure returns null instead of propagating")
  void redisReadFailureReturnsNull() {
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenThrow(new IllegalStateException("redis down"));

    assertThat(service.latestTelemetry(MACHINE_ID, MachineStatus.ACTIVE)).isNull();
  }

  @Test
  @DisplayName("3-7-QUERY-007 stale receivedAt with INACTIVE status produces STALE")
  void staleReceivedAtWithInactiveStatusProducesStale() {
    Map<String, String> latest = new LinkedHashMap<>();
    latest.put("receivedAt", NOW.minusSeconds(60 * 20).toString());
    when(redisWriter.readLatestAsMap(MACHINE_ID)).thenReturn(latest);

    LatestTelemetryDto.TelemetryData telemetry = service.latestTelemetry(MACHINE_ID, MachineStatus.INACTIVE);

    assertThat(telemetry).isNotNull();
    assertThat(telemetry.freshnessState()).isEqualTo(LatestTelemetryDto.FreshnessState.STALE);
    assertThat(telemetry.hasOptionalFields()).isFalse();
    assertThat(telemetry.optionalFields()).isEmpty();
  }

  // --- DW-33: batched hydration ---

  @Test
  @DisplayName("DW-33 batch parses each present hash with single-path semantics")
  void batchParsesPresentHashes() {
    Map<String, String> first = new LinkedHashMap<>();
    first.put("receivedAt", NOW.minusSeconds(60).toString());
    first.put("running", "true");
    first.put("runtimeHours", "12.5");
    first.put("counting", "4200");
    UUID secondId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    Map<String, String> second = new LinkedHashMap<>();
    second.put("receivedAt", NOW.minusSeconds(60).toString());
    second.put("running", "false");

    when(redisWriter.readLatestBatch(java.util.Set.of(MACHINE_ID, secondId)))
        .thenReturn(Map.of(MACHINE_ID, first, secondId, second));

    var result = service.latestTelemetryBatch(
        Map.of(MACHINE_ID, MachineStatus.ACTIVE, secondId, MachineStatus.ACTIVE));

    assertThat(result).containsKeys(MACHINE_ID, secondId);
    assertThat(result.get(MACHINE_ID).counting()).isEqualTo(4200L);
    assertThat(result.get(MACHINE_ID).hasOptionalFields()).isFalse();
    assertThat(result.get(secondId).running()).isFalse();
  }

  @Test
  @DisplayName("DW-33 machines with absent hashes are missing from the batch result only")
  void batchSkipsAbsentHashes() {
    UUID presentId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    Map<String, String> present = new LinkedHashMap<>();
    present.put("receivedAt", NOW.minusSeconds(60).toString());

    when(redisWriter.readLatestBatch(java.util.Set.of(MACHINE_ID, presentId)))
        .thenReturn(Map.of(presentId, present));

    var result = service.latestTelemetryBatch(
        Map.of(MACHINE_ID, MachineStatus.ACTIVE, presentId, MachineStatus.ACTIVE));

    assertThat(result).containsOnlyKeys(presentId);
  }

  @Test
  @DisplayName("DW-33 batch redis failure degrades whole page to empty map")
  void batchRedisFailureReturnsEmptyMap() {
    when(redisWriter.readLatestBatch(java.util.Set.of(MACHINE_ID)))
        .thenThrow(new RuntimeException("redis down"));

    var result = service.latestTelemetryBatch(Map.of(MACHINE_ID, MachineStatus.ACTIVE));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("DW-33 null statuses map yields empty result")
  void batchNullStatusesYieldsEmptyResult() {
    assertThat(service.latestTelemetryBatch(null)).isEmpty();
  }

  @Test
  @DisplayName("DW-33 malformed receivedAt omits only that machine from batch")
  void batchOmitsMalformedMachineOnly() {
    UUID goodId = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
    Map<String, String> malformed = new LinkedHashMap<>();
    malformed.put("receivedAt", "not-a-timestamp");
    Map<String, String> good = new LinkedHashMap<>();
    good.put("receivedAt", NOW.minusSeconds(60).toString());

    when(redisWriter.readLatestBatch(java.util.Set.of(MACHINE_ID, goodId)))
        .thenReturn(Map.of(MACHINE_ID, malformed, goodId, good));

    var result = service.latestTelemetryBatch(
        Map.of(MACHINE_ID, MachineStatus.ACTIVE, goodId, MachineStatus.ACTIVE));

    assertThat(result).containsOnlyKeys(goodId);
  }
}
