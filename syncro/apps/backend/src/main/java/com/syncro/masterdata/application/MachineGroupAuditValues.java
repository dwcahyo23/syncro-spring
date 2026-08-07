package com.syncro.masterdata.application;

import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import java.util.HashMap;
import java.util.Map;

public final class MachineGroupAuditValues {
  private MachineGroupAuditValues() {
  }

  public static Map<String, Object> of(MachineGroupEntity machineGroup) {
    var values = new HashMap<String, Object>();
    values.put("plantCode", machineGroup.getPlant().getCode());
    values.put("plantName", machineGroup.getPlant().getName());
    values.put("name", machineGroup.getName());
    return values;
  }
}
