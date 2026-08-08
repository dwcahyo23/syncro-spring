package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelemetryTopicTest {

  @Test
  void parsesValidTopic() {
    var result = TelemetryTopic.parse("factory/GM1/BF-08410/telemetry");

    assertThat(result).isPresent();
    assertThat(result.get().plantCode()).isEqualTo("GM1");
    assertThat(result.get().machineCode()).isEqualTo("BF-08410");
  }

  @Test
  void rejectsNullTopic() {
    assertThat(TelemetryTopic.parse(null)).isEmpty();
  }

  @Test
  void rejectsEmptyTopic() {
    assertThat(TelemetryTopic.parse("")).isEmpty();
  }

  @Test
  void rejectsExtraSegment() {
    assertThat(TelemetryTopic.parse("factory/GM1/BF-08410/telemetry/extra")).isEmpty();
  }

  @Test
  void rejectsTooFewSegments() {
    assertThat(TelemetryTopic.parse("factory/only-one-segment")).isEmpty();
  }

  @Test
  void rejectsWrongPrefix() {
    assertThat(TelemetryTopic.parse("WRONG/GM1/BF-08410/telemetry")).isEmpty();
  }

  @Test
  void rejectsWrongSuffix() {
    assertThat(TelemetryTopic.parse("factory/GM1/BF-08410/status")).isEmpty();
  }

  @Test
  void rejectsTrailingSlash() {
    assertThat(TelemetryTopic.parse("factory/GM1/BF-08410/telemetry/")).isEmpty();
  }

  @Test
  void rejectsEmptyPlantCodeSegment() {
    assertThat(TelemetryTopic.parse("factory//BF-08410/telemetry")).isEmpty();
  }

  @Test
  void rejectsEmptyMachineCodeSegment() {
    assertThat(TelemetryTopic.parse("factory/GM1//telemetry")).isEmpty();
  }
}