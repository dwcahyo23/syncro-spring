package com.syncro.compliance.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.compliance.domain.CalibrationStatus;
import com.syncro.compliance.domain.EightDStatus;
import com.syncro.compliance.domain.EcnStatus;
import com.syncro.compliance.domain.NcStatus;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 15-2 convention proof for {@code com.syncro.compliance.infrastructure.db}
 * (blueprint H1–H7): validates every compliance entity against the V1 schema via
 * context boot and round-trips the NC → 8D chain, calibration pair, ECN/baseline,
 * lesson-learned (JSONB map + tag array), and the historical import record with its
 * raw_payload.
 */
class ComplianceEntityConventionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-08-31T08:00:00Z");
  private static final Instant T1 = Instant.parse("2026-08-31T09:00:00Z");

  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private NonConformanceRepository nonConformances;
  @Autowired
  private EightDReportRepository eightDReports;
  @Autowired
  private CalibrationInstrumentRepository instruments;
  @Autowired
  private CalibrationRecordRepository records;
  @Autowired
  private EquipmentChangeNoticeRepository changeNotices;
  @Autowired
  private MachineSetupBaselineRepository baselines;
  @Autowired
  private LessonLearnedRepository lessons;
  @Autowired
  private HistoricalMachineRecordRepository historicalRecords;

  private record Fixture(PlantEntity plant, MachineEntity machine, AuthUserEntity user) {
  }

  private Fixture fixture() {
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "C" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), "cmp-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.TECHNICIAN, true, T0, T0));
    return new Fixture(plant, machine, user);
  }

  @Test
  void nonConformanceAndEightDChainRoundTrip() {
    var fx = fixture();
    var nc = nonConformances.saveAndFlush(new NonConformanceEntity(
        UUID.randomUUID(), "PRJ-" + UUID.randomUUID().toString().substring(0, 8), null,
        fx.machine().getId(), "NC-" + UUID.randomUUID().toString().substring(0, 8),
        "Dimensional drift on press head", null, null, fx.user().getId(), NcStatus.OPEN,
        LocalDate.of(2026, 10, 15), null, T0, T0));

    var report = eightDReports.saveAndFlush(new EightDReportEntity(
        UUID.randomUUID(), nc.getId(), "8D-" + UUID.randomUUID().toString().substring(0, 8),
        Map.of("members", List.of("Andi", "Budi")), "Customer complaint on burr height",
        "Sorted 200 pcs", Map.of("cause", "worn guide", "verified", true),
        "Replace guide rail", "Rail replaced 2026-09-02", "Guide wear is recurring",
        "Closed after 3 clean lots", EightDStatus.DRAFT, null, null, T0, T0));

    var reloaded = eightDReports.findById(report.getId()).orElseThrow();
    assertThat(reloaded.getReportNumber()).startsWith("8D-");
    assertThat(reloaded.getD1Team()).containsEntry("members", List.of("Andi", "Budi"));
    assertThat(reloaded.getD4RootCause()).containsEntry("verified", true);
    assertThat(reloaded.getStatus()).isEqualTo(EightDStatus.DRAFT);
    assertThat(eightDReports.findByNcId(nc.getId())).contains(reloaded);
    assertThat(eightDReports.findByReportNumber(reloaded.getReportNumber())).contains(reloaded);

    reloaded.verifyEffectiveness(EightDStatus.EFFECTIVE, T1, T1);
    eightDReports.saveAndFlush(reloaded);
    assertThat(eightDReports.findById(report.getId()).orElseThrow().getStatus())
        .isEqualTo(EightDStatus.EFFECTIVE);
    assertThat(eightDReports.findById(report.getId()).orElseThrow().getEffectivenessVerifiedAt())
        .isEqualTo(T1);

    nc.close("worn guide rail", "replaced rail", T1, T1);
    nonConformances.saveAndFlush(nc);
    var reloadedNc = nonConformances.findById(nc.getId()).orElseThrow();
    assertThat(reloadedNc.getStatus()).isEqualTo(NcStatus.OPEN);
    assertThat(reloadedNc.getRootCause()).isEqualTo("worn guide rail");
    assertThat(reloadedNc.getClosedAt()).isEqualTo(T1);
    assertThat(nonConformances.findByNcNumber(reloadedNc.getNcNumber())).contains(reloadedNc);
  }

  @Test
  void calibrationInstrumentAndRecordRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var instrument = instruments.saveAndFlush(new CalibrationInstrumentEntity(
        UUID.randomUUID(), "CAL-" + suffix, "Digital caliper", "Mitutoyo 500-752", "SN-1",
        "QC bench", 180, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 7, 31), "B4T Lab",
        CalibrationStatus.EXPIRED, T0, T0));

    var record = records.saveAndFlush(new CalibrationRecordEntity(
        UUID.randomUUID(), instrument.getId(), LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 7, 31), "B4T Lab", "CERT-" + suffix, "garage://cal/cert.pdf",
        "PASS", "Within tolerance", fx.user().getId(), T0));

    var reloadedInstrument = instruments.findById(instrument.getId()).orElseThrow();
    assertThat(reloadedInstrument.getInstrumentCode()).isEqualTo("CAL-" + suffix);
    assertThat(reloadedInstrument.getStatus()).isEqualTo(CalibrationStatus.EXPIRED);
    assertThat(reloadedInstrument.getNextCalibrationDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    assertThat(instruments.findByInstrumentCode("CAL-" + suffix)).contains(reloadedInstrument);

    var reloadedRecord = records.findById(record.getId()).orElseThrow();
    assertThat(reloadedRecord.getInstrumentId()).isEqualTo(instrument.getId());
    assertThat(reloadedRecord.getCertificateNumber()).isEqualTo("CERT-" + suffix);
    assertThat(reloadedRecord.getNotes()).isEqualTo("Within tolerance");
    assertThat(records.findByInstrumentIdOrderByCalibrationDateDesc(instrument.getId()))
        .containsExactly(reloadedRecord);
  }

  @Test
  void equipmentChangeNoticeAndSetupBaselineRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);

    var ecn = changeNotices.saveAndFlush(new EquipmentChangeNoticeEntity(
        UUID.randomUUID(), "ECN-" + suffix, fx.machine().getId(), "Retrofit safety guard",
        "Add light curtain", "IMPROVEMENT", "Near-miss report Q2", EcnStatus.DRAFT,
        fx.user().getId(), null, null, null, null, null, null, null, T0, T0));
    assertThat(changeNotices.findByEcnNumber("ECN-" + suffix)).contains(ecn);

    ecn.review(EcnStatus.APPROVED, fx.user().getId(), fx.user().getId(),
        LocalDate.of(2026, 9, 1), T1, T1);
    changeNotices.saveAndFlush(ecn);
    var reloadedEcn = changeNotices.findById(ecn.getId()).orElseThrow();
    assertThat(reloadedEcn.getStatus()).isEqualTo(EcnStatus.APPROVED);
    assertThat(reloadedEcn.getApprovedBy()).isEqualTo(fx.user().getId());
    assertThat(reloadedEcn.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 9, 1));

    var baseline = baselines.saveAndFlush(new MachineSetupBaselineEntity(
        UUID.randomUUID(), fx.machine().getId(), ecn.getId(), 1,
        Map.of("clampPressureBar", 6.5, "cycleSeconds", 42), fx.user().getId(), T1, true,
        T0, T0));
    var reloadedBaseline = baselines.findById(baseline.getId()).orElseThrow();
    assertThat(reloadedBaseline.getEcnId()).isEqualTo(ecn.getId());
    assertThat(reloadedBaseline.getVersion()).isEqualTo(1);
    assertThat(reloadedBaseline.getParameters()).containsEntry("cycleSeconds", 42);
    assertThat(reloadedBaseline.isActive()).isTrue();
    assertThat(baselines.findByMachineIdAndVersion(fx.machine().getId(), 1))
        .contains(reloadedBaseline);

    baseline.supersed(fx.user().getId(), T1, T1);
    baselines.saveAndFlush(baseline);
    assertThat(baselines.findById(baseline.getId()).orElseThrow().isActive()).isFalse();
  }

  @Test
  void lessonLearnedAndHistoricalRecordRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);

    var lesson = lessons.saveAndFlush(new LessonLearnedEntity(
        UUID.randomUUID(), "PRJ-" + suffix, fx.machine().getId(), "KAIZEN",
        "Guide rail wear root cause", "Three guide failures in six months",
        "Misalignment after base bolt loosening", "Add quarterly torque check",
        Map.of("items", List.of(Map.of("code", "GR-12", "qty", 2))), 14, 3,
        List.of("mechanical", "alignment"), T0, T0));

    var reloadedLesson = lessons.findById(lesson.getId()).orElseThrow();
    assertThat(reloadedLesson.getProjectId()).isEqualTo("PRJ-" + suffix);
    assertThat(reloadedLesson.getTags()).containsExactly("mechanical", "alignment");
    assertThat(reloadedLesson.getDurationDays()).isEqualTo(14);
    assertThat(reloadedLesson.getReCycleCount()).isEqualTo(3);
    assertThat(lessons.findByProjectId("PRJ-" + suffix)).contains(reloadedLesson);

    var record = historicalRecords.saveAndFlush(new HistoricalMachineRecordEntity(
        UUID.randomUUID(), fx.machine().getId(), "BATCH-" + suffix, "history-2025.csv", 17,
        Instant.parse("2025-03-12T02:30:00Z"), "Spindle vibration", "Bearing wear",
        "contamination", "Full rebuild", "Seal inspection monthly", 240,
        List.of("spindle", "bearing"),
        Map.of("sourceRow", Map.of("cells", List.of("A1", "B2"))), fx.user().getId(), T0));

    var reloadedRecord = historicalRecords.findById(record.getId()).orElseThrow();
    assertThat(reloadedRecord.getImportBatchId()).isEqualTo("BATCH-" + suffix);
    assertThat(reloadedRecord.getSourceRowNumber()).isEqualTo(17);
    assertThat(reloadedRecord.getHappenedAt()).isEqualTo(Instant.parse("2025-03-12T02:30:00Z"));
    assertThat(reloadedRecord.getDowntimeMinutes()).isEqualTo(240);
    assertThat(reloadedRecord.getTags()).containsExactly("spindle", "bearing");
    assertThat(reloadedRecord.getRawPayload()).containsEntry("sourceRow",
        Map.of("cells", List.of("A1", "B2")));
    assertThat(reloadedRecord.getImportedBy()).isEqualTo(fx.user().getId());
  }
}
