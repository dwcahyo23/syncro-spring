package com.syncro.machine.application;

import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import java.util.HashMap;
import java.util.Map;

public final class ResponsibilityAuditValues {
  private ResponsibilityAuditValues() {
  }

  public static Map<String, Object> of(MachineResponsibilityEntity responsibility) {
    var values = new HashMap<String, Object>();
    values.put("machineId", responsibility.getMachineId().toString());
    values.put("userId", responsibility.getUserId().toString());
    values.put("level", responsibility.getLevel().name());
    return values;
  }
}
