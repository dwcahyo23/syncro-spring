package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.syncro.telemetry.infrastructure.TelemetryQuarantineEntity;
import com.syncro.telemetry.infrastructure.TelemetryQuarantineRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelemetryQuarantineServiceTest {

  private static final Instant RECEIVED_AT = Instant.parse("2026-08-14T10:00:00Z");

  @Mock
  private TelemetryQuarantineRepository repository;

  private TelemetryQuarantineService service;

  @BeforeEach
  void setUp() {
    service = new TelemetryQuarantineService(repository);
  }

  @Test
  void persistsRejectedEnvelopeToRepository() {
    var envelope = new TelemetryEnvelope("trace-001", "factory/PLANT1/MACHINE1/telemetry",
        "{\"running\":true}", RECEIVED_AT);
    var rejected = new TelemetryValidationService.Result.Rejected("malformed_topic", null);

    service.quarantine(envelope, rejected);

    var captor = ArgumentCaptor.forClass(TelemetryQuarantineEntity.class);
    verify(repository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getTraceId()).isEqualTo("trace-001");
    assertThat(saved.getTopic()).isEqualTo("factory/PLANT1/MACHINE1/telemetry");
    assertThat(saved.getRawPayload()).isEqualTo("{\"running\":true}");
    assertThat(saved.getRejectionReason()).isEqualTo("malformed_topic");
    assertThat(saved.getRejectionField()).isNull();
    assertThat(saved.getReceivedAt()).isEqualTo(RECEIVED_AT);
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void persistsRejectionFieldWhenPresent() {
    var envelope = new TelemetryEnvelope("trace-002", "factory/PLANT1/MACHINE1/telemetry",
        "{}", RECEIVED_AT);
    var rejected = new TelemetryValidationService.Result.Rejected("missing_contract_field", "schemaVersion");

    service.quarantine(envelope, rejected);

    var captor = ArgumentCaptor.forClass(TelemetryQuarantineEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getRejectionField()).isEqualTo("schemaVersion");
  }

  @Test
  void swallowsExceptionWhenRepositorySaveFails() {
    var envelope = new TelemetryEnvelope("trace-003", "factory/PLANT1/MACHINE1/telemetry",
        "{}", RECEIVED_AT);
    var rejected = new TelemetryValidationService.Result.Rejected("unknown_plant", null);
    doThrow(new RuntimeException("DB unavailable")).when(repository).save(any());

    // must not throw
    service.quarantine(envelope, rejected);
  }

  @Test
  void truncatesOversizedPayload() {
    String oversized = "x".repeat(70000);
    var envelope = new TelemetryEnvelope("trace-004", "factory/PLANT1/MACHINE1/telemetry",
        oversized, RECEIVED_AT);
    var rejected = new TelemetryValidationService.Result.Rejected("unparseable_payload", null);

    service.quarantine(envelope, rejected);

    var captor = ArgumentCaptor.forClass(TelemetryQuarantineEntity.class);
    verify(repository).save(captor.capture());
    String stored = captor.getValue().getRawPayload();
    assertThat(stored).endsWith("[TRUNCATED]");
    assertThat(stored.length()).isEqualTo(65535); // MAX_PAYLOAD_LENGTH(65524) + sentinel(11) = 65535
  }

  @Test
  void doesNotTruncatePayloadAtExactLimit() {
    String exact = "y".repeat(65524); // exactly MAX_PAYLOAD_LENGTH — must not be truncated
    var envelope = new TelemetryEnvelope("trace-005", "factory/PLANT1/MACHINE1/telemetry",
        exact, RECEIVED_AT);
    var rejected = new TelemetryValidationService.Result.Rejected("unparseable_payload", null);

    service.quarantine(envelope, rejected);

    var captor = ArgumentCaptor.forClass(TelemetryQuarantineEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getRawPayload()).isEqualTo(exact);
  }

  @Test
  void handlesNullPayload() {
    var envelope = new TelemetryEnvelope("trace-006", "factory/PLANT1/MACHINE1/telemetry",
        null, RECEIVED_AT);
    var rejected = new TelemetryValidationService.Result.Rejected("unparseable_payload", null);

    service.quarantine(envelope, rejected);

    var captor = ArgumentCaptor.forClass(TelemetryQuarantineEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getRawPayload()).isEqualTo("");
  }

  // --- truncate() static method unit tests ---

  @Test
  void truncateReturnsPayloadUnchangedWhenWithinLimit() {
    String input = "a".repeat(100);
    assertThat(TelemetryQuarantineService.truncate(input)).isEqualTo(input);
  }

  @Test
  void truncateAppliesSentinelWhenOverLimit() {
    String input = "b".repeat(66000);
    String result = TelemetryQuarantineService.truncate(input);
    assertThat(result).startsWith("b".repeat(65524)); // MAX_PAYLOAD_LENGTH = 65535 - 11
    assertThat(result).endsWith("[TRUNCATED]");
    assertThat(result.length()).isEqualTo(65535); // 65524 + 11 = 65535
  }

  @Test
  void truncateHandlesNullInput() {
    assertThat(TelemetryQuarantineService.truncate(null)).isEqualTo("");
  }
}
