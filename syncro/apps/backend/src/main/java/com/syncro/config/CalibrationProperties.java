package com.syncro.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Compliance calibration configuration (story 21-2). Typed binding per the spine
 * config rule — the EXPIRING_SOON derivation window is env-overridable, never
 * hardcoded in the service.
 *
 * @param expiringWindowDays how many days before {@code next_calibration_date} an
 *                           instrument reads as EXPIRING_SOON (derived on read;
 *                           no scheduler persists it)
 */
@ConfigurationProperties(prefix = "syncro.compliance.calibration")
public record CalibrationProperties(
    @DefaultValue("14") int expiringWindowDays) {

  public CalibrationProperties {
    // Review 21-2 L9: an unbounded window would make every derived read 500 via
    // plusDays overflow (LocalDate.MAX) — cap at a decade.
    if (expiringWindowDays < 1 || expiringWindowDays > 3650) {
      throw new IllegalArgumentException(
          "syncro.compliance.calibration.expiring-window-days must be between 1 and 3650");
    }
  }
}
