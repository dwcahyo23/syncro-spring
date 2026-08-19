package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influxdb.client.write.Point;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.config.TelemetryProperties;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.telemetry.infrastructure.InfluxTelemetryWriter;
import com.syncro.telemetry.infrastructure.RedisLatestTelemetryWriter;
import com.syncro.alert.application.SparepartAlertService;
import com.syncro.sparepart.application.SparepartLifetimeEvaluator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class TelemetryPersistenceServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");
  private static final String TRACE_ID = "trace-persist-1";

  @Mock
  private MachineRepository machines;
  @Mock
  private InfluxTelemetryWriter influxWriter;
  @Mock
  private RedisLatestTelemetryWriter redisLatestWriter;
  @Mock
  private StringRedisTemplate redis;
  @Mock
  private ValueOperations<String, String> valueOps;
  @Mock
  private SparepartLifetimeEvaluator evaluator;
  @Mock
  private SparepartAlertService alertService;

  private MachineEntity machine;
  private TelemetryPersistenceService service;
  private TelemetryValidationService.Result.Accepted accepted;
  private TelemetryEnvelope envelope;
  private String dedupeKey;

  @BeforeEach
  void setUp() {
    var plant = new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", NOW, NOW);
    var group = new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", NOW, NOW);
    machine = new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19", MachineStatus.ACTIVE, "Juki",
        LocalDate.parse("2026-05-27"), null, List.of(), NOW, NOW);
    var payload = new TelemetryPayload(true, 12.5, 100, "1.0", "msg-persist-1", Instant.parse("2026-08-14T09:30:00Z"));
    accepted = new TelemetryValidationService.Result.Accepted(machine, payload);
    envelope = new TelemetryEnvelope(TRACE_ID, "factory/GM1/BF-08410/telemetry", "{}", NOW);
    dedupeKey = "syncro:machine:" + machine.getId() + ":telemetry:dedupe:msg-persist-1";
    service = new TelemetryPersistenceService(machines, influxWriter, redisLatestWriter, redis,
        new TelemetryProperties(Duration.parse("PT5M"), Duration.parse("PT30S"),
            new TelemetryProperties.Ingest(1000, 2)), evaluator, alertService);
    when(machines.findByIdWithPlantAndGroup(machine.getId())).thenReturn(Optional.of(machine));
    lenient().when(redis.opsForValue()).thenReturn(valueOps);
  }

  @Test
  void firstMessageProceedsAndDerivesPlantFromRefetchedMachine() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);

    service.persist(accepted, envelope);

    verify(machines).findByIdWithPlantAndGroup(machine.getId());
    verify(influxWriter).write(any(Point.class), anyString(), anyString());
    verify(redisLatestWriter).putLatest(any(UUID.class), any(), any(Duration.class));
  }

  @Test
  void duplicateMessageSkipsBothWritesAndLogsDuplicate() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(false);
    when(valueOps.get(dedupeKey)).thenReturn("trace-original-winner");
    var appender = attachAppender();

    service.persist(accepted, envelope);

    assertThat(appender.list)
        .anyMatch(event -> event.getLevel() == Level.WARN
            && event.getFormattedMessage().startsWith("mqtt_telemetry_duplicate")
            && event.getFormattedMessage().contains("traceId=" + TRACE_ID)
            && event.getFormattedMessage().contains("machineCode=BF-08410")
            && event.getFormattedMessage().contains("messageId=msg-persist-1")
            && event.getFormattedMessage().contains("winnerTraceId=trace-original-winner"));
    verify(influxWriter, never()).write(any(Point.class), anyString(), anyString());
    verify(redisLatestWriter, never()).putLatest(any(UUID.class), any(), any(Duration.class));
    detachAppender(appender);
  }

  @Test
  void sameMessageIdDifferentCountingIsStillDeduplicated() {
    var differentCounting = new TelemetryValidationService.Result.Accepted(machine,
        new TelemetryPayload(true, 12.5, 999, "1.0", "msg-persist-1", Instant.parse("2026-08-14T09:30:00Z")));
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(false);
    when(valueOps.get(dedupeKey)).thenReturn("trace-original-winner");

    service.persist(differentCounting, envelope);

    verify(influxWriter, never()).write(any(Point.class), anyString(), anyString());
    verify(redisLatestWriter, never()).putLatest(any(UUID.class), any(), any(Duration.class));
  }

  @Test
  void influxFailureDeletesOwnedDedupeKeyAndRethrows() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    when(valueOps.get(dedupeKey)).thenReturn(TRACE_ID);
    doThrow(new RuntimeException("influx down")).when(influxWriter).write(any(Point.class), anyString(), anyString());

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("influx down");

    verify(redis).delete(dedupeKey);
    verify(redisLatestWriter, never()).putLatest(any(UUID.class), any(), any(Duration.class));
  }

  @Test
  void redisLatestFailureRethrowsButKeepsDedupeGate() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    doThrow(new RuntimeException("redis down")).when(redisLatestWriter)
        .putLatest(any(UUID.class), any(), any(Duration.class));

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("redis down");

    verify(redis, never()).delete(dedupeKey);
  }

  @Test
  void failureDoesNotDeleteDedupeKeyOwnedByAnotherMessage() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    when(valueOps.get(dedupeKey)).thenReturn("other-trace");
    doThrow(new RuntimeException("influx down")).when(influxWriter).write(any(Point.class), anyString(), anyString());

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("influx down");

    verify(redis, never()).delete(dedupeKey);
  }

  @Test
  void cleanupFailureDoesNotMaskOriginalException() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    when(valueOps.get(dedupeKey)).thenReturn(TRACE_ID);
    doThrow(new RuntimeException("influx down")).when(influxWriter).write(any(Point.class), anyString(), anyString());
    doThrow(new RuntimeException("cleanup down")).when(redis).delete(dedupeKey);

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("influx down");
  }

  @Test
  void nullDedupeAcquisitionThrowsInsteadOfTreatingAsDuplicate() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(null);

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dedupe gate unavailable");

    verify(influxWriter, never()).write(any(Point.class), anyString(), anyString());
    verify(redisLatestWriter, never()).putLatest(any(UUID.class), any(), any(Duration.class));
  }

  @Test
  void machineVanishedBetweenValidationAndPersistenceThrowsAndWritesNothing() {
    when(machines.findByIdWithPlantAndGroup(machine.getId())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("machine no longer exists");

    verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any());
    verify(influxWriter, never()).write(any(Point.class), anyString(), anyString());
    verify(redisLatestWriter, never()).putLatest(any(UUID.class), any(), any(Duration.class));
  }

  @Test
  void firstSampleComputesDeltaZeroAndWritesCountingDeltaField() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);

    service.persist(accepted, envelope);

    verify(redisLatestWriter).readCounting(machine.getId());
    var pointCaptor = ArgumentCaptor.forClass(Point.class);
    verify(influxWriter).write(pointCaptor.capture(), anyString(), anyString());
    assertThat(pointCaptor.getValue().toLineProtocol()).contains("countingDelta=0i");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(redisLatestWriter).putLatest(any(UUID.class), fieldsCaptor.capture(), any(Duration.class));
    assertThat(fieldsCaptor.getValue()).containsEntry("countingDelta", "0");
  }

  @Test
  void readCountingFailureDeletesOwnedDedupeKeyAndRethrows() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    when(valueOps.get(dedupeKey)).thenReturn(TRACE_ID);
    when(redisLatestWriter.readCounting(machine.getId())).thenThrow(new RuntimeException("redis hiccup"));

    assertThatThrownBy(() -> service.persist(accepted, envelope))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("redis hiccup");

    verify(redis).delete(dedupeKey);
    verify(influxWriter, never()).write(any(Point.class), anyString(), anyString());
    verify(redisLatestWriter, never()).putLatest(any(UUID.class), any(), any(Duration.class));
  }

  @Test
  void wrapFromMaxToZeroComputesDeltaOne() {
    var countingZero = acceptedWithCounting(0);
    String countingZeroDedupeKey = dedupeKeyFor(0);
    when(valueOps.setIfAbsent(countingZeroDedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    when(redisLatestWriter.readCounting(machine.getId())).thenReturn(Optional.of(65535L));

    service.persist(countingZero, envelope);

    var pointCaptor = ArgumentCaptor.forClass(Point.class);
    verify(influxWriter).write(pointCaptor.capture(), anyString(), anyString());
    assertThat(pointCaptor.getValue().toLineProtocol()).contains("countingDelta=1i");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(redisLatestWriter).putLatest(any(UUID.class), fieldsCaptor.capture(), any(Duration.class));
    assertThat(fieldsCaptor.getValue()).containsEntry("countingDelta", "1");
  }

  @Test
  void increaseComputesDirectDelta() {
    var countingTwoHundred = acceptedWithCounting(200);
    String countingTwoHundredDedupeKey = dedupeKeyFor(200);
    when(valueOps.setIfAbsent(countingTwoHundredDedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    when(redisLatestWriter.readCounting(machine.getId())).thenReturn(Optional.of(100L));

    service.persist(countingTwoHundred, envelope);

    var pointCaptor = ArgumentCaptor.forClass(Point.class);
    verify(influxWriter).write(pointCaptor.capture(), anyString(), anyString());
    assertThat(pointCaptor.getValue().toLineProtocol()).contains("countingDelta=100i");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(redisLatestWriter).putLatest(any(UUID.class), fieldsCaptor.capture(), any(Duration.class));
    assertThat(fieldsCaptor.getValue()).containsEntry("countingDelta", "100");
  }

  @Test
  void optionalFieldsFlowIntoPointAndLatestHash() {
    accepted = new TelemetryValidationService.Result.Accepted(machine, payloadWithOptionalFields());
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    service.persist(accepted, envelope);

    var pointCaptor = ArgumentCaptor.forClass(Point.class);
    verify(influxWriter).write(pointCaptor.capture(), anyString(), anyString());
    assertThat(pointCaptor.getValue().toLineProtocol())
        .contains("vibration=2.4")
        .contains("rpm=1200i")
        .contains("heaterOn=true")
        .contains("qualityGrade=\"A\"");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(redisLatestWriter).putLatest(any(UUID.class), fieldsCaptor.capture(), any(Duration.class));
    assertThat(fieldsCaptor.getValue())
        .containsEntry("optional.vibration", "2.4")
        .containsEntry("optional.rpm", "1200")
        .containsEntry("optional.heaterOn", "true")
        .containsEntry("optional.qualityGrade", "A");
  }

  private TelemetryPayload payloadWithOptionalFields() {
    var nodeFactory = new ObjectMapper().getNodeFactory();
    var optional = new LinkedHashMap<String, JsonNode>();
    optional.put("vibration", nodeFactory.numberNode(2.4));
    optional.put("rpm", nodeFactory.numberNode(1200));
    optional.put("heaterOn", nodeFactory.booleanNode(true));
    optional.put("qualityGrade", nodeFactory.textNode("A"));
    return new TelemetryPayload(true, 12.5, 100, "1.0", "msg-persist-1", Instant.parse("2026-08-14T09:30:00Z"),
        Collections.unmodifiableMap(optional));
  }

  private TelemetryValidationService.Result.Accepted acceptedWithCounting(long counting) {
    return new TelemetryValidationService.Result.Accepted(machine,
        new TelemetryPayload(true, 12.5, counting, "1.0", "msg-count-" + counting,
            Instant.parse("2026-08-14T09:30:00Z")));
  }

  private String dedupeKeyFor(long counting) {
    return "syncro:machine:" + machine.getId() + ":telemetry:dedupe:msg-count-" + counting;
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(TelemetryPersistenceService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    Logger logger = (Logger) LoggerFactory.getLogger(TelemetryPersistenceService.class);
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  void persist_callsEvaluatorAfterRedisWrite() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);

    service.persist(accepted, envelope);

    var order = inOrder(redisLatestWriter, evaluator);
    order.verify(redisLatestWriter).putLatest(any(), any(), any());
    order.verify(evaluator).evaluateAll(machine.getId());
  }

  @Test
  void persist_evaluatorException_doesNotPropagate() {
    when(valueOps.setIfAbsent(dedupeKey, TRACE_ID, Duration.parse("PT30S"))).thenReturn(true);
    doThrow(new RuntimeException("evaluator failure")).when(evaluator).evaluateAll(machine.getId());

    // should complete without throwing
    service.persist(accepted, envelope);

    verify(evaluator).evaluateAll(machine.getId());
  }
}
