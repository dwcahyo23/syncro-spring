package com.syncro.maintenance.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.domain.workorder.WorkAssignmentParentType;
import com.syncro.maintenance.domain.workorder.WorkLogStoppedReason;
import com.syncro.maintenance.domain.workorder.WorkRatingStatus;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 15-2 convention proof for {@code com.syncro.maintenance.infrastructure.db}
 * (work_assignments, work_logs, rating criteria/quality ratings): validates every new
 * entity against the V1 schema via context boot and round-trips the assignment →
 * log → rating chain, including a required-column rejection from the I/O matrix.
 */
class MaintenanceEntityConventionIntegrationTest extends AbstractPostgresIntegrationTest {

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
  private WorkOrderRepository workOrders;
  @Autowired
  private WorkAssignmentRepository workAssignments;
  @Autowired
  private WorkLogRepository workLogs;
  @Autowired
  private WorkLogRatingCriterionRepository logCriteria;
  @Autowired
  private WorkLogRatingRepository workLogRatings;
  @Autowired
  private WorkOrderRatingCriterionRepository woCriteria;
  @Autowired
  private WorkOrderRatingCriterionCategoryRepository woCriterionCategories;
  @Autowired
  private WorkOrderQualityRatingRepository qualityRatings;
  @Autowired
  private WorkOrderQualityRatingTechnicianRepository qualityRatingTechnicians;
  @Autowired
  private WorkOrderQualityRatingScoreRepository qualityRatingScores;
  @Autowired
  private WorkOrderCategoryRepository workOrderCategories;

  private MachineEntity machine() {
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "M" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    return machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group,
        "MC-" + UUID.randomUUID().toString().substring(0, 8), "Machine",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null, T0, T0));
  }

  private AuthUserEntity user() {
    return users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), "u-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.TECHNICIAN, true, T0, T0));
  }

  private String workOrder(MachineEntity machine) {
    var wo = new WorkOrderEntity("WO-TEST-" + UUID.randomUUID().toString().substring(0, 8),
        "INTERNAL", null, com.syncro.maintenance.domain.workorder.WorkOrderStatus.OPEN,
        null, machine.getId(), "test", 0L, null, null, null, T0, T0);
    workOrders.saveAndFlush(wo);
    return wo.getId();
  }

  @Test
  void workAssignmentRoundTripWithEnumAndDrop() {
    var machine = machine();
    var woId = workOrder(machine);
    var technician = user();
    var lead = user();

    var assignment = workAssignments.saveAndFlush(new WorkAssignmentEntity(
        UUID.randomUUID(), WorkAssignmentParentType.CORRECTIVE_WO, woId, technician.getId(),
        lead.getId(), T0, null, null, true, T0, T0));
    assignment.drop(lead.getId(), T1, T1);
    workAssignments.saveAndFlush(assignment);

    var reloaded = workAssignments.findById(assignment.getId()).orElseThrow();
    assertThat(reloaded.getParentType()).isEqualTo(WorkAssignmentParentType.CORRECTIVE_WO);
    assertThat(reloaded.getWorkOrderId()).isEqualTo(woId);
    assertThat(reloaded.getTechnicianId()).isEqualTo(technician.getId());
    assertThat(reloaded.getAssignedAt()).isEqualTo(T0);
    assertThat(reloaded.getDroppedAt()).isEqualTo(T1);
    assertThat(reloaded.getDroppedBy()).isEqualTo(lead.getId());
    assertThat(reloaded.isActive()).isFalse();
    assertThat(workAssignments.findByWorkOrderIdOrderByAssignedAtAsc(woId)).hasSize(1);
  }

  @Test
  void workLogRoundTripWithEnumStopReasonAndRequiredNote() {
    var machine = machine();
    var woId = workOrder(machine);
    var technician = user();

    var log = workLogs.saveAndFlush(new WorkLogEntity(
        UUID.randomUUID(), null, woId, technician.getId(), T0, null, null,
        "Checked hydraulic pressure", null, null, T0, T0));
    log.stop(T1, WorkLogStoppedReason.SHIFT_END, T1);
    workLogs.saveAndFlush(log);

    var reloaded = workLogs.findById(log.getId()).orElseThrow();
    assertThat(reloaded.getWorkOrderId()).isEqualTo(woId);
    assertThat(reloaded.getTechnicianId()).isEqualTo(technician.getId());
    assertThat(reloaded.getStartTime()).isEqualTo(T0);
    assertThat(reloaded.getEndTime()).isEqualTo(T1);
    assertThat(reloaded.getStoppedReason()).isEqualTo(WorkLogStoppedReason.SHIFT_END);
    assertThat(reloaded.getActivityNote()).isEqualTo("Checked hydraulic pressure");
    assertThat(reloaded.getCompletionNote()).isNull();
    assertThat(workLogs.findByWorkOrderIdOrderByStartTimeAsc(woId)).hasSize(1);

    // I/O matrix: the NOT NULL activity_note rejects an insert without it.
    var blank = new WorkLogEntity(UUID.randomUUID(), null, woId, technician.getId(),
        T0, null, null, null, null, null, T0, T0);
    assertThatThrownBy(() -> workLogs.saveAndFlush(blank))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void workLogRatingCriterionAndRatingRoundTrip() {
    var machine = machine();
    var woId = workOrder(machine);
    var technician = user();
    var rater = user();

    var criterion = logCriteria.saveAndFlush(new WorkLogRatingCriterionEntity(
        UUID.randomUUID(), "Thoroughness", "desc", 1, 5, null, true, 1, null, T0, T0));
    var log = workLogs.saveAndFlush(new WorkLogEntity(
        UUID.randomUUID(), null, woId, technician.getId(), T0, T1,
        WorkLogStoppedReason.COMPLETED, "Replaced seal", "done", null, T0, T0));
    var rating = workLogRatings.saveAndFlush(new WorkLogRatingEntity(
        UUID.randomUUID(), log.getId(), criterion.getId(), 4, rater.getId(), T1, "good"));

    var reloaded = workLogRatings.findById(rating.getId()).orElseThrow();
    assertThat(reloaded.getWorkLogId()).isEqualTo(log.getId());
    assertThat(reloaded.getCriterionId()).isEqualTo(criterion.getId());
    assertThat(reloaded.getScore()).isEqualTo(4);
    assertThat(reloaded.getRatedBy()).isEqualTo(rater.getId());
    assertThat(reloaded.getRatedAt()).isEqualTo(T1);
    assertThat(reloaded.getRemarks()).isEqualTo("good");
  }

  @Test
  void workOrderQualityRatingChainRoundTrip() {
    var machine = machine();
    var woId = workOrder(machine);
    var technician = user();
    var submitter = user();

    var criterion = woCriteria.saveAndFlush(new WorkOrderRatingCriterionEntity(
        UUID.randomUUID(), "Tidiness", null, 1, 5, null, true, 1, null, T0, T0));

    var rating = qualityRatings.saveAndFlush(new WorkOrderQualityRatingEntity(
        UUID.randomUUID(), woId, WorkRatingStatus.PENDING, T1, null, null, null, null, null,
        null, T0, T0));

    var membership = qualityRatingTechnicians.saveAndFlush(
        new WorkOrderQualityRatingTechnicianEntity(
            UUID.randomUUID(), rating.getId(), null, technician.getId()));

    var score = qualityRatingScores.saveAndFlush(new WorkOrderQualityRatingScoreEntity(
        UUID.randomUUID(), rating.getId(), criterion.getId(), 5));

    rating.submit(4, 5, 3, submitter.getId(), T2, T2);
    qualityRatings.saveAndFlush(rating);

    var reloaded = qualityRatings.findById(rating.getId()).orElseThrow();
    assertThat(reloaded.getWorkOrderId()).isEqualTo(woId);
    assertThat(reloaded.getStatus()).isEqualTo(WorkRatingStatus.SUBMITTED);
    assertThat(reloaded.getSubmittedAt()).isEqualTo(T2);
    assertThat(reloaded.getSubmittedBy()).isEqualTo(submitter.getId());
    assertThat(reloaded.getCleanlinessScore()).isEqualTo(4);
    assertThat(reloaded.getTidinessScore()).isEqualTo(5);
    assertThat(reloaded.getSpeedScore()).isEqualTo(3);
    assertThat(qualityRatings.findByWorkOrderId(woId)).contains(reloaded);

    assertThat(qualityRatingTechnicians.findById(membership.getId()).orElseThrow().getTechnicianId())
        .isEqualTo(technician.getId());
    assertThat(qualityRatingScores.findById(score.getId()).orElseThrow().getScore()).isEqualTo(5);

    // Criterion categories pivot round-trip.
    var category = workOrderCategories.saveAndFlush(new WorkOrderCategoryEntity(
        UUID.randomUUID(), "CAT-" + UUID.randomUUID().toString().substring(0, 8), "Mechanical",
        null, T0, T0));
    var pivot = woCriterionCategories.saveAndFlush(
        new WorkOrderRatingCriterionCategoryEntity(UUID.randomUUID(), criterion.getId(),
            category.getId()));
    assertThat(woCriterionCategories.findById(pivot.getId()).orElseThrow().getCriterionId())
        .isEqualTo(criterion.getId());
  }

  @Test
  void bigDecimalInstantAndNullSemanticsSurvive() {
    var machine = machine();
    var woId = workOrder(machine);
    var technician = user();

    var criterion = woCriteria.saveAndFlush(new WorkOrderRatingCriterionEntity(
        UUID.randomUUID(), "Speed", null, 1, 5, null, true, 2, null, T0, T0));
    var log = workLogs.saveAndFlush(new WorkLogEntity(
        UUID.randomUUID(), null, woId, technician.getId(), T0, T1,
        WorkLogStoppedReason.WAITING_SPAREPART, "Waited", null, "notes", T0, T0));

    var rating = workLogRatings.saveAndFlush(new WorkLogRatingEntity(
        UUID.randomUUID(), log.getId(), criterion.getId(), 2, null, T1, null));

    var reloaded = workLogRatings.findById(rating.getId()).orElseThrow();
    assertThat(reloaded.getRatedBy()).isNull();
    assertThat(reloaded.getRemarks()).isNull();
    assertThat(BigDecimal.valueOf(2)).isEqualByComparingTo(BigDecimal.valueOf(reloaded.getScore()));
  }
}
