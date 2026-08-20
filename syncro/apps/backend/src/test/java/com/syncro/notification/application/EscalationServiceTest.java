package com.syncro.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

  @Mock private NotificationJobRepository notificationJobRepository;
  @Mock private SparepartAlertRepository sparepartAlertRepository;
  @Mock private MachineResponsibilityRepository machineResponsibilityRepository;
  @Mock private AuthUserRepository authUserRepository;

  private Clock clock;
  private EscalationService service;

  private static final Instant FIXED_NOW = Instant.parse("2026-08-20T10:00:00Z");
  private static final UUID ALERT_ID = UUID.randomUUID();
  private static final UUID MACHINE_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();
  private static final String TRACE_ID = "trace-5-4";
  private static final String PHONE = "6281234567890";

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    service = new EscalationService(
        notificationJobRepository, sparepartAlertRepository,
        machineResponsibilityRepository, authUserRepository, clock);
  }

  // ---- helpers ----

  private NotificationJobEntity sentJob(String level) {
    var job = new NotificationJobEntity(
        ALERT_ID, level, NotificationJobStatus.SENT,
        USER_ID, PHONE, ALERT_ID + "::" + level, TRACE_ID, null);
    job.markSent(FIXED_NOW.minusSeconds(1000));
    return job;
  }

  private SparepartAlertEntity openAlert() {
    return new SparepartAlertEntity(
        ALERT_ID, MACHINE_ID, UUID.randomUUID(),
        80, 1000L, 800L, BigDecimal.valueOf(80.00),
        TRACE_ID, SparepartAlertStatus.OPEN, null,
        FIXED_NOW.minusSeconds(3600), FIXED_NOW.minusSeconds(3600));
  }

  private MachineResponsibilityEntity responsibility(ResponsibilityLevel level) {
    return new MachineResponsibilityEntity(
        UUID.randomUUID(), MACHINE_ID, USER_ID, level,
        FIXED_NOW.minusSeconds(7200), FIXED_NOW.minusSeconds(7200));
  }

  private AuthUserEntity userWithPhoneViaReflection(String phone) throws Exception {
    var user = new AuthUserEntity(
        USER_ID, "user@test.com", "hash",
        com.syncro.auth.domain.ApplicationRole.MANAGE, true,
        FIXED_NOW.minusSeconds(7200), FIXED_NOW.minusSeconds(7200));
    var field = AuthUserEntity.class.getDeclaredField("whatsappNumber");
    field.setAccessible(true);
    field.set(user, phone);
    return user;
  }

  // ---- Tests: happy path ----

  @Test
  void escalate_fromTechnicianWithStaffRecipient_queuesStaffJobAndMarksEscalated() throws Exception {
    var job = sentJob("TECHNICIAN");
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(openAlert()));
    when(machineResponsibilityRepository
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(MACHINE_ID, ResponsibilityLevel.STAFF))
        .thenReturn(Optional.of(responsibility(ResponsibilityLevel.STAFF)));
    when(authUserRepository.findById(USER_ID))
        .thenReturn(Optional.of(userWithPhoneViaReflection(PHONE)));
    when(notificationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.escalate(job);

    // Verify new PENDING job saved for STAFF
    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    var savedJobs = captor.getAllValues();
    var newJob = savedJobs.get(0);
    assertThat(newJob.getEscalationLevel()).isEqualTo("STAFF");
    assertThat(newJob.getStatus()).isEqualTo(NotificationJobStatus.PENDING);
    assertThat(newJob.getRecipientPhone()).isEqualTo(PHONE);
    assertThat(newJob.getIdempotencyKey()).isEqualTo(ALERT_ID + "::STAFF");

    // Verify current job marked ESCALATED
    var escalatedJob = savedJobs.get(1);
    assertThat(escalatedJob.getStatus()).isEqualTo(NotificationJobStatus.ESCALATED);
  }

  // ---- Tests: end of chain ----

  @Test
  void escalate_atManagerLevel_marksEscalatedWithoutQueuingNewJob() {
    var job = sentJob("MANAGER");
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(openAlert()));
    when(notificationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.escalate(job);

    // Only one save — the current job marked ESCALATED, no new job
    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationJobStatus.ESCALATED);
    verify(machineResponsibilityRepository, never())
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(any(), any());
  }

  // ---- Tests: alert no longer OPEN (AC: 10) ----

  @Test
  void escalate_whenAlertAcknowledged_skipsWithoutMutatingJob() {
    var job = sentJob("TECHNICIAN");
    var alert = new SparepartAlertEntity(
        ALERT_ID, MACHINE_ID, UUID.randomUUID(), 80, 1000L, 800L,
        BigDecimal.valueOf(80.00), TRACE_ID, SparepartAlertStatus.ACKNOWLEDGED,
        null, FIXED_NOW.minusSeconds(3600), FIXED_NOW.minusSeconds(3600));
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert));

    service.escalate(job);

    verify(notificationJobRepository, never()).save(any());
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.SENT);
  }

  @Test
  void escalate_whenAlertResolved_skipsWithoutMutatingJob() {
    var job = sentJob("STAFF");
    var alert = new SparepartAlertEntity(
        ALERT_ID, MACHINE_ID, UUID.randomUUID(), 80, 1000L, 800L,
        BigDecimal.valueOf(80.00), TRACE_ID, SparepartAlertStatus.RESOLVED,
        "resolved", FIXED_NOW.minusSeconds(3600), FIXED_NOW.minusSeconds(3600));
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(alert));

    service.escalate(job);

    verify(notificationJobRepository, never()).save(any());
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.SENT);
  }

  // ---- Tests: routing failure — no assignment ----

  @Test
  void escalate_whenNoNextLevelAssignment_savesRoutingFailedJobAndMarksEscalated() {
    var job = sentJob("TECHNICIAN");
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(openAlert()));
    when(machineResponsibilityRepository
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(MACHINE_ID, ResponsibilityLevel.STAFF))
        .thenReturn(Optional.empty());
    when(notificationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.escalate(job);

    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    var routingFailedJob = captor.getAllValues().get(0);
    assertThat(routingFailedJob.getStatus()).isEqualTo(NotificationJobStatus.ROUTING_FAILED);
    assertThat(routingFailedJob.getEscalationLevel()).isEqualTo("STAFF");
    assertThat(routingFailedJob.getErrorDetail()).contains("No STAFF assignment");

    var escalatedJob = captor.getAllValues().get(1);
    assertThat(escalatedJob.getStatus()).isEqualTo(NotificationJobStatus.ESCALATED);
  }

  // ---- Tests: routing failure — no phone ----

  @Test
  void escalate_whenNextLevelUserHasNoPhone_savesRoutingFailedJobAndMarksEscalated() throws Exception {
    var job = sentJob("TECHNICIAN");
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(openAlert()));
    when(machineResponsibilityRepository
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(MACHINE_ID, ResponsibilityLevel.STAFF))
        .thenReturn(Optional.of(responsibility(ResponsibilityLevel.STAFF)));
    // User with null whatsappNumber (no reflection needed — field stays null by default)
    var userNoPhone = new AuthUserEntity(
        USER_ID, "staff@test.com", "hash",
        com.syncro.auth.domain.ApplicationRole.MANAGE, true,
        FIXED_NOW.minusSeconds(7200), FIXED_NOW.minusSeconds(7200));
    when(authUserRepository.findById(USER_ID)).thenReturn(Optional.of(userNoPhone));
    when(notificationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.escalate(job);

    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    var routingFailedJob = captor.getAllValues().get(0);
    assertThat(routingFailedJob.getStatus()).isEqualTo(NotificationJobStatus.ROUTING_FAILED);
    assertThat(routingFailedJob.getErrorDetail()).contains("no whatsappNumber");
  }

  // ---- Tests: duplicate insert swallowed ----

  @Test
  void escalate_whenDuplicateJobInsert_swallowsDataIntegrityViolation() throws Exception {
    var job = sentJob("TECHNICIAN");
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.of(openAlert()));
    when(machineResponsibilityRepository
        .findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(MACHINE_ID, ResponsibilityLevel.STAFF))
        .thenReturn(Optional.of(responsibility(ResponsibilityLevel.STAFF)));
    when(authUserRepository.findById(USER_ID))
        .thenReturn(Optional.of(userWithPhoneViaReflection(PHONE)));
    // First save (new job) throws duplicate; second save (mark escalated) succeeds
    when(notificationJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"))
        .thenAnswer(inv -> inv.getArgument(0));

    service.escalate(job);

    // Should not throw — duplicate swallowed; current job still marked ESCALATED
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.ESCALATED);
  }

  // ---- Tests: alert not found ----

  @Test
  void escalate_whenAlertNotFound_marksEscalatedToStopRetry() {
    var job = sentJob("TECHNICIAN");
    when(sparepartAlertRepository.findById(ALERT_ID)).thenReturn(Optional.empty());
    when(notificationJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.escalate(job);

    verify(notificationJobRepository).save(job);
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.ESCALATED);
  }
}
