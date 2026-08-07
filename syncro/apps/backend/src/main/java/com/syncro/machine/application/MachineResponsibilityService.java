package com.syncro.machine.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.machine.api.MachineResponsibilityDtos.CreateMachineResponsibilityRequest;
import com.syncro.machine.api.MachineResponsibilityDtos.MachineResponsibilityResponse;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.syncro.machine.api.MachineResponsibilityDtos.UpdateMachineResponsibilityRequest;

@Service
public class MachineResponsibilityService {

  private final MachineResponsibilityRepository responsibilityRepository;
  private final MachineRepository machineRepository;
  private final AuthUserRepository userRepository;
  private final PlantScopeService plantScopeService;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public MachineResponsibilityService(
      MachineResponsibilityRepository responsibilityRepository,
      MachineRepository machineRepository,
      AuthUserRepository userRepository,
      PlantScopeService plantScopeService,
      AuditLogWriter auditLog,
      Clock clock) {
    this.responsibilityRepository = responsibilityRepository;
    this.machineRepository = machineRepository;
    this.userRepository = userRepository;
    this.plantScopeService = plantScopeService;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional
  public MachineResponsibilityResponse assign(AuthenticatedUser currentUser, CreateMachineResponsibilityRequest request) {
    requireMutationRole(currentUser);
    MachineEntity machine = machineRepository.findById(request.machineId())
        .orElseThrow(() -> new com.syncro.machine.application.MachineService.MachineNotFoundException());

    plantScopeService.requirePlantAccess(currentUser, machine.getPlant().getId());

    AuthUserEntity user = userRepository.findById(request.userId())
        .orElseThrow(() -> new com.syncro.machine.application.MachineService.MachineValidationException());

    if (responsibilityRepository.existsByMachineIdAndUserId(machine.getId(), user.getId())) {
      throw new DuplicateResponsibilityException();
    }

    var entity = new MachineResponsibilityEntity(
        UUID.randomUUID(),
        machine,
        user,
        request.level(),
        clock.instant(),
        clock.instant()
    );

    try {
      entity = responsibilityRepository.save(entity);
    } catch (DataIntegrityViolationException ex) {
      throw new DuplicateResponsibilityException();
    }

    auditLog.record(currentUser, new AuditRecord(AuditAction.CREATE, AuditEntityType.RESPONSIBILITY, entity.getId(),
        user.getLoginIdentifier(), machine.getPlant().getId(), null, ResponsibilityAuditValues.of(entity)));
    return toResponse(entity);
  }

  @Transactional
  public MachineResponsibilityResponse update(AuthenticatedUser currentUser, UUID id, UpdateMachineResponsibilityRequest request) {
    requireMutationRole(currentUser);
    MachineResponsibilityEntity entity = responsibilityRepository.findById(id)
        .orElseThrow(() -> new ResponsibilityNotFoundException());

    plantScopeService.requirePlantAccess(currentUser, entity.getMachine().getPlant().getId());

    var entityLabel = entity.getUser().getLoginIdentifier();
    var previous = ResponsibilityAuditValues.of(entity);
    var plantId = entity.getMachine().getPlant().getId();
    entity.update(request.level(), clock.instant());
    entity = responsibilityRepository.save(entity);
    auditLog.record(currentUser, new AuditRecord(AuditAction.UPDATE, AuditEntityType.RESPONSIBILITY, id, entityLabel,
        plantId, previous, ResponsibilityAuditValues.of(entity)));
    return toResponse(entity);
  }

  @Transactional
  public void unassign(AuthenticatedUser currentUser, UUID id) {
    requireMutationRole(currentUser);
    MachineResponsibilityEntity entity = responsibilityRepository.findById(id)
        .orElseThrow(() -> new ResponsibilityNotFoundException());

    plantScopeService.requirePlantAccess(currentUser, entity.getMachine().getPlant().getId());

    var entityLabel = entity.getUser().getLoginIdentifier();
    var previous = ResponsibilityAuditValues.of(entity);
    var plantId = entity.getMachine().getPlant().getId();
    responsibilityRepository.delete(entity);
    auditLog.record(currentUser, new AuditRecord(AuditAction.DELETE, AuditEntityType.RESPONSIBILITY, id, entityLabel,
        plantId, previous, null));
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new com.syncro.machine.application.MachineService.MachineMutationForbiddenException();
    }
  }

  @Transactional(readOnly = true)
  public Page<MachineResponsibilityResponse> listByMachine(AuthenticatedUser currentUser, UUID machineId, Pageable pageable) {
    MachineEntity machine = machineRepository.findById(machineId)
        .orElseThrow(() -> new com.syncro.machine.application.MachineService.MachineNotFoundException());

    plantScopeService.requirePlantAccess(currentUser, machine.getPlant().getId());

    return responsibilityRepository.findByMachineId(machineId, pageable)
        .map(this::toResponse);
  }

  @Transactional(readOnly = true)
  public Page<MachineResponsibilityResponse> listAll(AuthenticatedUser currentUser, Pageable pageable) {
    if (currentUser.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return responsibilityRepository.findAll(pageable).map(this::toResponse);
    }
    var scope = plantScopeService.effectiveScope(currentUser);
    if ("EMPTY".equals(scope.mode())) {
      return Page.empty(pageable);
    }
    List<UUID> plantIds = scope.availablePlants().stream()
        .map(p -> UUID.fromString(p.id()))
        .toList();

    return responsibilityRepository.findAllByPlantIds(plantIds, pageable)
        .map(this::toResponse);
  }

  private MachineResponsibilityResponse toResponse(MachineResponsibilityEntity entity) {
    return new MachineResponsibilityResponse(
        entity.getId(),
        entity.getMachine().getId(),
        entity.getUser().getId(),
        entity.getUser().getLoginIdentifier(),
        entity.getLevel(),
        entity.getCreatedAt(),
        entity.getUpdatedAt()
    );
  }

  public static class DuplicateResponsibilityException extends RuntimeException {}
  public static class ResponsibilityNotFoundException extends RuntimeException {}
}
