package com.syncro.sparepart.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class SparepartPriceEntryDtos {
  private SparepartPriceEntryDtos() {
  }

  public record SparepartPriceEntryRequest(
      @NotNull @Positive @Digits(integer = 16, fraction = 2) BigDecimal amount,
      @Pattern(regexp = "[A-Z]{3}") String currency,
      @Positive @Digits(integer = 12, fraction = 6) BigDecimal kursToIdr) {
  }

  public record SparepartPriceEntryView(
      UUID id,
      UUID sparepartId,
      BigDecimal amount,
      String currency,
      BigDecimal kursToIdr,
      BigDecimal idrAmount,
      UUID enteredBy,
      String enteredByName,
      Instant enteredAt) {
  }
}
