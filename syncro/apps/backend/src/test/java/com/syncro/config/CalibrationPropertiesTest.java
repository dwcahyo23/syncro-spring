package com.syncro.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 21-2 review L9: the EXPIRING_SOON window is bounded — below 1 the
 * derivation is meaningless, above 3650 a huge value would make every derived
 * read 500 via LocalDate.plusDays overflow.
 */
class CalibrationPropertiesTest {

  @Test
  @DisplayName("21.2-CFG-001 P1 default window of 14 and the 3650 cap are accepted")
  void validWindows() {
    assertThat(new CalibrationProperties(14).expiringWindowDays()).isEqualTo(14);
    assertThat(new CalibrationProperties(1).expiringWindowDays()).isEqualTo(1);
    assertThat(new CalibrationProperties(3650).expiringWindowDays()).isEqualTo(3650);
  }

  @Test
  @DisplayName("21.2-CFG-002 P1 window below 1 or above 3650 is rejected at binding")
  void invalidWindowsRejected() {
    assertThatThrownBy(() -> new CalibrationProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expiring-window-days");
    assertThatThrownBy(() -> new CalibrationProperties(-5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CalibrationProperties(3651))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("3650");
  }
}
