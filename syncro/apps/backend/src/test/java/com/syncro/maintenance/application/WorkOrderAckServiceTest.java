package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRatingRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingRepository;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobRepository;
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

@ExtendWith(MockitoExtension.class)
class WorkOrderAckServiceTest {

  private static final String WORKORDER_ID = "WO-2608-00001";
  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderAckRepository acks;
  @Mock
  private NotificationJobRepository notificationJobs;
  @Mock
  private WorkorderRatingRepository ratings;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private WorkOrderAckService service;

  private final UUID machineId = UUID.randomUUID();
  private final AuthenticatedUser user =
      new AuthenticatedUser("11111111-1111-1111-1111-111111111111", "leader@syncro.dev",
          ApplicationRole.SECTION_LEADER);

  @BeforeEach
  void setUp() {
    service = new WorkOrderAckService(workOrders, acks, notificationJobs, ratings, auditLog, clock);
  }

  private WorkOrderEntity internalWo(WorkOrderStatus status) {
    return new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, status, null, machineId,
        "desc", 0L, null, null, null, NOW.minusSeconds(7200), NOW.minusSeconds(7200));
  }

  @Test
  @DisplayName("14.4-ACKSVC-001 acknowledge records ack and cancels pending jobs")
  void acknowledgeRecordsAckAndCancelsJobs() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(internalWo(WorkOrderStatus.IN_PROGRESS)));
    when(acks.findByWorkOrderId(WORKORDER_ID)).thenReturn(Optional.empty());
    when(notificationJobs.cancelActiveForRequest(any(), any(), any(), any())).thenReturn(2);

    var result = service.acknowledge(user, WORKORDER_ID, "trace-1");

    assertThat(result.workOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(result.acknowledgedAt()).isEqualTo(NOW);
    ArgumentCaptor<WorkOrderAckEntity> captor = ArgumentCaptor.forClass(WorkOrderAckEntity.class);
    verify(acks).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getWorkOrderId()).isEqualTo(WORKORDER_ID);
    assertThat(captor.getValue().getAcknowledgedBy()).isEqualTo(UUID.fromString(user.id()));
  }

  @Test
  @DisplayName("14.4-ACKSVC-002 duplicate ack is rejected")
  void duplicateAckRejected() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.of(internalWo(WorkOrderStatus.IN_PROGRESS)));
    when(acks.findByWorkOrderId(WORKORDER_ID)).thenReturn(
        Optional.of(new WorkOrderAckEntity(UUID.randomUUID(), WORKORDER_ID, UUID.randomUUID(), NOW, "t")));

    assertThatThrownBy(() -> service.acknowledge(user, WORKORDER_ID, "trace-1"))
        .isInstanceOf(WorkOrderAckService.AckAlreadyExistsException.class);
    verify(acks, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.4-ACKSVC-003 unknown workorder is rejected")
  void unknownWorkorderRejected() {
    when(workOrders.findById(WORKORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.acknowledge(user, WORKORDER_ID, "trace-1"))
        .isInstanceOf(WorkOrderAckService.AckWorkOrderNotFoundException.class);
  }

  @Test
  @DisplayName("14.4-ACKSVC-004 task list separates ack'd vs pending and rated vs unrated")
  void taskListSeparates() {
    when(acks.findAll()).thenReturn(List.of(
        new WorkOrderAckEntity(UUID.randomUUID(), "WO-1", UUID.randomUUID(), NOW, "t")));
    var pendingWo = new WorkOrderEntity("WO-2", "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, null,
        machineId, "desc", 0L, null, null, null, NOW, NOW);
    when(workOrders.findAllNonSyncedByStatus(WorkOrderStatus.IN_PROGRESS)).thenReturn(List.of(pendingWo));
    when(acks.existsByWorkOrderId("WO-2")).thenReturn(false);

    var closed = internalWo(WorkOrderStatus.CLOSED);
    when(workOrders.findClosedForRating(org.mockito.ArgumentMatchers.anyBoolean(), any(), any())).thenReturn(List.of(
        new WorkOrderRatingRow(closed, null)));
    when(ratings.existsByWorkorderId(WORKORDER_ID)).thenReturn(true);

    var view = service.taskList(user);

    assertThat(view.acknowledged()).hasSize(1);
    assertThat(view.pending()).hasSize(1);
    assertThat(view.pending().getFirst().workOrderId()).isEqualTo("WO-2");
    assertThat(view.rated()).hasSize(1);
    assertThat(view.unrated()).isEmpty();
  }
}
