package com.syncro.maintenance.preventive.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
import com.syncro.maintenance.preventive.domain.PmScheduleDateStatus;
import com.syncro.maintenance.preventive.domain.PmScheduleStatus;
import com.syncro.maintenance.preventive.domain.PmWorkOrderStatus;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 15-2 convention proof for {@code com.syncro.maintenance.preventive.infrastructure.db}
 * (PM execution model, blueprint F1–F8): validates every PM entity against the V1
 * schema via context boot and round-trips the frequency → checksheet → schedule →
 * date → pm workorder → execution → items chain, including the JSONB warnings map,
 * BigDecimal bounds, and the VARCHAR(50) finding/blocking workorder references.
 */
class PreventiveEntityConventionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-08-31T08:00:00Z");
  private static final Instant T1 = Instant.parse("2026-08-31T09:00:00Z");
  private static final Instant T2 = Instant.parse("2026-08-31T10:00:00Z");

  @Autowired
  private PlantRepository plants;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private PmFrequencyRepository frequencies;
  @Autowired
  private PmChecksheetRepository checksheets;
  @Autowired
  private ActiveChecksheetRepository activeChecksheets;
  @Autowired
  private PmChecklistCategoryRepository checklistCategories;
  @Autowired
  private PmChecklistItemRepository checklistItems;
  @Autowired
  private PmScheduleRepository schedules;
  @Autowired
  private PmScheduleDateRepository scheduleDates;
  @Autowired
  private PmWorkOrderRepository pmWorkOrders;
  @Autowired
  private PmExecutionRepository executions;
  @Autowired
  private PmExecutionItemRepository executionItems;
  @Autowired
  private com.syncro.maintenance.infrastructure.db.WorkOrderRepository workOrders;

  private record Fixture(PlantEntity plant, MachineEntity machine, AuthUserEntity user) {
  }

  private Fixture fixture() {
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "PM" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), "pm-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.TECHNICIAN, true, T0, T0));
    return new Fixture(plant, machine, user);
  }

  @Test
  void frequencyChecksheetAndActivePointerRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);

    var frequency = frequencies.saveAndFlush(new PmFrequencyEntity(
        UUID.randomUUID(), "FQ-" + suffix, "Monthly", null, 1, true, T0, T0));
    assertThat(frequencies.findByCode("FQ-" + suffix)).contains(frequency);

    var checksheet = checksheets.saveAndFlush(new PmChecksheetEntity(
        UUID.randomUUID(), fx.machine().getId(), frequency.getId(), 1, null, true, null, null,
        null, null, fx.user().getId(), T0, T0));

    // Self-reference: revision 2 supersedes revision 1 (plain UUID, AD-3).
    var revision2 = checksheets.saveAndFlush(new PmChecksheetEntity(
        UUID.randomUUID(), fx.machine().getId(), frequency.getId(), 2, "revised", true,
        checksheet.getId(), null, null, null, fx.user().getId(), T1, T1));

    var reloaded = checksheets.findById(revision2.getId()).orElseThrow();
    assertThat(reloaded.getRevisionNo()).isEqualTo(2);
    assertThat(reloaded.getSupersedes()).isEqualTo(checksheet.getId());
    assertThat(checksheets.findByMachineIdAndFrequencyIdAndRevisionNo(
        fx.machine().getId(), frequency.getId(), 2)).contains(reloaded);

    var active = activeChecksheets.saveAndFlush(new ActiveChecksheetEntity(
        new ActiveChecksheetId(fx.machine().getId(), frequency.getId()), revision2.getId()));
    assertThat(activeChecksheets.findById(active.getId()).orElseThrow().getChecksheetId())
        .isEqualTo(revision2.getId());
  }

  @Test
  void checklistCategoryAndItemWithMeasurementBoundsRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var frequency = frequencies.saveAndFlush(new PmFrequencyEntity(
        UUID.randomUUID(), "FQ-" + suffix, "Weekly", null, 2, true, T0, T0));
    var checksheet = checksheets.saveAndFlush(new PmChecksheetEntity(
        UUID.randomUUID(), fx.machine().getId(), frequency.getId(), 1, null, true, null, null,
        null, null, fx.user().getId(), T0, T0));

    var category = checklistCategories.saveAndFlush(new PmChecklistCategoryEntity(
        UUID.randomUUID(), checksheet.getId(), "Mechanical", 1, T0, T0));
    var item = checklistItems.saveAndFlush(new PmChecklistItemEntity(
        UUID.randomUUID(), checksheet.getId(), category.getId(), 1,
        "Oil pressure bar", "gauge", PmItemInputType.MEASUREMENT, "bar",
        new BigDecimal("1.500000"), new BigDecimal("3.000000"), new BigDecimal("4.500000"),
        true, null, null, T0, T0));
    var okNgItem = checklistItems.saveAndFlush(new PmChecklistItemEntity(
        UUID.randomUUID(), checksheet.getId(), null, 2,
        "Guard in place", "visual", PmItemInputType.OK_NG, null, null, null, null,
        false, null, null, T0, T0));

    var reloaded = checklistItems.findById(item.getId()).orElseThrow();
    assertThat(reloaded.getInputType()).isEqualTo(PmItemInputType.MEASUREMENT);
    assertThat(reloaded.getLsl()).isEqualByComparingTo(new BigDecimal("1.5"));
    assertThat(reloaded.getNominal()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(reloaded.getUsl()).isEqualByComparingTo(new BigDecimal("4.5"));
    assertThat(reloaded.isCriticalFlag()).isTrue();
    assertThat(reloaded.getCategoryId()).isEqualTo(category.getId());
    assertThat(checklistItems.findByChecksheetIdOrderBySequenceAsc(checksheet.getId())).hasSize(2);
    assertThat(checklistCategories.findByChecksheetIdOrderBySortOrderAsc(checksheet.getId()))
        .hasSize(1);
    assertThat(checklistItems.findById(okNgItem.getId()).orElseThrow().getUnit()).isNull();
  }

  @Test
  void scheduleWithJsonbWarningsApprovalChainAndDatesRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var frequency = frequencies.saveAndFlush(new PmFrequencyEntity(
        UUID.randomUUID(), "FQ-" + suffix, "Monthly", null, 3, true, T0, T0));
    var checksheet = checksheets.saveAndFlush(new PmChecksheetEntity(
        UUID.randomUUID(), fx.machine().getId(), frequency.getId(), 1, null, true, null, null,
        null, null, fx.user().getId(), T0, T0));

    var schedule = schedules.saveAndFlush(new PmScheduleEntity(
        UUID.randomUUID(), fx.plant().getId(), fx.machine().getId(), checksheet.getId(), 1,
        frequency.getId(), frequency.getCode(), frequency.getName(), 2026,
        PmScheduleStatus.DRAFT, null, null, null, null, null, null,
        Map.of("unmappedMachines", 2, "note", "generated"), T0, T0));

    var warnings = schedules.findById(schedule.getId()).orElseThrow().getWarnings();
    assertThat(warnings).containsEntry("unmappedMachines", 2);

    schedule.approveBySpv(fx.user().getId(), T1, T1);
    schedule.transitionTo(PmScheduleStatus.PENDING_PRODUCTION_APPROVAL, T1);
    schedules.saveAndFlush(schedule);
    schedule.approveByProd(fx.user().getId(), T2, T2);
    schedule.transitionTo(PmScheduleStatus.ACTIVE, T2);
    schedules.saveAndFlush(schedule);

    var reloaded = schedules.findById(schedule.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(PmScheduleStatus.ACTIVE);
    assertThat(reloaded.getApprovedAtSpv()).isEqualTo(T1);
    assertThat(reloaded.getApprovedAtProd()).isEqualTo(T2);
    assertThat(schedules.findByPlantIdAndMachineIdAndChecksheetIdAndYear(
        fx.plant().getId(), fx.machine().getId(), checksheet.getId(), 2026)).contains(reloaded);

    var date = scheduleDates.saveAndFlush(new PmScheduleDateEntity(
        UUID.randomUUID(), schedule.getId(), LocalDate.of(2026, 9, 15),
        PmScheduleDateStatus.SCHEDULED, T0, T0));
    date.transitionTo(PmScheduleDateStatus.EXECUTED, T1);
    scheduleDates.saveAndFlush(date);
    var reloadedDate = scheduleDates.findById(date.getId()).orElseThrow();
    assertThat(reloadedDate.getStatus()).isEqualTo(PmScheduleDateStatus.EXECUTED);
    assertThat(scheduleDates.findByScheduleIdOrderByPlannedDateAsc(schedule.getId())).hasSize(1);
  }

  @Test
  void pmWorkOrderExecutionAndItemsChainRoundTrip() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var frequency = frequencies.saveAndFlush(new PmFrequencyEntity(
        UUID.randomUUID(), "FQ-" + suffix, "Annual", null, 4, true, T0, T0));
    var checksheet = checksheets.saveAndFlush(new PmChecksheetEntity(
        UUID.randomUUID(), fx.machine().getId(), frequency.getId(), 1, null, true, null, null,
        null, null, fx.user().getId(), T0, T0));
    var category = checklistCategories.saveAndFlush(new PmChecklistCategoryEntity(
        UUID.randomUUID(), checksheet.getId(), "Electrical", 1, T0, T0));
    var item = checklistItems.saveAndFlush(new PmChecklistItemEntity(
        UUID.randomUUID(), checksheet.getId(), category.getId(), 1, "Contactor wear",
        "visual", PmItemInputType.OK_NG, null, null, null, null, true, null, null, T0, T0));

    var pmWo = pmWorkOrders.saveAndFlush(new PmWorkOrderEntity(
        UUID.randomUUID(), fx.machine().getId(), checksheet.getId(), frequency.getId(),
        frequency.getCode(), frequency.getName(), 1, PmWorkOrderStatus.SCHEDULED, null,
        LocalDate.of(2026, 9, 1), null, null, null, T0, T0));
    pmWo.assign(fx.user().getId(), T0);
    pmWorkOrders.saveAndFlush(pmWo);
    assertThat(pmWorkOrders.findById(pmWo.getId()).orElseThrow().getStatus())
        .isEqualTo(PmWorkOrderStatus.ASSIGNED);

    var execution = executions.saveAndFlush(new PmExecutionEntity(
        UUID.randomUUID(), pmWo.getId(), null, fx.user().getId(), null, null, null, null, null,
        T0, null, false, 0, null, T0, T0));
    assertThat(executions.findByPmWoId(pmWo.getId())).contains(execution);

    // NG item with snapshot text; a second blocked item references a real corrective WO.
    var ngItem = executionItems.saveAndFlush(new PmExecutionItemEntity(
        UUID.randomUUID(), execution.getId(), item.getId(), 1, category.getName(),
        item.getParameterText(), item.getCheckMethod(), PmItemInputType.OK_NG, true,
        null, null, null, null, null, Boolean.FALSE, true, "Contactor pitted", null,
        false, null, null, null, null, null, null, null, null, T1, T1));

    var correctiveWoId = "WO-" + UUID.randomUUID().toString().substring(0, 8);
    workOrders.saveAndFlush(new com.syncro.maintenance.infrastructure.db.WorkOrderEntity(
        correctiveWoId, "INTERNAL", null,
        com.syncro.maintenance.domain.workorder.WorkOrderStatus.OPEN, null, fx.machine().getId(),
        "PM finding follow-up", 0L, null, null, null, T0, T0));
    var blockedItem = executionItems.saveAndFlush(new PmExecutionItemEntity(
        UUID.randomUUID(), execution.getId(), null, 2, null, "Follow-up", null,
        PmItemInputType.OK_NG, false, null, null, null, null, null, null, false, null, null,
        true, correctiveWoId, correctiveWoId, null, null, null, null, null, null, T1, T1));

    ngItem.fill(null, Boolean.FALSE, true, "Contactor pitted", false, null, null, T1, T1);
    executionItems.saveAndFlush(ngItem);
    execution.complete(T2, true, 1, T2);
    execution.verifyBySpv(fx.user().getId(), UUID.randomUUID(), T2, T2);
    executions.saveAndFlush(execution);

    var reloadedExecution = executions.findById(execution.getId()).orElseThrow();
    assertThat(reloadedExecution.hasNgItems()).isTrue();
    assertThat(reloadedExecution.getNgCount()).isEqualTo(1);
    assertThat(reloadedExecution.getCompletedAt()).isEqualTo(T2);
    assertThat(reloadedExecution.getSpvSignedAt()).isEqualTo(T2);

    var reloadedNg = executionItems.findById(ngItem.getId()).orElseThrow();
    assertThat(reloadedNg.isNg()).isTrue();
    assertThat(reloadedNg.getOk()).isFalse();
    assertThat(reloadedNg.getFilledAt()).isEqualTo(T1);
    assertThat(reloadedNg.getCategoryName()).isEqualTo(category.getName());

    var reloadedBlocked = executionItems.findById(blockedItem.getId()).orElseThrow();
    assertThat(reloadedBlocked.isBlocked()).isTrue();
    assertThat(reloadedBlocked.getBlockingWoId()).isEqualTo(correctiveWoId);
    assertThat(reloadedBlocked.getBlockedWoCode()).isEqualTo(correctiveWoId);
    assertThat(executionItems.findByExecutionIdOrderBySequenceAsc(execution.getId())).hasSize(2);
  }
}
