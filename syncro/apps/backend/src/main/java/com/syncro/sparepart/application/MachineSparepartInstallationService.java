package com.syncro.sparepart.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MachineSparepartInstallationService {
  private static final int DEFAULT_THRESHOLD_PERCENTAGE = 90;

  private final MachineSparepartInstallationRepository installations;
  private final MachineRepository machines;
  private final SparepartRepository spareparts;
  private final PlantRepository plants;
  private final PlantScopeService plantScopes;
  private final AuthUserPlantAssignmentRepository assignments;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final SparepartLifetimeEvaluator evaluator;

  public MachineSparepartInstallationService(MachineSparepartInstallationRepository installations,
      MachineRepository machines, SparepartRepository spareparts, PlantRepository plants,
      PlantScopeService plantScopes, AuthUserPlantAssignmentRepository assignments, AuditLogWriter auditLog,
      Clock clock, SparepartLifetimeEvaluator evaluator) {
    this.installations = installations;
    this.machines = machines;
    this.spareparts = spareparts;
    this.plants = plants;
    this.plantScopes = plantScopes;
    this.assignments = assignments;
    this.auditLog = auditLog;
    this.clock = clock;
    this.evaluator = evaluator;
  }

  @Transactional(readOnly = true)
  public InstallationListView list(AuthenticatedUser user, InstallationFilters filters, Pageable pageable) {
    validatePageable(pageable);
    var superAdmin = user.applicationRole() == ApplicationRole.SUPER_ADMIN;
    validateFilterScope(user, filters, superAdmin);
    var result = superAdmin
        ? installations.findAllUnscoped(filters.machineId(), filters.sparepartId(), filters.plantId(), filters.machineGroupId(), pageable)
        : installations.findAllScoped(scopedPlantIds(user), filters.machineId(), filters.sparepartId(), filters.plantId(), filters.machineGroupId(), pageable);
    return new InstallationListView(result.stream().map(this::toView).toList(), result.getTotalElements(), pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().toString());
  }

  @Transactional(readOnly = true)
  public InstallationView get(AuthenticatedUser user, UUID installationId) {
    return toView(findScoped(user, installationId));
  }

  @Transactional
  public InstallationView create(AuthenticatedUser user, InstallationCommand command) {
    requireMutationRole(user);
    var normalized = normalize(command);
    var machine = resolveMachine(user, normalized.machineId());
    var sparepart = resolveSparepart(normalized.sparepartId());
    var now = Instant.now(clock);
    var installedAt = normalized.installedAt() != null ? normalized.installedAt() : now;
    var saved = save(new MachineSparepartInstallationEntity(UUID.randomUUID(), machine, sparepart,
        normalized.functionName(), normalized.expectedProductionCount(), normalized.baselineCounter(), normalized.thresholdPercentage(),
        installedAt, now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.INSTALLATION, saved.getId(),
        machine.getCode() + " / " + sparepart.getCode(), machine.getPlant().getId(), null,
        InstallationAuditValues.of(saved)));
    return toView(saved);
  }

  @Transactional
  public InstallationView update(AuthenticatedUser user, UUID installationId, InstallationUpdateCommand command) {
    requireMutationRole(user);
    var installation = findScoped(user, installationId);
    var entityLabel = installation.getMachine().getCode() + " / " + installation.getSparepart().getCode();
    var previous = InstallationAuditValues.of(installation);
    var normalized = normalize(command);
    var threshold = normalized.thresholdPercentage() != null ? normalized.thresholdPercentage() : installation.getThresholdPercentage();
    installation.update(normalized.functionName(), normalized.expectedProductionCount(), normalized.baselineCounter(), threshold, Instant.now(clock));
    var saved = save(installation);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.INSTALLATION, installationId, entityLabel,
        installation.getMachine().getPlant().getId(), previous, InstallationAuditValues.of(saved)));
    return toView(saved);
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID installationId) {
    requireMutationRole(user);
    var installation = findScoped(user, installationId);
    var entityLabel = installation.getMachine().getCode() + " / " + installation.getSparepart().getCode();
    var previous = InstallationAuditValues.of(installation);
    try {
      installations.delete(installation);
      installations.flush();
      auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.INSTALLATION, installationId,
          entityLabel, installation.getMachine().getPlant().getId(), previous, null));
    } catch (DataIntegrityViolationException exception) {
      throw new InstallationDataIntegrityException();
    }
  }

  private void validateFilterScope(AuthenticatedUser user, InstallationFilters filters, boolean superAdmin) {
    if (!superAdmin) {
      if (filters.plantId() != null) {
        plantScopes.requirePlantAccess(user, filters.plantId());
      }
      if (filters.machineId() != null) {
        resolveMachine(user, filters.machineId());
      }
      var scopedPlantIds = scopedPlantIds(user);
      if (scopedPlantIds.isEmpty()) {
        throw new InstallationPlantScopeEmptyException();
      }
    } else if (filters.plantId() != null && !plants.existsById(filters.plantId())) {
      throw new InstallationPlantNotFoundException();
    }
  }

  private MachineEntity resolveMachine(AuthenticatedUser user, UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(MachineForInstallationNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, machine.getPlant().getId());
    }
    return machine;
  }

  private SparepartEntity resolveSparepart(UUID sparepartId) {
    return spareparts.findById(sparepartId).orElseThrow(SparepartForInstallationNotFoundException::new);
  }

  private MachineSparepartInstallationEntity findScoped(AuthenticatedUser user, UUID installationId) {
    var installation = installations.findByIdWithDetails(installationId).orElseThrow(InstallationNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      plantScopes.requirePlantAccess(user, installation.getMachine().getPlant().getId());
    }
    return installation;
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new InstallationMutationForbiddenException();
    }
  }

  private MachineSparepartInstallationEntity save(MachineSparepartInstallationEntity installation) {
    try {
      return installations.saveAndFlush(installation);
    } catch (DataIntegrityViolationException exception) {
      throw new InstallationDataIntegrityException();
    }
  }

  private List<UUID> scopedPlantIds(AuthenticatedUser user) {
    return assignments.findByAuthUserId(UUID.fromString(user.id())).stream()
        .map(assignment -> assignment.getPlantId())
        .toList();
  }

  private void validatePageable(Pageable pageable) {
    if (pageable.getPageNumber() < 0 || pageable.getPageSize() < 1 || pageable.getPageSize() > 200) {
      throw new InstallationValidationException();
    }
  }

  private InstallationCommand normalize(InstallationCommand command) {
    if (command.machineId() == null || command.sparepartId() == null) {
      throw new InstallationValidationException();
    }
    return new InstallationCommand(command.machineId(), command.sparepartId(), normalizeFunctionName(command.functionName()), positive(command.expectedProductionCount()),
        nonNegative(command.baselineCounter()), threshold(command.thresholdPercentage()), command.installedAt());
  }

  private InstallationUpdateCommand normalize(InstallationUpdateCommand command) {
    return new InstallationUpdateCommand(normalizeFunctionName(command.functionName()), positive(command.expectedProductionCount()), nonNegative(command.baselineCounter()),
        nullableThreshold(command.thresholdPercentage()));
  }

  private Integer nullableThreshold(Integer value) {
    if (value == null) {
      return null;
    }
    return threshold(value);
  }

  private String normalizeFunctionName(String value) {
    if (value == null) {
      throw new InstallationValidationException();
    }
    var trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > 255) {
      throw new InstallationValidationException();
    }
    return trimmed;
  }

  private long positive(Long value) {
    if (value == null || value <= 0) {
      throw new InstallationValidationException();
    }
    return value;
  }

  private long nonNegative(Long value) {
    if (value == null || value < 0) {
      throw new InstallationValidationException();
    }
    return value;
  }

  private int threshold(Integer value) {
    var threshold = value == null ? DEFAULT_THRESHOLD_PERCENTAGE : value;
    if (threshold < 1 || threshold > 100) {
      throw new InstallationValidationException();
    }
    return threshold;
  }

  private InstallationView toView(MachineSparepartInstallationEntity installation) {
    var machine = installation.getMachine();
    var plant = machine.getPlant();
    var machineGroup = machine.getMachineGroup();
    var sparepart = installation.getSparepart();
    Long currentCount = null;
    Long consumedProductionCount = null;
    java.math.BigDecimal consumedPercentage = null;
    try {
      var evalResult = evaluator.evaluate(machine.getId(), installation.getId());
      currentCount = evalResult.map(SparepartLifetimeEvaluator.EvaluationResult::currentCount).orElse(null);
      consumedProductionCount = evalResult.map(SparepartLifetimeEvaluator.EvaluationResult::consumedProductionCount).orElse(null);
      consumedPercentage = evalResult.map(SparepartLifetimeEvaluator.EvaluationResult::consumedPercentage).orElse(null);
    } catch (Exception e) {
      // Redis or transient failure — leave lifetime fields null, consistent with cache-miss behaviour
    }
    return new InstallationView(installation.getId(), machine.getId(), machine.getCode(), machine.getName(), plant.getId(),
        plant.getCode(), plant.getName(), machineGroup.getId(), machineGroup.getName(), sparepart.getId(), sparepart.getCode(),
        sparepart.getName(), installation.getFunctionName(), new TaxonomyRefView(sparepart.getCategory().getId(), sparepart.getCategory().getCode(), sparepart.getCategory().getName()),
        new TaxonomyRefView(sparepart.getBrand().getId(), sparepart.getBrand().getCode(), sparepart.getBrand().getName()),
        new TaxonomyRefView(sparepart.getKind().getId(), sparepart.getKind().getCode(), sparepart.getKind().getName()),
        new TaxonomyRefView(sparepart.getType().getId(), sparepart.getType().getCode(), sparepart.getType().getName()),
        installation.getExpectedProductionCount(), installation.getBaselineCounter(), currentCount, consumedProductionCount, consumedPercentage,
        installation.getThresholdPercentage(), "COUNTER_BASED", installation.getInstalledAt(), installation.getCreatedAt(), installation.getUpdatedAt());
  }

  public record InstallationCommand(UUID machineId, UUID sparepartId, String functionName, Long expectedProductionCount, Long baselineCounter,
      Integer thresholdPercentage, Instant installedAt) {
    public InstallationCommand(UUID machineId, UUID sparepartId, String functionName, Long expectedProductionCount, Long baselineCounter,
        Integer thresholdPercentage) {
      this(machineId, sparepartId, functionName, expectedProductionCount, baselineCounter, thresholdPercentage, null);
    }
  }

  public record InstallationUpdateCommand(String functionName, Long expectedProductionCount, Long baselineCounter, Integer thresholdPercentage) {
  }

  public record InstallationFilters(UUID machineId, UUID sparepartId, UUID plantId, UUID machineGroupId) {
  }

  public record InstallationListView(List<InstallationView> items, long totalElements, int page, int size, String sort) {
  }

  public record TaxonomyRefView(UUID id, String code, String name) {
  }

  public record InstallationView(UUID id, UUID machineId, String machineCode, String machineName, UUID plantId,
      String plantCode, String plantName, UUID machineGroupId, String machineGroupName, UUID sparepartId,
      String sparepartCode, String sparepartName, String functionName, TaxonomyRefView category, TaxonomyRefView brand,
      TaxonomyRefView kind, TaxonomyRefView type, long expectedProductionCount, long baselineCounter,
      Long currentCount, Long consumedProductionCount, java.math.BigDecimal consumedPercentage, int thresholdPercentage,
      String calculationBasis, Instant installedAt, Instant createdAt, Instant updatedAt) {
  }

  public static class InstallationDataIntegrityException extends RuntimeException { }
  public static class InstallationMutationForbiddenException extends RuntimeException { }
  public static class InstallationNotFoundException extends RuntimeException { }
  public static class InstallationPlantNotFoundException extends RuntimeException { }
  public static class InstallationPlantScopeEmptyException extends RuntimeException { }
  public static class InstallationValidationException extends RuntimeException { }
  public static class MachineForInstallationNotFoundException extends RuntimeException { }
  public static class SparepartForInstallationNotFoundException extends RuntimeException { }
}
