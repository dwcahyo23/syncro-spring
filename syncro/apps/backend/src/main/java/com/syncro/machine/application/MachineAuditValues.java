package com.syncro.machine.application;

import com.syncro.machine.infrastructure.MachineEntity;
import java.util.HashMap;
import java.util.Map;

public final class MachineAuditValues {
  private MachineAuditValues() {
  }

  public static Map<String, Object> of(MachineEntity machine) {
    var values = new HashMap<String, Object>();
    values.put("code", machine.getCode());
    values.put("name", machine.getName());
    values.put("status", machine.getStatus().name());
    values.put("brand", machine.getBrand());
    values.put("installedAt", machine.getInstalledAt() == null ? null : machine.getInstalledAt().toString());
    values.put("notes", machine.getNotes());
    return values;
  }
}
