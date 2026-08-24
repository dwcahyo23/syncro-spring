package com.syncro.sparepart.api;

import java.util.UUID;

public final class SparepartImageDtos {
  private SparepartImageDtos() {
  }

  public record SparepartImageView(UUID sparepartId, String objectKey, String presignedUrl) {
  }
}