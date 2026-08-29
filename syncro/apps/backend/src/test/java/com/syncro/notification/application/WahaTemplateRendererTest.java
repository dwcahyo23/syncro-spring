package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.notification.domain.WahaTemplate;
import com.syncro.notification.infrastructure.WahaTemplateEntity;
import com.syncro.notification.infrastructure.WahaTemplateRepository;
import com.syncro.projection.application.CounterRateEstimator.CalculationBasis;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WahaTemplateRendererTest {

  @Mock
  private WahaTemplateRepository wahaTemplateRepository;

  @Mock
  private SparepartAlertRepository sparepartAlertRepository;

  @Mock
  private MachineSparepartInstallationRepository installationRepository;

  @Mock
  private MachineRepository machineRepository;

  private final Instant now = Instant.parse("2026-08-24T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private WahaTemplateRenderer renderer() {
    return new WahaTemplateRenderer(wahaTemplateRepository, sparepartAlertRepository, installationRepository);
  }

  @Test
  void rendersProcurementRiskWithEvidenceInBody() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var sparepartId = UUID.randomUUID();
    var installId = UUID.randomUUID();
    var alertId = UUID.randomUUID();

    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", now, now);
    var group = new MachineGroupEntity(groupId, plant, "Forming", now, now);
    var machine = new MachineEntity(machineId, plant, group, "BF-08410", "JBF19",
        MachineStatus.ACTIVE, "Juki", null, null, List.of(), now, now);
    var sparepart = new SparepartEntity(sparepartId, "BF-08410GM1ELEPLCWEC000",
        "Electric · PLC · Wecon · LX5", machine, null, null, null, null, now, now);
    var installation = new MachineSparepartInstallationEntity(installId, machine, sparepart,
        "Primary", 1000, 0, 90, now, now, now);

    var alert = new SparepartAlertEntity(alertId, machineId, installId,
        SparepartAlertType.PROCUREMENT_RISK, null, null, null, null, "trace-render",
        SparepartAlertStatus.OPEN, null, now, now);
    alert.snapshotProcurementEvidence(new BigDecimal("72.00"), new BigDecimal("15.00"),
        CalculationBasis.ROLLING_30_DAY, Instant.parse("2026-08-29T00:00:00Z"));

    when(wahaTemplateRepository.findByTemplateKey(WahaTemplate.DEFAULT_KEY))
        .thenReturn(Optional.of(new WahaTemplateEntity(UUID.randomUUID(),
            WahaTemplate.DEFAULT_KEY, "anything", now, now)));
    when(sparepartAlertRepository.findById(alertId)).thenReturn(Optional.of(alert));
    when(installationRepository.findByIdWithDetails(installId)).thenReturn(Optional.of(installation));

    var body = renderer().render(alertId);

    assertThat(body)
        .contains("Peringatan pengadaan")
        .contains("Lead time: 72.00 jam")
        .contains("Laju: 15.00 counter/jam operasi")
        .contains("Basis proyeksi: 30 hari berjalan")
        .contains("Perkiraan habis: 2026-08-29 07:00")
        .contains("Electric · PLC · Wecon · LX5")
        .contains("BF-08410")
        .contains("GM1")
        .contains("Forming");
  }

  // -------------------------------------------------------------------------
  // Story 14-4 workorder templates (FR-180/FR-181)
  // -------------------------------------------------------------------------

  @Test
  void rendersWorkorderLifecycleTemplate() {
    when(wahaTemplateRepository.findByTemplateKey(WahaTemplate.WORKORDER_LIFECYCLE_KEY))
        .thenReturn(Optional.of(new WahaTemplateEntity(UUID.randomUUID(),
            WahaTemplate.WORKORDER_LIFECYCLE_KEY,
            "WO {workOrderId} mesin {machineCode} {eventLabel} ({status}) at {transitionedAt}", now, now)));

    var body = renderer().renderWorkorderLifecycle("WO-2608-00001", "BF-08410", "IN_PROGRESS",
        "Mulai perbaikan", Instant.parse("2026-08-24T12:00:00Z"));

    assertThat(body)
        .contains("WO WO-2608-00001")
        .contains("mesin BF-08410")
        .contains("Mulai perbaikan")
        .contains("IN_PROGRESS")
        .contains("2026-08-24 19:00"); // UTC+7
  }

  @Test
  void rendersWorkorderAckTemplate() {
    when(wahaTemplateRepository.findByTemplateKey(WahaTemplate.WORKORDER_ACK_KEY))
        .thenReturn(Optional.of(new WahaTemplateEntity(UUID.randomUUID(),
            WahaTemplate.WORKORDER_ACK_KEY,
            "WO {workOrderId} mesin {machineCode} deadline {ackDeadline} link {ackLink}", now, now)));

    var body = renderer().renderWorkorderAck("WO-2608-00001", "BF-08410",
        Instant.parse("2026-08-24T16:00:00Z"), "/dashboard/workorders/ack-task-list?token=abc");

    assertThat(body)
        .contains("WO WO-2608-00001")
        .contains("mesin BF-08410")
        .contains("deadline 2026-08-24 23:00")
        .contains("link /dashboard/workorders/ack-task-list?token=abc");
  }
}