package com.syncro.notification.application;

import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.notification.domain.WahaTemplate;
import com.syncro.notification.infrastructure.WahaTemplateRepository;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WahaTemplateRenderer {

  private static final DateTimeFormatter ALERT_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Jakarta"));

  private final WahaTemplateRepository wahaTemplateRepository;
  private final SparepartAlertRepository sparepartAlertRepository;
  private final MachineSparepartInstallationRepository installationRepository;

  public WahaTemplateRenderer(WahaTemplateRepository wahaTemplateRepository,
      SparepartAlertRepository sparepartAlertRepository,
      MachineSparepartInstallationRepository installationRepository) {
    this.wahaTemplateRepository = wahaTemplateRepository;
    this.sparepartAlertRepository = sparepartAlertRepository;
    this.installationRepository = installationRepository;
  }

  /**
   * Renders the active WAHA alert_notification template with alert context data.
   *
   * @throws WahaTemplateRenderException if template not found or alert/installation data missing
   */
  @Transactional(readOnly = true)
  public String render(UUID alertId) {
    var templateEntity = wahaTemplateRepository.findByTemplateKey(WahaTemplate.DEFAULT_KEY)
        .orElseThrow(() -> new WahaTemplateRenderException(
            "No active WAHA template found for key: " + WahaTemplate.DEFAULT_KEY));

    SparepartAlertEntity alert = sparepartAlertRepository.findById(alertId)
        .orElseThrow(() -> new WahaTemplateRenderException("Alert not found: " + alertId));

    var installation = installationRepository
        .findByIdWithDetails(alert.getMachineSparepartInstallationId())
        .orElseThrow(() -> new WahaTemplateRenderException(
            "Installation not found: " + alert.getMachineSparepartInstallationId()));

    var machine = installation.getMachine();
    var plant = machine.getPlant();
    var machineGroup = machine.getMachineGroup();
    var sparepart = installation.getSparepart();

    String body = templateEntity.getBody();
    body = body.replace("{machineCode}", safeStr(machine.getCode()));
    body = body.replace("{machineName}", safeStr(machine.getName()));
    body = body.replace("{plantCode}", safeStr(plant.getCode()));
    body = body.replace("{machineGroup}", safeStr(machineGroup.getName()));
    body = body.replace("{sparepartName}", safeStr(sparepart.getName()));
    body = body.replace("{thresholdPercent}", String.valueOf(alert.getThresholdPercentage()));
    body = body.replace("{currentCount}", String.valueOf(alert.getCurrentCounterSnapshot()));
    body = body.replace("{alertTime}", ALERT_TIME_FORMAT.format(alert.getCreatedAt()));

    return body;
  }

  private static String safeStr(String value) {
    return value != null ? value : "";
  }

  public static class WahaTemplateRenderException extends RuntimeException {
    public WahaTemplateRenderException(String message) {
      super(message);
    }
  }
}
