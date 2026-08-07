package com.syncro.sparepart.application;

import com.syncro.sparepart.infrastructure.SparepartEntity;
import java.util.HashMap;
import java.util.Map;

public final class SparepartAuditValues {
  private SparepartAuditValues() {
  }

  public static Map<String, Object> of(SparepartEntity sparepart) {
    var values = new HashMap<String, Object>();
    values.put("code", sparepart.getCode());
    values.put("name", sparepart.getName());
    values.put("machineCode", sparepart.getMachine().getCode());
    values.put("categoryCode", sparepart.getCategory().getCode());
    values.put("brandCode", sparepart.getBrand().getCode());
    values.put("kindCode", sparepart.getKind().getCode());
    values.put("typeCode", sparepart.getType().getCode());
    return values;
  }
}
