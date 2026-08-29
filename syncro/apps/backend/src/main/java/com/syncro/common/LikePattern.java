package com.syncro.common;

import java.util.Locale;

/**
 * Shared LIKE-pattern escaping (DW-123). The backslash-first ordering of the replace chain
 * is load-bearing: {@code \\} must be doubled before {@code %} and {@code _} are escaped,
 * otherwise the escapes introduced for the wildcards would themselves be re-escaped.
 *
 * <p>Every LIKE predicate in the codebase that receives user search terms must run its
 * input through {@link #escape} (or {@link #contains} for a substring match) and declare
 * {@code escape '\'} on the JPQL/native query — the same defect class as the audit-actor
 * filter (fixed) and the BOM-prefix collision (DW-121).
 */
public final class LikePattern {

  private LikePattern() {
  }

  /** Escapes {@code \}, {@code %}, and {@code _} so a LIKE input matches literally. */
  public static String escape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /** Escapes and wraps in {@code %...%} for a case-insensitive substring match. */
  public static String contains(String value) {
    return "%" + escape(value) + "%";
  }

  /** Escapes and wraps in {@code %...%} for a case-insensitive substring match. */
  public static String containsLower(String value) {
    return "%" + escape(value.toLowerCase(Locale.ROOT)) + "%";
  }
}
