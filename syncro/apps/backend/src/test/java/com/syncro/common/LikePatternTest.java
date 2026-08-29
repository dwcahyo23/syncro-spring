package com.syncro.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternTest {

  @Test
  void escape_doublesBackslashFirst_thenEscapesWildcards() {
    // Backslash-first ordering: the backslash introduced by escaping % and _ must not
    // be re-escaped. "a\\b%c_d" -> "a\\\\b\\%c\\_d".
    assertThat(LikePattern.escape("a\\b%c_d"))
        .isEqualTo("a\\\\b\\%c\\_d");
  }

  @Test
  void escape_plainTerm_unchanged() {
    assertThat(LikePattern.escape("bf-08410")).isEqualTo("bf-08410");
  }

  @Test
  void contains_wrapsEscapedTermInPercent() {
    assertThat(LikePattern.contains("GM_1")).isEqualTo("%GM\\_1%");
  }

  @Test
  void containsLower_lowercasesAndWraps() {
    assertThat(LikePattern.containsLower("AB_C")).isEqualTo("%ab\\_c%");
  }
}
