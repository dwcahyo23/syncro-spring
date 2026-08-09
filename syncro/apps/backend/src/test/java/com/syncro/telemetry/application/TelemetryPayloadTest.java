package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}