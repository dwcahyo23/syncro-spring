package com.syncro.notification.application;

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
import com.syncro.maintenance.application.WorkOrderLifecycleEvent;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
class WorkOrderNotificationRoutingServiceTest {

  private static final String WORKORDER_ID = "WO-260800001";
  private static final String TRACE_ID = "trace-abc";

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private MachineResponsibilityRepository responsibilities;
  @Mock
  private AuthUserRepository users;
  @Mock
  private NotificationJobRepository notificationJobs;

  private WorkOrderNotificationRoutingService service;

  private final UUID machineId = UUID.randomUUID();
  private final UUID leaderId = UUID.randomUUID();
  private final UUID inventoryId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new WorkOrderNotificationRoutingService(workOrders, responsibilities, users, notificationJobs);
  }

  private WorkOrderEntity internalWorkOrder() {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, null,
        machineId, "desc", 0L, null, null, null, Instant.parse("2026-08-29T00:00:00Z"),
        Instant.parse("2026-08-29T00:00:00Z"));
  }

  private WorkOrderLifecycleEvent event() {
    return new WorkOrderLifecycleEvent(WORKORDER_ID, WorkOrderLifecycleEvent.EVENT_BREAKDOWN,
        WorkOrderStatus.IN_PROGRESS, TRACE_ID);
  }

  private AuthUserEntity user(UUID id, ApplicationRole role, String whatsapp) {
    return new AuthUserEntity(id, id + "@syncro.dev", "hash", role, true,
        Instant.parse("2026-08-29T00:00:00Z"), Instant.parse("2026-08-29T00:00:00Z")) {
      @Override
      public String getWhatsappNumber() {
        return whatsapp;
      }
    };
  }

  @Test
  @DisplayName("14.4-ROUTING-001 HAPPY_PATH: jobs enqueued for LEADER + inventory recipients with phone")
  void happyPathEnqueuesJobs() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(internalWorkOrder()));
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.LEADER))
        .thenReturn(Optional.of(new MachineResponsibilityEntity(UUID.randomUUID(), machineId, leaderId,
            ResponsibilityLevel.LEADER, Instant.now(), Instant.now())));
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.SPV))
        .thenReturn(Optional.empty());
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.MANAGER))
        .thenReturn(Optional.empty());
    when(users.findById(leaderId)).thenReturn(Optional.of(user(leaderId, ApplicationRole.SECTION_LEADER, "628111")));
    when(users.findAllByApplicationRoleInWithWhatsapp(any())).thenReturn(
        List.of(user(inventoryId, ApplicationRole.INVENTORY_MAINTENANCE, "628222")));

    service.onWorkOrderLifecycleEvent(event());

    ArgumentCaptor<NotificationJobEntity> captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobs, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
    var leaderJob = captor.getAllValues().stream()
        .filter(j -> j.getStatus() == NotificationJobStatus.PENDING)
        .filter(j -> leaderId.equals(j.getRecipientUserId()))
        .findFirst()
        .orElseThrow();
    assertThat(leaderJob.getStatus()).isEqualTo(NotificationJobStatus.PENDING);
    assertThat(leaderJob.getRecipientUserId()).isEqualTo(leaderId);
    assertThat(leaderJob.getIdempotencyKey()).isEqualTo("WORKORDER:" + WORKORDER_ID + ":BREAKDOWN:" + leaderId);
  }

  @Test
  @DisplayName("14.4-ROUTING-002 NO_PHONE: ROUTING_FAILED job with errorDetail")
  void noPhoneRecordsRoutingFailed() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(internalWorkOrder()));
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.LEADER))
        .thenReturn(Optional.of(new MachineResponsibilityEntity(UUID.randomUUID(), machineId, leaderId,
            ResponsibilityLevel.LEADER, Instant.now(), Instant.now())));
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.SPV))
        .thenReturn(Optional.empty());
    when(responsibilities.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, ResponsibilityLevel.MANAGER))
        .thenReturn(Optional.empty());
    when(users.findById(leaderId)).thenReturn(Optional.of(user(leaderId, ApplicationRole.SECTION_LEADER, null)));
    when(users.findAllByApplicationRoleInWithWhatsapp(any())).thenReturn(List.of());

    service.onWorkOrderLifecycleEvent(event());

    ArgumentCaptor<NotificationJobEntity> captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobs, org.mockito.Mockito.atLeastOnce()).saveAndFlush(captor.capture());
    var failedJob = captor.getAllValues().stream()
        .filter(j -> j.getStatus() == NotificationJobStatus.ROUTING_FAILED)
        .filter(j -> leaderId.equals(j.getRecipientUserId()))
        .findFirst()
        .orElseThrow();
    assertThat(failedJob.getStatus()).isEqualTo(NotificationJobStatus.ROUTING_FAILED);
    assertThat(failedJob.getErrorDetail()).contains("no whatsappNumber");
  }

  @Test
  @DisplayName("14.4-ROUTING-003 EXTERNAL_WO: no jobs created")
  void externalWorkOrderIsSkipped() {
    var external = new WorkOrderEntity(WORKORDER_ID, "EXTERNAL", null, WorkOrderStatus.IN_PROGRESS, null,
        machineId, "desc", 1L, null, null, null, Instant.now(), Instant.now());
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(external));

    service.onWorkOrderLifecycleEvent(event());

    verify(notificationJobs, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.4-ROUTING-004 missing workorder is a silent no-op")
  void missingWorkOrderIsNoop() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

    service.onWorkOrderLifecycleEvent(event());

    verify(notificationJobs, never()).saveAndFlush(any());
  }
}
