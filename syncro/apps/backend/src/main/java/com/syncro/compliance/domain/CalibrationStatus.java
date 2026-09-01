package com.syncro.compliance.domain;

/**
 * Calibration instrument status (blueprint H3, story 15-2): values match the
 * {@code calibration_instruments.status} CHECK constraint in V1 exactly.
 */
public enum CalibrationStatus {
  VALID,
  EXPIRING_SOON,
  EXPIRED
}
