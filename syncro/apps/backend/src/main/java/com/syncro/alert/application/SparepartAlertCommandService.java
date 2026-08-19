package com.syncro.alert.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertEntity.InvalidAlertTransitionException;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartAlertCommandService {

  private final SparepartAlertRepository alertRepository;
  private final AuditLogWriter auditLogWriter;
  private final AuthUserPlantAssignmentRepository assignments;
  private final PlantScopeService plantScopes;
  private final Clock clock;

  public SparepartAlertCommandService(
      SparepartAlertRepository alertRepository,
      AuditLogWriter auditLogWriter,
      AuthUserPlantAssignmentRepository assignments,
      PlantScopeService plantScopes,
      Clock clock) {
    this.alertRepository = alertRepository;
    this.auditLogWriter = auditLogWriter;
    this.assignments = assignments;
    this.plantScopes = plantScopes;
    this.clock = clock;
  }

  /**
   * Acknowledge an OPEN alert (OPEN → ACKNOWLEDGED).
   * Any authenticated user with plant access may acknowledge.
   */
  @Transactional
  public void acknowledge(AuthenticatedUser user, UUID alertId, String reason) {
    var alert = loadAndCheckAccess(user, alertId);

    try {
      alert.acknowledge(reason, clock.instant());
    } catch (InvalidAlertTransitionException e) {
      throw new AlertInvalidTransitionException(e.getFrom(), SparepartAlertStatus.ACKNOWLEDGED);
    }

    alertRepository.save(alert);

    auditLogWriter.recordSystem(new AuditRecord(
        AuditAction.UPDATE,
        AuditEntityType.ALERT,
        alertId,
        "ALERT:" + alertId,
        resolvePlantId(alert),
        Map.of("actorId", user.id(), "transition", "OPEN→ACKNOWLEDGED"),
        Map.of("status", "ACKNOWLEDGED", "reason", reason != null ? reason : "")));
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private SparepartAlertEntity loadAndCheckAccess(AuthenticatedUser user, UUID alertId) {
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;

    if (superAdmin) {
      return alertRepository.findByIdWithDetails(alertId)
          .orElseThrow(SparepartAlertQueryService.AlertNotFoundException::new);
    }

    var scopedPlantIds = scopedPlantIds(user);
    if (scopedPlantIds.isEmpty()) {
      throw new SparepartAlertQueryService.AlertNotFoundException();
    }
    return alertRepository.findByIdWithDetailsScopedToPlants(alertId, scopedPlantIds)
        .orElseThrow(SparepartAlertQueryService.AlertNotFoundException::new);
  }

  private List<UUID> scopedPlantIds(AuthenticatedUser user) {
    return assignments.findByAuthUserId(UUID.fromString(user.id()))
        .stream()
        .map(a -> a.getPlantId())
        .toList();
  }

  private UUID resolvePlantId(SparepartAlertEntity alert) {
    try {
      return alert.getInstallation().getMachine().getPlant().getId();
    } catch (Exception e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Exceptions
  // ---------------------------------------------------------------------------

  public static class AlertInvalidTransitionException extends RuntimeException {
    private final SparepartAlertStatus from;
    private final SparepartAlertStatus to;

    public AlertInvalidTransitionException(SparepartAlertStatus from, SparepartAlertStatus to) {
      super("Invalid alert transition: " + from + " → " + to);
      this.from = from;
      this.to = to;
    }

    public SparepartAlertStatus getFrom() { return from; }
    public SparepartAlertStatus getTo() { return to; }
  }
}
