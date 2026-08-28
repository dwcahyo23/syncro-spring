package com.syncro.sparepart.stock.infrastructure.db;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite id class for {@code sparepart_stock} — {@code (material_code, plant_id)}
 * (story 12-4, FR-146). Must match the entity's @Id fields exactly for JPA id mapping.
 */
public class SparepartStockId implements Serializable {

  private String materialCode;
  private UUID plantId;

  protected SparepartStockId() {
  }

  public SparepartStockId(String materialCode, UUID plantId) {
    this.materialCode = materialCode;
    this.plantId = plantId;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public UUID getPlantId() {
    return plantId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SparepartStockId that)) {
      return false;
    }
    return Objects.equals(materialCode, that.materialCode) && Objects.equals(plantId, that.plantId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(materialCode, plantId);
  }
}
