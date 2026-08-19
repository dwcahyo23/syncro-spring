package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TelemetryPayloadTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String CONTRACT = "\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\",";

  @Test
  void parsesValidPayload() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload())
        .isEqualTo(new TelemetryPayload(true, 12.5, 100, "1.0", "m-1", Instant.parse("2026-08-14T09:30:00Z")));
  }

  @Test
  void rejectsMissingField() {
    var result = TelemetryPayload.parse("{" + CONTRACT + "\"running\":true}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_base_field");
    assertThat(rejected.field()).isEqualTo("runtimeHours");
  }

  @Test
  void rejectsWrongType() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":\"yes\",\"runtimeHours\":12,\"counting\":100}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("running");
  }

  @Test
  void rejectsUnparseableJson() {
    var result = TelemetryPayload.parse("not json", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("unparseable_payload");
  }

  @Test
  void rejectsEmptyPayload() {
    var result = TelemetryPayload.parse("", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("unparseable_payload");
  }

  @Test
  void rejectsFractionalCounting() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":12.9}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void acceptsIntegralDoubleCounting() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":12.0}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().counting()).isEqualTo(12L);
  }

  @Test
  void rejectsNonFiniteRuntimeHours() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":1e999,\"counting\":100}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("runtimeHours");
  }

  @Test
  void rejectsNegativeCounting() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":-1}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("out_of_range");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void rejectsNegativeIntegralDoubleCounting() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":-12.0}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("out_of_range");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void collectsConfiguredScalarFields() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200,\"heaterOn\":true,\"qualityGrade\":\"A\"}",
        objectMapper, Set.of("vibration", "rpm", "heaterOn", "qualityGrade"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    var optional = accepted.payload().optionalFields();
    assertThat(optional).containsOnlyKeys("vibration", "rpm", "heaterOn", "qualityGrade");
    assertThat(optional.get("vibration").asDouble()).isEqualTo(2.4);
    assertThat(optional.get("rpm").asLong()).isEqualTo(1200L);
    assertThat(optional.get("heaterOn").asBoolean()).isTrue();
    assertThat(optional.get("qualityGrade").asText()).isEqualTo("A");
  }

  @Test
  void preservesConfiguredInsertionOrderFromPayload() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200,\"heaterOn\":true,\"qualityGrade\":\"A\"}",
        objectMapper, new LinkedHashSet<>(List.of("vibration", "rpm", "heaterOn", "qualityGrade")));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields().keySet())
        .containsExactly("vibration", "rpm", "heaterOn", "qualityGrade");
  }

  @Test
  void rejectsConfiguredNonScalarValues() {
    for (String value : List.of("{\"x\":1}", "[1,2]", "null")) {
      var result = TelemetryPayload.parse(
          "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":" + value + "}",
          objectMapper, Set.of("vibration"));

      assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
      var rejected = (TelemetryPayload.ParseResult.Rejected) result;
      assertThat(rejected.reason()).isEqualTo("invalid_field_type");
      assertThat(rejected.field()).isEqualTo("vibration");
    }
  }

  @Test
  void ignoresUnknownUnconfiguredFields() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"temperature\":30}",
        objectMapper, Set.of("vibration"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).containsOnlyKeys("vibration");
    assertThat(accepted.payload().optionalFields().get("vibration").asDouble()).isEqualTo(2.4);
  }

  @Test
  void baseFieldsRemainRequiredWhenOptionalConfigured() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"vibration\":2.4}",
        objectMapper, Set.of("vibration"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_base_field");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void emptyOptionalFieldsWhenNothingConfigured() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).isEmpty();
  }

  @Test
  void rejectsConfiguredIntegralBeyondLongRange() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"rpm\":9223372036854775808}",
        objectMapper, Set.of("rpm"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("rpm");
  }

  @Test
  void rejectsOversizedConfiguredString() {
    var oversized = "x".repeat(4097);
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"qualityGrade\":\"" + oversized
            + "\"}",
        objectMapper, Set.of("qualityGrade"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("qualityGrade");
  }

  @Test
  void toleratesNullConfiguredSet() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4}",
        objectMapper, null);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).isEmpty();
  }

  @Test
  void skipsConfiguredNamesCollidingWithBaseFields() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"_field\":\"x\"}",
        objectMapper, Set.of("running", "_field"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).isEmpty();
  }

  @Test
  void rejectsMissingSchemaVersion() {
    var result = TelemetryPayload.parse(
        "{\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_contract_field");
    assertThat(rejected.field()).isEqualTo("schemaVersion");
  }

  @Test
  void rejectsUnsupportedSchemaVersion() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"2.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("unsupported_schema_version");
    assertThat(rejected.field()).isEqualTo("schemaVersion");
  }

  @Test
  void rejectsBlankSchemaVersion() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("schemaVersion");
  }

  @Test
  void rejectsMissingMessageId() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_contract_field");
    assertThat(rejected.field()).isEqualTo("messageId");
  }

  @Test
  void rejectsBlankMessageId() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("messageId");
  }

  @Test
  void rejectsOversizedMessageId() {
    var oversized = "x".repeat(256);
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"" + oversized + "\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("messageId");
  }

  @Test
  void rejectsMissingTimestamp() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_contract_field");
    assertThat(rejected.field()).isEqualTo("timestamp");
  }

  @Test
  void rejectsInvalidTimestampFormat() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14 09:30:00\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_timestamp");
    assertThat(rejected.field()).isEqualTo("timestamp");
  }

  @Test
  void rejectsNonStringTimestamp() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":1234567890,"
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_timestamp");
    assertThat(rejected.field()).isEqualTo("timestamp");
  }

  @Test
  void parsesContractFieldsOnAcceptedPayload() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-42\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().schemaVersion()).isEqualTo("1.0");
    assertThat(accepted.payload().messageId()).isEqualTo("m-42");
    assertThat(accepted.payload().timestamp()).isEqualTo(Instant.parse("2026-08-14T09:30:00Z"));
  }

  @Test
  void contractFieldsAreNotCollectedAsOptionalFields() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-42\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4}",
        objectMapper, Set.of("schemaVersion", "messageId", "timestamp", "vibration"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).containsOnlyKeys("vibration");
  }

  @Test
  void rejectsNullValuedContractFields() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":null,\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_contract_field");
    assertThat(rejected.field()).isEqualTo("schemaVersion");
  }

  @Test
  void rejectsWhitespaceOnlyMessageId() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"   \",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("messageId");
  }

  @Test
  void trimsSurroundingWhitespaceFromMessageId() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"  m-42  \",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().messageId()).isEqualTo("m-42");
  }

  @Test
  void acceptsMaxLengthMessageId() {
    var maxLength = "x".repeat(255);
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"" + maxLength + "\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().messageId()).isEqualTo(maxLength);
  }

  @Test
  void rejectsMessageIdWithControlCharacters() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\\nFAKE_LOG_LINE\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("messageId");
  }

  @Test
  void rejectsTrailingTokensAfterJsonObject() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100} trailing-garbage",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    assertThat(((TelemetryPayload.ParseResult.Rejected) result).reason()).isEqualTo("unparseable_payload");
  }

  @Test
  void rejectsConcatenatedJsonPayloads() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}"
            + "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-2\",\"timestamp\":\"2026-08-14T09:30:01Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    assertThat(((TelemetryPayload.ParseResult.Rejected) result).reason()).isEqualTo("unparseable_payload");
  }

  @Test
  void rejectsNegativeRuntimeHours() {
    var result = TelemetryPayload.parse(
        "{" + CONTRACT + "\"running\":true,\"runtimeHours\":-1.0,\"counting\":100}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("out_of_range");
    assertThat(rejected.field()).isEqualTo("runtimeHours");
  }

  @Test
  void acceptsOffsetTimestampAndNormalizesToInstant() {
    var result = TelemetryPayload.parse(
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T16:30:00+07:00\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}",
        objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().timestamp()).isEqualTo(Instant.parse("2026-08-14T09:30:00Z"));
  }
}
