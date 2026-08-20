package com.syncro.notification.application;

import com.syncro.alert.application.SparepartAlertQueryService.AlertNotFoundException;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.notification.api.NotificationHistoryDtos;
import com.syncro.notification.api.NotificationHistoryDtos.AlertNotificationHistoryResponse;
import com.syncro.notification.api.NotificationHistoryDtos.NotificationAttemptView;
import com.syncro.notification.api.NotificationHistoryDtos.NotificationJobView;
import com.syncro.notification.infrastructure.NotificationAttemptEntity;
import com.syncro.notification.infrastructure.NotificationAttemptRepository;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only query service for alert notification history (Story 5.6).
 * Validates alert plant scope exactly as SparepartAlertQueryService.get does
 * (SUPER_ADMIN bypass, others throw 404 to avoid plant enumeration).
 */
@Service
public class NotificationHistoryQueryService {

  private static final Logger log = LoggerFactory.getLogger(NotificationHistoryQueryService.class);

  private final SparepartAlertRepository alertRepository;
  private final NotificationJobRepository jobRepository;
  private final NotificationAttemptRepository attemptRepository;
  private final AuthUserRepository authUserRepository;
  private final AuthUserPlantAssignmentRepository assignments;

  public NotificationHistoryQueryService(
      SparepartAlertRepository alertRepository,
      NotificationJobRepository jobRepository,
      NotificationAttemptRepository attemptRepository,
      AuthUserRepository authUserRepository,
      AuthUserPlantAssignmentRepository assignments) {
    this.alertRepository = alertRepository;
    this.jobRepository = jobRepository;
    this.attemptRepository = attemptRepository;
    this.authUserRepository = authUserRepository;
    this.assignments = assignments;
  }

  @Transactional(readOnly = true)
  public AlertNotificationHistoryResponse getHistory(AuthenticatedUser user, UUID alertId) {
    if (user == null || user.id() == null) {
      throw new AlertNotFoundException();
    }
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;
    if (superAdmin) {
      alertRepository.findByIdWithDetails(alertId)
          .orElseThrow(AlertNotFoundException::new);
    } else {
      var scopedPlantIds = scopedPlantIds(user);
      if (scopedPlantIds.isEmpty()) {
        throw new AlertNotFoundException();
      }
      alertRepository.findByIdWithDetailsScopedToPlants(alertId, scopedPlantIds)
          .orElseThrow(AlertNotFoundException::new);
    }

    List<NotificationJobEntity> jobs = jobRepository.findByAlertIdOrderByEscalationOrder(alertId);

    List<NotificationAttemptEntity> attempts;
    if (jobs.isEmpty()) {
      attempts = List.of();
    } else {
      List<UUID> jobIds = jobs.stream().map(NotificationJobEntity::getId).toList();
      attempts = attemptRepository.findByJobIdInOrderByJobIdAscAttemptNumberAsc(jobIds);
    }

    Map<UUID, List<NotificationAttemptEntity>> attemptsByJobId =
        attempts.stream().collect(Collectors.groupingBy(NotificationAttemptEntity::getJobId));

    List<UUID> recipientIds = jobs.stream()
        .map(NotificationJobEntity::getRecipientUserId)
        .filter(id -> id != null)
        .distinct()
        .toList();

    Map<UUID, String> displayNameById;
    if (recipientIds.isEmpty()) {
      displayNameById = Map.of();
    } else {
      var users = authUserRepository.findAllById(recipientIds);
      displayNameById = users.stream()
          .collect(Collectors.toMap(
              u -> u.getId(),
              u -> u.getLoginIdentifier(),
              (a, b) -> a));
    }

    List<NotificationJobView> views = jobs.stream()
        .map(job -> toView(job, attemptsByJobId.getOrDefault(job.getId(), List.of()), displayNameById))
        .toList();

    String traceId = views.stream()
        .map(NotificationJobView::traceId)
        .filter(t -> t != null && !t.isBlank())
        .findFirst()
        .orElse(null);
    String safeTraceId = traceId != null ? traceId.replaceAll("[\\r\\n]", "_") : "none";
    log.info("[traceId={}] getAlertNotifications alertId={} count={}", safeTraceId, alertId, views.size());

    return new AlertNotificationHistoryResponse(views, views.size());
  }

  private NotificationJobView toView(
      NotificationJobEntity job,
      List<NotificationAttemptEntity> jobAttempts,
      Map<UUID, String> displayNameById) {

    String displayName = job.getRecipientUserId() != null
        ? displayNameById.get(job.getRecipientUserId())
        : null;

    List<NotificationAttemptView> attemptViews = jobAttempts.stream()
        .map(a -> new NotificationAttemptView(
            a.getAttemptNumber(),
            a.getStatus(),
            a.getAttemptedAt(),
            truncate(a.getResponseDetail(), 512),
            a.getTraceId()))
        .toList();

    return new NotificationJobView(
        job.getId(),
        job.getAlertId(),
        job.getEscalationLevel(),
        job.getStatus(),
        job.getRecipientUserId(),
        displayName,
        NotificationHistoryDtos.maskPhone(job.getRecipientPhone()),
        job.getAttemptCount(),
        job.getMaxAttempts(),
        job.getSentAt(),
        job.getCreatedAt(),
        job.getUpdatedAt(),
        job.getNextAttemptAt(),
        truncate(job.getErrorDetail(), 512),
        job.getTraceId(),
        attemptViews);
  }

  private List<UUID> scopedPlantIds(AuthenticatedUser user) {
    UUID userId;
    try {
      userId = UUID.fromString(user.id());
    } catch (IllegalArgumentException e) {
      throw new AlertNotFoundException();
    }
    return assignments.findByAuthUserId(userId).stream()
        .map(a -> a.getPlantId())
        .toList();
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
