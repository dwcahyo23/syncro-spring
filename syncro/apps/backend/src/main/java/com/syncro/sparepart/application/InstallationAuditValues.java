package com.syncro.sparepart.application;

import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import java.util.HashMap;
import java.util.Map;

public final class InstallationAuditValues {
  private InstallationAuditValues() {
  }

  public static Map<String, Object> of(MachineSparepartInstallationEntity installation) {
    var values = new HashMap<String, Object>();
    values.put("machineCode", installation.getMachine().getCode());
    values.put("sparepartCode", installation.getSparepart().getCode());
    values.put("functionName", installation.getFunctionName());
    values.put("expectedProductionCount", installation.getExpectedProductionCount());
    values.put("baselineCounter", installation.getBaselineCounter());
    values.put("thresholdPercentage", installation.getThresholdPercentage());
    values.put("installedAt", installation.getInstalledAt().toString());
    return values;
  }
}
