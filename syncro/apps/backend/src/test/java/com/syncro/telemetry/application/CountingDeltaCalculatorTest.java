package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountingDeltaCalculatorTest {

  @Test
  void normalIncreaseComputesDirectDelta() {
    assertThat(CountingDeltaCalculator.delta(100, 200)).isEqualTo(100L);
  }

  @Test
  void wrapFromMaxToLowComputesOne() {
    assertThat(CountingDeltaCalculator.delta(65535, 0)).isEqualTo(1L);
  }

  @Test
  void wrapFromMidToLowComputesWrappedDelta() {
    assertThat(CountingDeltaCalculator.delta(40000, 1000)).isEqualTo(26536L);
  }

  @Test
  void wrapFromZeroToMaxComputesFullModulus() {
    assertThat(CountingDeltaCalculator.delta(0, 65535)).isEqualTo(65535L);
  }

  @Test
  void noChangeComputesZero() {
    assertThat(CountingDeltaCalculator.delta(100, 100)).isEqualTo(0L);
  }

  @Test
  void zeroToZeroComputesZero() {
    assertThat(CountingDeltaCalculator.delta(0, 0)).isEqualTo(0L);
  }
}
