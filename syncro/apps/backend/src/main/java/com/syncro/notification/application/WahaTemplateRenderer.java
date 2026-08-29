package com.syncro.notification.application;

import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.notification.domain.WahaTemplate;
import com.syncro.notification.infrastructure.WahaTemplateRepository;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
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
  private final MachineRepository machineRepository;

  @org.springframework.beans.factory.annotation.Autowired
  public WahaTemplateRenderer(WahaTemplateRepository wahaTemplateRepository,
      SparepartAlertRepository sparepartAlertRepository,
      MachineSparepartInstallationRepository installationRepository,
      MachineRepository machineRepository) {
    this.wahaTemplateRepository = wahaTemplateRepository;
    this.sparepartAlertRepository = sparepartAlertRepository;
    this.installationRepository = installationRepository;
    this.machineRepository = machineRepository;
  }

  /** Constructor used by existing tests; the machine repo is injected by Spring. */
  public WahaTemplateRenderer(WahaTemplateRepository wahaTemplateRepository,
      SparepartAlertRepository sparepartAlertRepository,
      MachineSparepartInstallationRepository installationRepository) {
    this(wahaTemplateRepository, sparepartAlertRepository, installationRepository, null);
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

    // PROCUREMENT_RISK alerts carry no threshold snapshot, so the shared threshold-shaped
    // template would render a misleading "-%". Surface the risk evidence instead (story 8-7).
    if (alert.getAlertType() == SparepartAlertType.PROCUREMENT_RISK) {
      return renderProcurementRisk(alert, machine, plant, machineGroup, sparepart, installation);
    }

    String body = templateEntity.getBody();
    body = body.replace("{machineCode}", safeStr(machine.getCode()));
    body = body.replace("{machineName}", safeStr(machine.getName()));
    body = body.replace("{plantCode}", safeStr(plant.getCode()));
    body = body.replace("{machineGroup}", safeStr(machineGroup.getName()));
    body = body.replace("{sparepartName}", safeStr(sparepart.getName()));
    body = body.replace("{thresholdPercent}", safeNumber(alert.getThresholdPercentage()));
    body = body.replace("{currentCount}", safeNumber(alert.getCurrentCounterSnapshot()));
    body = body.replace("{alertTime}", ALERT_TIME_FORMAT.format(alert.getCreatedAt()));

    return body;
  }

  private static String renderProcurementRisk(SparepartAlertEntity alert,
      MachineEntity machine, PlantEntity plant, MachineGroupEntity machineGroup,
      SparepartEntity sparepart, MachineSparepartInstallationEntity installation) {
    String basis = alert.getCalculationBasis() != null
        ? (alert.getCalculationBasis() == CalculationBasis.ROLLING_30_DAY
            ? "30 hari berjalan" : "seluruh riwayat")
        : "-";
    String depletion = alert.getProjectedDepletionAt() != null
        ? ALERT_TIME_FORMAT.format(alert.getProjectedDepletionAt())
        : "-";
    return "Peringatan pengadaan: proyeksi penipisan sparepart "
        + safeStr(sparepart.getName())
        + " (fungsi " + safeStr(installation.getFunctionName())
        + ") pada mesin " + safeStr(machine.getCode())
        + " jatuh dalam jendela waktu lead time. "
        + "Lead time: " + safeNumber(alert.getLeadTimeHours()) + " jam · "
        + "Laju: " + safeNumber(alert.getRatePerOperatingHour()) + " counter/jam operasi · "
        + "Basis proyeksi: " + basis + " · "
        + "Perkiraan habis: " + depletion + ". "
        + "Pabrik " + safeStr(plant.getCode()) + " / " + safeStr(machineGroup.getName()) + ".";
  }

  /**
   * Renders the active {@code workorder_lifecycle} template with workorder context data
   * (story 14-4, FR-180). Variables: workOrderId, machineCode, status, eventLabel, transitionedAt.
   */
  @Transactional(readOnly = true)
  public String renderWorkorderLifecycle(String workOrderId, String machineCode, String status,
      String eventLabel, java.time.Instant transitionedAt) {
    var templateEntity = wahaTemplateRepository.findByTemplateKey(WahaTemplate.WORKORDER_LIFECYCLE_KEY)
        .orElseThrow(() -> new WahaTemplateRenderException(
            "No active WAHA template found for key: " + WahaTemplate.WORKORDER_LIFECYCLE_KEY));
    String body = templateEntity.getBody();
    body = body.replace("{workOrderId}", safeStr(workOrderId));
    body = body.replace("{machineCode}", safeStr(machineCode));
    body = body.replace("{status}", safeStr(status));
    body = body.replace("{eventLabel}", safeStr(eventLabel));
    body = body.replace("{transitionedAt}",
        transitionedAt != null ? ALERT_TIME_FORMAT.format(transitionedAt) : "");
    return body;
  }

  /**
   * Renders the active {@code workorder_ack} template (story 14-4, FR-181).
   * Variables: workOrderId, machineCode, ackDeadline, ackLink.
   */
  @Transactional(readOnly = true)
  public String renderWorkorderAck(String workOrderId, String machineCode,
      java.time.Instant ackDeadline, String ackLink) {
    var templateEntity = wahaTemplateRepository.findByTemplateKey(WahaTemplate.WORKORDER_ACK_KEY)
        .orElseThrow(() -> new WahaTemplateRenderException(
            "No active WAHA template found for key: " + WahaTemplate.WORKORDER_ACK_KEY));
    String body = templateEntity.getBody();
    body = body.replace("{workOrderId}", safeStr(workOrderId));
    body = body.replace("{machineCode}", safeStr(machineCode));
    body = body.replace("{ackDeadline}",
        ackDeadline != null ? ALERT_TIME_FORMAT.format(ackDeadline) : "");
    body = body.replace("{ackLink}", safeStr(ackLink));
    return body;
  }

  /** Resolves a workorder's machine code; empty string when the machine is missing. */
  @Transactional(readOnly = true)
  public String machineCodeFor(String workOrderId, UUID machineId) {
    if (machineId == null || machineRepository == null) {
      return "";
    }
    return machineRepository.findById(machineId).map(MachineEntity::getCode).orElse("");
  }

  private static String safeStr(String value) {
    return value != null ? value : "";
  }

  /** Renders a possibly-null numeric alert snapshot as a dash instead of the literal "null". */
  private static String safeNumber(Number value) {
    return value != null ? String.valueOf(value) : "-";
  }

  public static class WahaTemplateRenderException extends RuntimeException {
    public WahaTemplateRenderException(String message) {
      super(message);
    }
  }
}
