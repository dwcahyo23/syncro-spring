package com.syncro.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TelemetryPropertiesTest {

  @Test
  void dataQualityWindowAcceptsDocumentedRange() {
    assertThatCode(() -> new TelemetryProperties.DataQuality(Duration.parse("PT1H")))
        .doesNotThrowAnyException();
    // Sub-minute windows are legal config; the tracker counts them at minute granularity.
    assertThatCode(() -> new TelemetryProperties.DataQuality(Duration.parse("PT30S")))
        .doesNotThrowAnyException();
    // Exactly 30 days is the inclusive ceiling.
    assertThatCode(() -> new TelemetryProperties.DataQuality(Duration.parse("PT2592000S")))
        .doesNotThrowAnyException();
  }

  @Test
  void dataQualityWindowRejectsZeroAndNegative() {
    assertThatThrownBy(() -> new TelemetryProperties.DataQuality(Duration.parse("PT0S")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a positive duration");
    assertThatThrownBy(() -> new TelemetryProperties.DataQuality(Duration.parse("PT-1H")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a positive duration");
    assertThatThrownBy(() -> new TelemetryProperties.DataQuality(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be a positive duration");
  }

  @Test
  void dataQualityWindowRejectsBeyondThirtyDaysIncludingSubMinuteOverrun() {
    // 30 days plus one second must not slip through a minutes-truncated comparison.
    assertThatThrownBy(() -> new TelemetryProperties.DataQuality(Duration.parse("PT2592001S")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be at most 30 days");
    assertThatThrownBy(() -> new TelemetryProperties.DataQuality(Duration.parse("P31D")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be at most 30 days");
  }
}
