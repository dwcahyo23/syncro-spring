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
    values.put("materialCode", sparepart.getMaterialCode());
    values.put("leadTimeHours", sparepart.getLeadTimeHours());
    values.put("imageObjectKey", sparepart.getImageObjectKey());
    values.put("hierarchyIdentityKey", sparepart.getHierarchyIdentityKey());
    values.put("bomSerial", sparepart.getBomSerial());
    values.put("bomCode", sparepart.getBomCode());
    values.put("bomCodeVersion", sparepart.getBomCodeVersion());
    values.put("reviewStatus", sparepart.getReviewStatus() == null ? null : sparepart.getReviewStatus().name());
    values.put("rejectionReason", sparepart.getRejectionReason());
    return values;
  }
}
