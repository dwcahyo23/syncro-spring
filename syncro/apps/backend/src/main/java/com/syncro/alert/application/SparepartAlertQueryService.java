package com.syncro.alert.application;

import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartAlertQueryService {

  private final SparepartAlertRepository alertRepository;
  private final AuthUserPlantAssignmentRepository assignments;
  private final PlantScopeService plantScopes;

  public SparepartAlertQueryService(
      SparepartAlertRepository alertRepository,
      AuthUserPlantAssignmentRepository assignments,
      PlantScopeService plantScopes) {
    this.alertRepository = alertRepository;
    this.assignments = assignments;
    this.plantScopes = plantScopes;
  }

  @Transactional(readOnly = true)
  public AlertListView list(AuthenticatedUser user, UUID machineId, UUID plantId,
      SparepartAlertStatus status, int page, int size, String sort) {
    var pageable = PageRequest.of(page, Math.min(size, 200), parseSort(sort));
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;

    if (!superAdmin && plantId != null) {
      plantScopes.requirePlantAccess(user, plantId);
    }

    var scopedPlantIds = superAdmin ? List.<UUID>of() : scopedPlantIds(user);
    if (!superAdmin && scopedPlantIds.isEmpty()) {
      return new AlertListView(List.of(), 0L, page, size, sort);
    }

    var result = superAdmin
        ? alertRepository.findAllUnscoped(machineId, plantId, status, pageable)
        : alertRepository.findAllScoped(scopedPlantIds, machineId, plantId, status, pageable);

    return new AlertListView(
        result.getContent().stream().map(this::toView).toList(),
        result.getTotalElements(),
        pageable.getPageNumber(),
        pageable.getPageSize(),
        sort);
  }

  @Transactional(readOnly = true)
  public AlertDetailView get(AuthenticatedUser user, UUID alertId) {
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;

    SparepartAlertEntity alert;
    if (superAdmin) {
      alert = alertRepository.findByIdWithDetails(alertId)
          .orElseThrow(AlertNotFoundException::new);
    } else {
      var scopedPlantIds = scopedPlantIds(user);
      if (scopedPlantIds.isEmpty()) {
        throw new AlertNotFoundException();
      }
      alert = alertRepository.findByIdWithDetailsScopedToPlants(alertId, scopedPlantIds)
          .orElseThrow(AlertNotFoundException::new);
    }
    return toView(alert);
  }

  private AlertDetailView toView(SparepartAlertEntity alert) {
    var installation = alert.getInstallation();
    var machine = installation.getMachine();
    var plant = machine.getPlant();
    var machineGroup = machine.getMachineGroup();
    var sparepart = installation.getSparepart();

    return new AlertDetailView(
        alert.getId(),
        machine.getId(),
        machine.getCode(),
        machine.getName(),
        plant.getId(),
        plant.getCode(),
        plant.getName(),
        machineGroup.getId(),
        machineGroup.getName(),
        installation.getId(),
        sparepart.getId(),
        sparepart.getCode(),
        sparepart.getName(),
        installation.getFunctionName(),
        alert.getThresholdPercentage(),
        installation.getBaselineCounter(),
        installation.getExpectedProductionCount(),
        alert.getCurrentCounterSnapshot(),
        alert.getConsumedProductionCountSnapshot(),
        alert.getConsumedPercentageSnapshot(),
        alert.getStatus(),
        alert.getStatusReason(),
        alert.getTraceId(),
        alert.getCreatedAt(),
        alert.getUpdatedAt());
  }

  private Sort parseSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return Sort.by(Sort.Direction.DESC, "createdAt");
    }
    var parts = sort.split(",", 2);
    if (parts.length == 2 && "asc".equalsIgnoreCase(parts[1].trim())) {
      return Sort.by(Sort.Direction.ASC, parts[0].trim());
    }
    return Sort.by(Sort.Direction.DESC, parts[0].trim());
  }

  private List<UUID> scopedPlantIds(AuthenticatedUser user) {
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(a -> a.getPlantId())
        .toList();
  }

  // --- View records ---

  public record AlertDetailView(
      UUID id,
      UUID machineId,
      String machineCode,
      String machineName,
      UUID plantId,
      String plantCode,
      String plantName,
      UUID machineGroupId,
      String machineGroupName,
      UUID installationId,
      UUID sparepartId,
      String sparepartCode,
      String sparepartName,
      String functionName,
      int thresholdPercentage,
      long baselineCounter,
      long expectedProductionCount,
      long currentCounterSnapshot,
      long consumedProductionCountSnapshot,
      BigDecimal consumedPercentageSnapshot,
      SparepartAlertStatus status,
      String statusReason,
      String traceId,
      Instant createdAt,
      Instant updatedAt) {
  }

  public record AlertListView(
      List<AlertDetailView> items,
      long totalElements,
      int page,
      int size,
      String sort) {
  }

  // --- Exceptions ---

  public static class AlertNotFoundException extends RuntimeException {
  }
}
