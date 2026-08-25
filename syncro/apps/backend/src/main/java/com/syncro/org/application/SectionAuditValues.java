package com.syncro.org.application;

import com.syncro.org.infrastructure.SectionEntity;
import java.util.HashMap;
import java.util.Map;

/** Audit snapshot for a section (code/name/active) — Phase 1 actor-correlated model. */
public final class SectionAuditValues {
  private SectionAuditValues() {
  }

  public static Map<String, Object> of(SectionEntity section) {
    var values = new HashMap<String, Object>();
    values.put("plantCode", section.getPlant().getCode());
    values.put("plantName", section.getPlant().getName());
    values.put("code", section.getCode());
    values.put("name", section.getName());
    values.put("active", section.isActive());
    return values;
  }
}
