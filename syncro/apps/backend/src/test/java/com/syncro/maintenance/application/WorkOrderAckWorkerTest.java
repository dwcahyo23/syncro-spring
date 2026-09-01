package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderAckRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderStatusHistoryRepository;
import com.syncro.notification.application.WahaTemplateRenderer;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.sparepart.request.application.EscalationConfigService;
import com.syncro.sparepart.request.infrastructure.db.EscalationConfigEntity;
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
class WorkOrderAckWorkerTest {

  private static final String WORKORDER_ID = "WO-260800001";
  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkOrderStatusHistoryRepository statusHistory;
  @Mock
  private WorkOrderAckRepository acks;
  @Mock
  private NotificationJobRepository notificationJobs;
  @Mock
  private AuthUserRepository users;
  @Mock
  private EscalationConfigService escalationConfigs;
  @Mock
  private WahaTemplateRenderer templateRenderer;
  @Mock
  private JwtTokenService jwtTokens;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private WorkOrderAckWorker worker;

  private final UUID machineId = UUID.randomUUID();
  private final UUID leaderId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    worker = new WorkOrderAckWorker(workOrders, statusHistory, acks, notificationJobs, users,
        escalationConfigs, templateRenderer, jwtTokens, clock);
  }

  private AuthUserEntity leaderWithWhatsapp() {
    return new AuthUserEntity(leaderId, "pl@syncro.dev", "hash", ApplicationRole.PRODUCTION_LEADER, true,
        NOW.minusSeconds(3600), NOW.minusSeconds(3600)) {
      @Override
      public String getWhatsappNumber() {
        return "6281234567890";
      }
    };
  }

  @Test
  @DisplayName("14.4-ACK-001 computes net IN_PROGRESS minutes excluding PENDING_SPAREPART spans")
  void computesNetInProgressMinutesExcludingProcurement() {
    var t0 = NOW.minusSeconds(8 * 3600);
    var t1 = t0.plusSeconds(2 * 3600); // IN_PROGRESS
    var t2 = t1.plusSeconds(2 * 3600); // PENDING_SPAREPART (excluded)
    var t3 = t2.plusSeconds(3 * 3600); // IN_PROGRESS again
    var t4 = t3.plusSeconds(1 * 3600); // PENDING_REVIEW

    when(statusHistory.findByWorkOrderIdOrderByTransitionedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, null, "OPEN", "MANUAL", "u", "t", t0),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "OPEN", "IN_PROGRESS", "MANUAL", "u", "t", t1),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "IN_PROGRESS", "PENDING_SPAREPART", "DERIVED", "SYSTEM", "t", t2),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "PENDING_SPAREPART", "IN_PROGRESS", "DERIVED", "SYSTEM", "t", t3),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "IN_PROGRESS", "PENDING_REVIEW", "MANUAL", "u", "t", t4)));

    long minutes = worker.computeNetInProgressMinutes(WORKORDER_ID, NOW);

    // 2h (t1→t2) + 1h (t3→t4) = 180 minutes; the 3h procurement span is excluded
    assertThat(minutes).isEqualTo(180);
  }

  @Test
  @DisplayName("14.4-ACK-002 still-in-progress segment counts until now")
  void openSegmentCountsUntilNow() {
    var t1 = NOW.minusSeconds(5 * 3600);
    when(statusHistory.findByWorkOrderIdOrderByTransitionedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, null, "OPEN", "MANUAL", "u", "t", t1.minusSeconds(3600)),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "OPEN", "IN_PROGRESS", "MANUAL", "u", "t", t1)));

    long minutes = worker.computeNetInProgressMinutes(WORKORDER_ID, NOW);

    assertThat(minutes).isEqualTo(300);
  }

  @Test
  @DisplayName("14.4-ACK-003 4h reached: ack message with auto-login link enqueued to PRODUCTION_LEADER")
  void thresholdReachedSendsAck() {
    var t1 = NOW.minusSeconds(9 * 3600); // 9h in IN_PROGRESS -> 540 min >= 480 threshold
    var wo = new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, null,
        machineId, "desc", 0L, null, null, null, t1, t1);

    when(workOrders.findAllNonSyncedByStatus(WorkOrderStatus.IN_PROGRESS)).thenReturn(List.of(wo));
    when(acks.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    when(statusHistory.findByWorkOrderIdOrderByTransitionedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, null, "OPEN", "MANUAL", "u", "t", t1.minusSeconds(3600)),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "OPEN", "IN_PROGRESS", "MANUAL", "u", "t", t1)));
    when(escalationConfigs.findByScope("WORKORDER", "ACK_WAITING"))
        .thenReturn(Optional.of(configEntity(480)));
    when(users.findAllByApplicationRoleInWithWhatsapp(any())).thenReturn(List.of(leaderWithWhatsapp()));
    when(templateRenderer.machineCodeFor(WORKORDER_ID, machineId)).thenReturn("BF-08410");
    when(templateRenderer.renderWorkorderAck(eq(WORKORDER_ID), eq("BF-08410"), any(), any()))
        .thenReturn("Ack message body");
    when(jwtTokens.createToken(any(), any(), any())).thenReturn("auto-login-token");

    worker.poll();

    ArgumentCaptor<NotificationJobEntity> captor = ArgumentCaptor.forClass(NotificationJobEntity.class);
    verify(notificationJobs).saveAndFlush(captor.capture());
    var job = captor.getValue();
    assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.PENDING);
    assertThat(job.getRecipientUserId()).isEqualTo(leaderId);
    assertThat(job.getMessageBody()).isEqualTo("Ack message body");
    assertThat(job.getIdempotencyKey()).isEqualTo("WORKORDER_ACK:" + WORKORDER_ID + ":" + leaderId);
  }

  @Test
  @DisplayName("14.4-ACK-004 threshold not reached: no job enqueued")
  void thresholdNotReachedSkips() {
    var t1 = NOW.minusSeconds(1 * 3600);
    var wo = new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, null,
        machineId, "desc", 0L, null, null, null, t1, t1);

    when(workOrders.findAllNonSyncedByStatus(WorkOrderStatus.IN_PROGRESS)).thenReturn(List.of(wo));
    when(acks.existsByWorkOrderId(WORKORDER_ID)).thenReturn(false);
    when(statusHistory.findByWorkOrderIdOrderByTransitionedAtAsc(WORKORDER_ID)).thenReturn(List.of(
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, null, "OPEN", "MANUAL", "u", "t", t1.minusSeconds(3600)),
        new WorkOrderStatusHistoryEntity(UUID.randomUUID(), WORKORDER_ID, "OPEN", "IN_PROGRESS", "MANUAL", "u", "t", t1)));
    when(escalationConfigs.findByScope("WORKORDER", "ACK_WAITING"))
        .thenReturn(Optional.of(configEntity(480)));

    worker.poll();

    verify(notificationJobs, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("14.4-ACK-005 already acknowledged workorder is skipped")
  void acknowledgedWorkorderSkipped() {
    var wo = new WorkOrderEntity(WORKORDER_ID, "INTERNAL", null, WorkOrderStatus.IN_PROGRESS, null,
        machineId, "desc", 0L, null, null, null, NOW.minusSeconds(7200), NOW.minusSeconds(7200));
    when(workOrders.findAllNonSyncedByStatus(WorkOrderStatus.IN_PROGRESS)).thenReturn(List.of(wo));
    when(acks.existsByWorkOrderId(WORKORDER_ID)).thenReturn(true);

    worker.poll();

    verify(notificationJobs, never()).saveAndFlush(any());
  }

  private EscalationConfigEntity configEntity(int minutes) {
    return new EscalationConfigEntity() {
      @Override
      public int getDurationMinutes() {
        return minutes;
      }
    };
  }
}
