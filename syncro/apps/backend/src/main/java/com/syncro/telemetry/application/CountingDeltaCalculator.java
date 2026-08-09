package com.syncro.telemetry.application;

public final class CountingDeltaCalculator {

  public static final long UNSIGNED_16BIT_MODULUS = 1L << 16;

  private CountingDeltaCalculator() {
  }

  public static long delta(long previous, long current) {
    return Math.floorMod(current - previous, UNSIGNED_16BIT_MODULUS);
  }
}
