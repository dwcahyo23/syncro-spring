package com.syncro.sparepart.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Pure derivation of the sparepart BOM code and display label (DW-73/74). Extracted from
 * {@code SparepartService} so the pilot seed and fixture tests can cross-check their literal
 * values against the live algorithm — a drift in {@code bomPrefix}/{@code codePart}/
 * {@code sparepartLabel} now fails CI instead of silently diverging the seed.
 *
 * <p>Deliberately static and dependency-free (no repositories, no entities): the pilot seed
 * test is a hermetic JDBC test with no Spring context, so it can only call pure functions.
 */
public final class SparepartDerivation {

  private SparepartDerivation() {
  }

  /** Taxonomy code → fixed-width alphanumeric code part: strip non-alphanumerics, uppercase,
   * left-pad to 3 with zeros, truncate to 3. */
  public static String codePart(String taxonomyCode) {
    var normalized = taxonomyCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    return normalized.length() <= 3
        ? String.format(Locale.ROOT, "%-3s", normalized).replace(' ', '0')
        : normalized.substring(0, 3);
  }

  /** BOM prefix: {@code machineCode + plantCode + category + kind + brand} code parts. */
  public static String bomPrefix(String machineCode, String plantCode,
      String categoryCode, String kindCode, String brandCode) {
    return machineCode + plantCode
        + codePart(categoryCode) + codePart(kindCode) + codePart(brandCode);
  }

  /** Display label: {@code Category · Kind · Brand · Type} name parts joined with " · ". */
  public static String sparepartLabel(String categoryName, String kindName,
      String brandName, String typeName) {
    return categoryName + " · " + kindName + " · " + brandName + " · " + typeName;
  }

  /**
   * Consumed-percentage of a sparepart lifetime (DW-74): {@code consumedCount * 100 /
   * expectedProductionCount}, scale 2, HALF_UP — the exact formula {@code SparepartLifetimeEvaluator}
   * applies. Extracted so the pilot fixture boundary test runs the real math instead of a mirror.
   */
  public static BigDecimal consumedPercentage(long consumedCount, long expectedProductionCount) {
    return BigDecimal.valueOf(consumedCount)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(expectedProductionCount), 2, RoundingMode.HALF_UP);
  }
}
