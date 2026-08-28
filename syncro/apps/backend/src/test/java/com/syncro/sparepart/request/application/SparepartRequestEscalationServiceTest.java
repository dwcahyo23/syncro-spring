package com.syncro.sparepart.request.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestEntity;
import com.syncro.sparepart.request.infrastructure.db.SparepartRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SparepartRequestEscalationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

  @Mock
  private SparepartRequestRepository requests;
  @Mock
  private EscalationConfigService escalationConfigs;
  @Mock
  private MachineResponsibilityRepository responsibilities;
  @Mock
  private AuthUserRepository users;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private NotificationJobRepository notificationJobs;

  private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
    @Override
    public org.springframework.transaction.TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(org.springframework.transaction.TransactionStatus status) {
    }

    @Override
    public void rollback(org.springframework.transaction.TransactionStatus status) {
    }
  };
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID machineId = UUID.randomUUID();
  private final UUID requestId = UUID.randomUUID();
  private final UUID leaderUserId = UUID.randomUUID();

  private SparepartRequestEscalationService service;

  @BeforeEach
  void setUp() {
    service = new SparepartRequestEscalationService(
        requests, escalationConfigs, responsibilities, users, workOrders, notificationJobs, clock,
        transactionManager);
    when(escalationConfigs.durationMinutes("ACK_WAITING")).thenReturn(480);
    when(escalationConfigs.durationMinutes("PROCESS_WAITING")).thenReturn(1440);
    when(escalationConfigs.durationMinutes("PURCHASE_WAITING")).thenReturn(2880);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of());
    when(users.findAllByApplicationRoleInWithWhatsapp(any())).thenReturn(List.of());
    when(notificationJobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private SparepartRequestEntity staleRequest(SparepartRequestStatus status) {
    return new SparepartRequestEntity(requestId, SparepartRequestType.SPAREPART, null, machineId,
        null, "MC-0001", (short) 2, null, null, null, status, UUID.randomUUID(), NOW, null, NOW, NOW);
  }

  private AuthUserEntity userWithPhone(UUID id, ApplicationRole role, String phone) {
    var user = new AuthUserEntity(id, role.name().toLowerCase() + "@test", "hash", role, true, NOW, NOW);
    if (phone != null) {
      org.springframework.test.util.ReflectionTestUtils.setField(user, "whatsappNumber", phone);
    }
    return user;
  }

  @Test
  @DisplayName("12.3-ESC-001 P0 ACK_WAITING stale request enqueues jobs for LEADER responsibility + inventory roles")
  void escalateAckStale() {
    var request = staleRequest(SparepartRequestStatus.REQUESTED);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));
    var responsibility = new MachineResponsibilityEntity(UUID.randomUUID(), machineId, leaderUserId,
        ResponsibilityLevel.LEADER, NOW, NOW);
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId,
        ResponsibilityLevel.LEADER)).thenReturn(Optional.of(responsibility));
    when(users.findById(leaderUserId)).thenReturn(Optional.of(userWithPhone(leaderUserId, ApplicationRole.SECTION_LEADER, "628111")));

    service.escalateStale();

    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobs).saveAndFlush(captor.capture());
    var job = captor.getValue();
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.PENDING);
    assertThat(job.getEscalationLevel()).isEqualTo("ACK_WAITING");
    assertThat(job.getIdempotencyKey()).isEqualTo(
        "SPAREPART_REQUEST:" + requestId + ":ACK_WAITING:" + leaderUserId);
    assertThat(job.getMessageBody()).contains(requestId.toString()).contains("MC-0001");
  }

  @Test
  @DisplayName("12.3-ESC-002 P0 duplicate idempotency key is silently skipped (saveIgnoreDuplicate)")
  void duplicateSkipped() {
    var request = staleRequest(SparepartRequestStatus.REQUESTED);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));
    var responsibility = new MachineResponsibilityEntity(UUID.randomUUID(), machineId, leaderUserId,
        ResponsibilityLevel.LEADER, NOW, NOW);
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId,
        ResponsibilityLevel.LEADER)).thenReturn(Optional.of(responsibility));
    when(users.findById(leaderUserId)).thenReturn(Optional.of(userWithPhone(leaderUserId, ApplicationRole.SECTION_LEADER, "628111")));
    when(notificationJobs.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

    // Must not throw despite the duplicate
    service.escalateStale();
  }

  @Test
  @DisplayName("12.3-ESC-003 P0 LEADER without phone records ROUTING_FAILED job with reason")
  void leaderNoPhoneRoutingFailed() {
    var request = staleRequest(SparepartRequestStatus.REQUESTED);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));
    var responsibility = new MachineResponsibilityEntity(UUID.randomUUID(), machineId, leaderUserId,
        ResponsibilityLevel.LEADER, NOW, NOW);
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId,
        ResponsibilityLevel.LEADER)).thenReturn(Optional.of(responsibility));
    when(users.findById(leaderUserId)).thenReturn(Optional.of(userWithPhone(leaderUserId, ApplicationRole.SECTION_LEADER, null)));

    service.escalateStale();

    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobs).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(NotificationJobStatus.ROUTING_FAILED);
    assertThat(captor.getValue().getErrorDetail()).contains("no whatsappNumber");
  }

  @Test
  @DisplayName("12.3-ESC-004 P0 request without machine target is logged and skipped (no job)")
  void noMachineTargetSkipped() {
    var request = new SparepartRequestEntity(requestId, SparepartRequestType.CONSUMABLE, null, null,
        null, null, (short) 1, null, null, null, SparepartRequestStatus.REQUESTED, UUID.randomUUID(),
        NOW, null, NOW, NOW);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));

    service.escalateStale();

    verify(notificationJobs, never()).saveAndFlush(any(NotificationJobEntity.class));
  }

  @Test
  @DisplayName("12.3-ESC-005 P0 no LEADER assignment + no inventory recipients → log + skip (no job)")
  void noRecipientsSkipped() {
    var request = staleRequest(SparepartRequestStatus.REQUESTED);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId,
        ResponsibilityLevel.LEADER)).thenReturn(Optional.empty());
    when(users.findAllByApplicationRoleInWithWhatsapp(any())).thenReturn(List.of());

    service.escalateStale();

    verify(notificationJobs, never()).saveAndFlush(any(NotificationJobEntity.class));
  }

  @Test
  @DisplayName("12.3-ESC-006 P0 inventory/stores users with phones are enqueued as additional recipients")
  void inventoryRecipientsEnqueued() {
    var request = staleRequest(SparepartRequestStatus.REQUESTED);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId,
        ResponsibilityLevel.LEADER)).thenReturn(Optional.empty());
    var inventory = userWithPhone(UUID.randomUUID(), ApplicationRole.INVENTORY_MAINTENANCE, "628222");
    var store = userWithPhone(UUID.randomUUID(), ApplicationRole.STOREKEEPER, "628333");
    when(users.findAllByApplicationRoleInWithWhatsapp(any())).thenReturn(List.of(inventory, store));

    service.escalateStale();

    var captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobs, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
    // Idempotency keys are per-recipient so both saves survive the unique constraint.
    var keys = captor.getAllValues().stream().map(NotificationJobEntity::getIdempotencyKey).toList();
    assertThat(keys).doesNotHaveDuplicates();
    var phones = captor.getAllValues().stream().map(NotificationJobEntity::getRecipientPhone).toList();
    assertThat(phones).containsExactlyInAnyOrder("628222", "628333");
  }

  @Test
  @DisplayName("12.3-ESC-007 P0 request bound to a workorder resolves the workorder's machine for recipients")
  void boundWorkOrderMachineResolution() {
    var request = new SparepartRequestEntity(requestId, SparepartRequestType.SERVICE_EXTERNAL, "WO-2609-00001",
        null, null, null, (short) 1, null, null, null, SparepartRequestStatus.REQUESTED, UUID.randomUUID(),
        NOW, null, NOW, NOW);
    when(requests.findStaleByStatusInAndUpdatedAtBefore(
        org.mockito.ArgumentMatchers.argThat(statuses -> statuses.contains(SparepartRequestStatus.REQUESTED)),
        any())).thenReturn(List.of(request));
    var workOrder = new WorkOrderEntity("WO-2609-00001", "INTERNAL", null, WorkOrderStatus.OPEN, null, machineId,
        "fix", 0L, null, null, null, NOW, NOW);
    when(workOrders.findById("WO-2609-00001")).thenReturn(Optional.of(workOrder));
    var responsibility = new MachineResponsibilityEntity(UUID.randomUUID(), machineId, leaderUserId,
        ResponsibilityLevel.LEADER, NOW, NOW);
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId,
        ResponsibilityLevel.LEADER)).thenReturn(Optional.of(responsibility));
    when(users.findById(leaderUserId)).thenReturn(Optional.of(userWithPhone(leaderUserId, ApplicationRole.SECTION_LEADER, "628111")));

    service.escalateStale();

    verify(notificationJobs).saveAndFlush(any(NotificationJobEntity.class));
  }
}
