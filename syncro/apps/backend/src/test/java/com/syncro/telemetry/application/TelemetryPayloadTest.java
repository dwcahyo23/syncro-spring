package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TelemetryPayloadTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parsesValidPayload() {
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload()).isEqualTo(new TelemetryPayload(true, 12.5, 100));
  }

  @Test
  void rejectsMissingField() {
    var result = TelemetryPayload.parse("{\"running\":true}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_base_field");
    assertThat(rejected.field()).isEqualTo("runtimeHours");
  }

  @Test
  void rejectsWrongType() {
    var result = TelemetryPayload.parse("{\"running\":\"yes\",\"runtimeHours\":12,\"counting\":100}", objectMapper);

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
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":12.5,\"counting\":12.9}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void acceptsIntegralDoubleCounting() {
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":12.5,\"counting\":12.0}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().counting()).isEqualTo(12L);
  }

  @Test
  void rejectsNonFiniteRuntimeHours() {
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":1e999,\"counting\":100}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("runtimeHours");
  }

  @Test
  void rejectsNegativeCounting() {
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":12.5,\"counting\":-1}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("out_of_range");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void rejectsNegativeIntegralDoubleCounting() {
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":12.5,\"counting\":-12.0}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("out_of_range");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void collectsConfiguredScalarFields() {
    var result = TelemetryPayload.parse(
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200,\"heaterOn\":true,\"qualityGrade\":\"A\"}",
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
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200,\"heaterOn\":true,\"qualityGrade\":\"A\"}",
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
          "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":" + value + "}",
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
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"temperature\":30}",
        objectMapper, Set.of("vibration"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).containsOnlyKeys("vibration");
    assertThat(accepted.payload().optionalFields().get("vibration").asDouble()).isEqualTo(2.4);
  }

  @Test
  void baseFieldsRemainRequiredWhenOptionalConfigured() {
    var result = TelemetryPayload.parse("{\"running\":true,\"runtimeHours\":12.5,\"vibration\":2.4}",
        objectMapper, Set.of("vibration"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("missing_base_field");
    assertThat(rejected.field()).isEqualTo("counting");
  }

  @Test
  void emptyOptionalFieldsWhenNothingConfigured() {
    var result = TelemetryPayload.parse(
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4}", objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).isEmpty();
  }

  @Test
  void rejectsConfiguredIntegralBeyondLongRange() {
    var result = TelemetryPayload.parse(
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"rpm\":9223372036854775808}",
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
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"qualityGrade\":\"" + oversized + "\"}",
        objectMapper, Set.of("qualityGrade"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Rejected.class);
    var rejected = (TelemetryPayload.ParseResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("qualityGrade");
  }

  @Test
  void toleratesNullConfiguredSet() {
    var result = TelemetryPayload.parse(
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4}", objectMapper, null);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).isEmpty();
  }

  @Test
  void skipsConfiguredNamesCollidingWithBaseFields() {
    var result = TelemetryPayload.parse(
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"_field\":\"x\"}",
        objectMapper, Set.of("running", "_field"));

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var accepted = (TelemetryPayload.ParseResult.Accepted) result;
    assertThat(accepted.payload().optionalFields()).isEmpty();
  }
}