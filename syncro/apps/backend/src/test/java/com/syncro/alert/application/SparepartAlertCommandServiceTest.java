package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.alert.application.SparepartAlertCommandService.AlertInvalidTransitionException;
import com.syncro.alert.application.SparepartAlertQueryService.AlertNotFoundException;
import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.domain.SparepartAlertType;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class SparepartAlertCommandServiceTest {

  @Mock private SparepartAlertRepository alertRepository;
  @Mock private AuditLogWriter auditLogWriter;
  @Mock private AuthUserPlantAssignmentRepository assignments;
  @Mock private NotificationJobRepository notificationJobRepository;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneOffset.UTC);

  private SparepartAlertCommandService service() {
    return new SparepartAlertCommandService(
        alertRepository, auditLogWriter, assignments, notificationJobRepository, clock);
  }

  // --- helpers ---

  private AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin", ApplicationRole.SUPER_ADMIN);
  }

  private AuthenticatedUser manageUser(UUID userId) {
    return new AuthenticatedUser(userId.toString(), "manager", ApplicationRole.MANAGE);
  }

  private PlantEntity plant(UUID plantId) {
    return new PlantEntity(plantId, "P1", "Plant 1", Instant.now(clock), Instant.now(clock));
  }

  private MachineGroupEntity machineGroup(UUID groupId, PlantEntity plant) {
    return new MachineGroupEntity(groupId, plant, "Group 1", Instant.now(clock), Instant.now(clock));
  }

  private MachineEntity machine(UUID machineId, PlantEntity plant, MachineGroupEntity group) {
    return new MachineEntity(machineId, plant, group, "M1", "Machine 1",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null,
        Instant.now(clock), Instant.now(clock));
  }

  private SparepartEntity sparepart(UUID sparepartId) {
    return new SparepartEntity(sparepartId, "SP-001", "Sparepart 1", null, null, null, null, null,
        Instant.now(clock), Instant.now(clock));
  }

  private MachineSparepartInstallationEntity installation(UUID installationId,
      MachineEntity machine, SparepartEntity sparepart) {
    return new MachineSparepartInstallationEntity(
        installationId, machine, sparepart, "func", 1000L, 0L, 90,
        Instant.now(clock), Instant.now(clock), Instant.now(clock));
  }

  private SparepartAlertEntity alert(UUID alertId, MachineSparepartInstallationEntity installation,
      SparepartAlertStatus status) {
    var alert = new SparepartAlertEntity(
        alertId,
        installation.getMachine().getId(),
        installation.getId(),
        SparepartAlertType.THRESHOLD_PERCENTAGE,
        90,
        950L,
        950L,
        new BigDecimal("95.00"),
        "trace-001",
        status,
        null,
        Instant.now(clock),
        Instant.now(clock));
    try {
      var field = SparepartAlertEntity.class.getDeclaredField("installation");
      field.setAccessible(true);
      field.set(alert, installation);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return alert;
  }

  // --- acknowledge tests ---

  @Test
  void acknowledge_superAdmin_openAlert_transitionsToAcknowledged() {
    var plantId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var p = plant(plantId);
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().acknowledge(superAdmin(), alertId, "Scheduled maintenance");

    assertThat(a.getStatus()).isEqualTo(SparepartAlertStatus.ACKNOWLEDGED);
    assertThat(a.getStatusReason()).isEqualTo("Scheduled maintenance");
    assertThat(a.getUpdatedAt()).isEqualTo(Instant.now(clock));
    verify(alertRepository).save(a);
  }

  @Test
  void acknowledge_superAdmin_writesAuditEvent() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(any())).thenReturn(a);

    var user = superAdmin();
    service().acknowledge(user, alertId, "reason");

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLogWriter).recordSystem(captor.capture());
    var record = captor.getValue();
    assertThat(record.entityId()).isEqualTo(alertId);
    assertThat(record.previousValue()).containsEntry("status", "OPEN");
    assertThat(record.newValue()).containsEntry("actorId", user.id());
    assertThat(record.newValue()).containsEntry("status", "ACKNOWLEDGED");
  }

  @Test
  void acknowledge_managedUser_scopedToPlant_succeeds() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var p = plant(plantId);
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, Instant.now(clock))));
    when(alertRepository.findByIdWithDetailsScopedToPlants(alertId, List.of(plantId), null))
        .thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().acknowledge(manageUser(userId), alertId, null);

    assertThat(a.getStatus()).isEqualTo(SparepartAlertStatus.ACKNOWLEDGED);
    verify(alertRepository).save(a);
  }

  @Test
  void acknowledge_managedUser_noPlantAssignment_throwsNotFound() {
    var userId = UUID.randomUUID();
    var alertId = UUID.randomUUID();

    when(assignments.findByAuthUserId(userId)).thenReturn(List.of());

    assertThatThrownBy(() -> service().acknowledge(manageUser(userId), alertId, null))
        .isInstanceOf(AlertNotFoundException.class);
    verify(alertRepository, never()).save(any());
  }

  @Test
  void acknowledge_alertNotFound_throwsNotFound() {
    var alertId = UUID.randomUUID();

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().acknowledge(superAdmin(), alertId, null))
        .isInstanceOf(AlertNotFoundException.class);
    verify(alertRepository, never()).save(any());
  }

  @Test
  void acknowledge_alreadyAcknowledged_throwsInvalidTransition() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.ACKNOWLEDGED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().acknowledge(superAdmin(), alertId, null))
        .isInstanceOf(AlertInvalidTransitionException.class)
        .satisfies(ex -> {
          var t = (AlertInvalidTransitionException) ex;
          assertThat(t.getFrom()).isEqualTo(SparepartAlertStatus.ACKNOWLEDGED);
          assertThat(t.getTo()).isEqualTo(SparepartAlertStatus.ACKNOWLEDGED);
        });
    verify(alertRepository, never()).save(any());
  }

  @Test
  void acknowledge_resolvedAlert_throwsInvalidTransition() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.RESOLVED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().acknowledge(superAdmin(), alertId, null))
        .isInstanceOf(AlertInvalidTransitionException.class)
        .satisfies(ex -> {
          var t = (AlertInvalidTransitionException) ex;
          assertThat(t.getFrom()).isEqualTo(SparepartAlertStatus.RESOLVED);
        });
    verify(alertRepository, never()).save(any());
  }

  @Test
  void acknowledge_optimisticLockConflict_propagatesUnwrapped() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(any())).thenThrow(new ObjectOptimisticLockingFailureException(
        SparepartAlertEntity.class, alertId));

    assertThatThrownBy(() -> service().acknowledge(superAdmin(), alertId, "reason"))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void acknowledge_nullReason_allowed() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().acknowledge(superAdmin(), alertId, null);

    assertThat(a.getStatus()).isEqualTo(SparepartAlertStatus.ACKNOWLEDGED);
    assertThat(a.getStatusReason()).isNull();
  }

  // --- acknowledge cancellation tests ---

  @Test
  void acknowledge_cancelsActiveNotificationJobs() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().acknowledge(superAdmin(), alertId, "reason");

    verify(notificationJobRepository).cancelActiveForAlert(
        alertId,
        List.of(NotificationJobStatus.PENDING, NotificationJobStatus.SENT, NotificationJobStatus.RATE_LIMITED),
        NotificationJobStatus.CANCELLED,
        Instant.now(clock));
  }

  @Test
  void acknowledge_writesEscalationCancelledCountInAudit() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(any())).thenReturn(a);
    when(notificationJobRepository.cancelActiveForAlert(any(), any(), any(), any())).thenReturn(2);

    var user = superAdmin();
    service().acknowledge(user, alertId, "reason");

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLogWriter).recordSystem(captor.capture());
    var record = captor.getValue();
    assertThat(record.newValue()).containsEntry("escalationCancelledCount", 2);
  }

  @Test
  void acknowledge_zeroCancelledJobs_stillWritesAudit() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(any())).thenReturn(a);
    when(notificationJobRepository.cancelActiveForAlert(any(), any(), any(), any())).thenReturn(0);

    var user = superAdmin();
    service().acknowledge(user, alertId, "reason");

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLogWriter).recordSystem(captor.capture());
    var record = captor.getValue();
    assertThat(record.newValue()).containsEntry("escalationCancelledCount", 0);
  }

  @Test
  void acknowledge_invalidTransition_doesNotCancelJobs() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.ACKNOWLEDGED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().acknowledge(superAdmin(), alertId, null))
        .isInstanceOf(AlertInvalidTransitionException.class);

    verify(notificationJobRepository, never()).cancelActiveForAlert(any(), any(), any(), any());
  }

  // --- resolve tests ---

  @Test
  void resolve_superAdmin_acknowledgedAlert_transitionsToResolved() {
    var plantId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var p = plant(plantId);
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.ACKNOWLEDGED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().resolve(superAdmin(), alertId, "Replaced sparepart");

    assertThat(a.getStatus()).isEqualTo(SparepartAlertStatus.RESOLVED);
    assertThat(a.getStatusReason()).isEqualTo("Replaced sparepart");
    assertThat(a.getUpdatedAt()).isEqualTo(Instant.now(clock));
    verify(alertRepository).save(a);
  }

  @Test
  void resolve_superAdmin_writesAuditEvent() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.ACKNOWLEDGED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(any())).thenReturn(a);

    var user = superAdmin();
    service().resolve(user, alertId, "reason");

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLogWriter).recordSystem(captor.capture());
    var record = captor.getValue();
    assertThat(record.entityId()).isEqualTo(alertId);
    assertThat(record.previousValue()).containsEntry("status", "ACKNOWLEDGED");
    assertThat(record.newValue()).containsEntry("actorId", user.id());
    assertThat(record.newValue()).containsEntry("status", "RESOLVED");
  }

  @Test
  void resolve_managedUser_scopedToPlant_succeeds() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var p = plant(plantId);
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.ACKNOWLEDGED);

    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, Instant.now(clock))));
    when(alertRepository.findByIdWithDetailsScopedToPlants(alertId, List.of(plantId), null))
        .thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().resolve(manageUser(userId), alertId, null);

    assertThat(a.getStatus()).isEqualTo(SparepartAlertStatus.RESOLVED);
    verify(alertRepository).save(a);
  }

  @Test
  void resolve_managedUser_wrongPlant_throwsNotFound() {
    var userId = UUID.randomUUID();
    var userPlantId = UUID.randomUUID();   // plant the user has access to
    var alertPlantId = UUID.randomUUID();  // different plant the alert belongs to
    var alertId = UUID.randomUUID();
    var p = plant(alertPlantId);
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);

    // user is assigned only to userPlantId, not alertPlantId
    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, userPlantId, Instant.now(clock))));
    // scoped query returns empty — alert not in user's plant
    when(alertRepository.findByIdWithDetailsScopedToPlants(alertId, List.of(userPlantId), null))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().resolve(manageUser(userId), alertId, null))
        .isInstanceOf(AlertNotFoundException.class);
    verify(alertRepository, never()).save(any());
  }

  @Test
  void resolve_openAlert_throwsInvalidTransition() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().resolve(superAdmin(), alertId, null))
        .isInstanceOf(AlertInvalidTransitionException.class)
        .satisfies(ex -> {
          var t = (AlertInvalidTransitionException) ex;
          assertThat(t.getFrom()).isEqualTo(SparepartAlertStatus.OPEN);
          assertThat(t.getTo()).isEqualTo(SparepartAlertStatus.RESOLVED);
        });
    verify(alertRepository, never()).save(any());
  }

  @Test
  void resolve_alreadyResolved_throwsInvalidTransition() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.RESOLVED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().resolve(superAdmin(), alertId, null))
        .isInstanceOf(AlertInvalidTransitionException.class)
        .satisfies(ex -> {
          var t = (AlertInvalidTransitionException) ex;
          assertThat(t.getFrom()).isEqualTo(SparepartAlertStatus.RESOLVED);
        });
    verify(alertRepository, never()).save(any());
  }

  // --- resolveOverride tests ---

  @Test
  void resolveOverride_superAdmin_openAlert_transitionsToResolved() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(a)).thenReturn(a);

    service().resolveOverride(superAdmin(), alertId, "Urgent override");

    assertThat(a.getStatus()).isEqualTo(SparepartAlertStatus.RESOLVED);
    assertThat(a.getStatusReason()).isEqualTo("Urgent override");
    assertThat(a.getUpdatedAt()).isEqualTo(Instant.now(clock));
    verify(alertRepository).save(a);
  }

  @Test
  void resolveOverride_nonSuperAdmin_throwsForbidden() {
    var userId = UUID.randomUUID();
    var alertId = UUID.randomUUID();

    assertThatThrownBy(() -> service().resolveOverride(manageUser(userId), alertId, null))
        .isInstanceOf(SparepartAlertCommandService.AlertForbiddenException.class);
    verify(alertRepository, never()).findByIdWithDetails(any());
    verify(alertRepository, never()).save(any());
  }

  @Test
  void resolveOverride_acknowledgedAlert_throwsInvalidTransition() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.ACKNOWLEDGED);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    assertThatThrownBy(() -> service().resolveOverride(superAdmin(), alertId, null))
        .isInstanceOf(AlertInvalidTransitionException.class)
        .satisfies(ex -> {
          var t = (AlertInvalidTransitionException) ex;
          assertThat(t.getFrom()).isEqualTo(SparepartAlertStatus.ACKNOWLEDGED);
          assertThat(t.getTo()).isEqualTo(SparepartAlertStatus.RESOLVED);
        });
    verify(alertRepository, never()).save(any());
  }

  @Test
  void resolveOverride_writesAuditEvent() {
    var alertId = UUID.randomUUID();
    var p = plant(UUID.randomUUID());
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));
    when(alertRepository.save(any())).thenReturn(a);

    var user = superAdmin();
    service().resolveOverride(user, alertId, null);

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLogWriter).recordSystem(captor.capture());
    var record = captor.getValue();
    assertThat(record.entityId()).isEqualTo(alertId);
    assertThat(record.previousValue()).containsEntry("status", "OPEN");
    assertThat(record.newValue()).containsEntry("actorId", user.id());
    assertThat(record.newValue()).containsEntry("status", "RESOLVED");
  }

  @Test
  void acknowledge_managedUser_wrongPlant_throwsNotFound() {
    var userId = UUID.randomUUID();
    var userPlantId = UUID.randomUUID();   // plant the user has access to
    var alertPlantId = UUID.randomUUID();  // different plant the alert belongs to
    var alertId = UUID.randomUUID();
    var p = plant(alertPlantId);
    var g = machineGroup(UUID.randomUUID(), p);
    var m = machine(UUID.randomUUID(), p, g);
    var sp = sparepart(UUID.randomUUID());
    var inst = installation(UUID.randomUUID(), m, sp);
    var a = alert(alertId, inst, SparepartAlertStatus.OPEN);

    // user is assigned only to userPlantId, not alertPlantId
    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, userPlantId, Instant.now(clock))));
    // scoped query returns empty — alert not in user's plant
    when(alertRepository.findByIdWithDetailsScopedToPlants(alertId, List.of(userPlantId), null))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().acknowledge(manageUser(userId), alertId, null))
        .isInstanceOf(AlertNotFoundException.class);
    verify(alertRepository, never()).save(any());
  }
}
