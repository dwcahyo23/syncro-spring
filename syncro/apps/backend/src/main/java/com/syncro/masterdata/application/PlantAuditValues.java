package com.syncro.masterdata.application;

import com.syncro.auth.infrastructure.PlantEntity;
import java.util.HashMap;
import java.util.Map;

public final class PlantAuditValues {
  private PlantAuditValues() {
  }

  public static Map<String, Object> of(PlantEntity plant) {
    var values = new HashMap<String, Object>();
    values.put("code", plant.getCode());
    values.put("name", plant.getName());
    return values;
  }
}
