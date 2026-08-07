package com.syncro.sparepart.application;

import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import java.util.HashMap;
import java.util.Map;

public final class SparepartTaxonomyAuditValues {
  private SparepartTaxonomyAuditValues() {
  }

  public static Map<String, Object> of(SparepartTaxonomyEntity entry) {
    var values = new HashMap<String, Object>();
    values.put("dimension", entry.getDimension().name());
    values.put("code", entry.getCode());
    values.put("name", entry.getName());
    values.put("categoryCode", entry.getCategory() == null ? null : entry.getCategory().getCode());
    return values;
  }
}
