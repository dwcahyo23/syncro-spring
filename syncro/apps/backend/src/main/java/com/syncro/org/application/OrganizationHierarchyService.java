package com.syncro.org.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.org.infrastructure.DepartmentEntity;
import com.syncro.org.infrastructure.DepartmentMemberRepository;
import com.syncro.org.infrastructure.DepartmentRepository;
import com.syncro.org.infrastructure.SectionEntity;
import com.syncro.org.infrastructure.SectionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computed organization hierarchy read (Organization Maintenance tab):
 * {@code Plant -> Departments (spv/mg names, member users) -> Sections (leader name,
 * machine groups)}. Nothing is stored — the view is assembled per request from the
 * org tables. Any authenticated leader reads; SUPER_ADMIN/MANAGER_MAINTENANCE are
 * unrestricted; other roles see only plants they can access.
 */
@Service
public class OrganizationHierarchyService {

  private final PlantRepository plants;
  private final DepartmentRepository departments;
  private final DepartmentMemberRepository departmentMembers;
  private final SectionRepository sections;
  private final MachineGroupRepository machineGroups;
  private final PlantScopeService plantScopes;

  public OrganizationHierarchyService(
      PlantRepository plants,
      DepartmentRepository departments,
      DepartmentMemberRepository departmentMembers,
      SectionRepository sections,
      MachineGroupRepository machineGroups,
      PlantScopeService plantScopes) {
    this.plants = plants;
    this.departments = departments;
    this.departmentMembers = departmentMembers;
    this.sections = sections;
    this.machineGroups = machineGroups;
    this.plantScopes = plantScopes;
  }

  @Transactional(readOnly = true)
  public OrganizationHierarchyView hierarchy(AuthenticatedUser user) {
    var visiblePlantIds = resolveVisiblePlantIds(user);
    return new OrganizationHierarchyView(
        visiblePlantIds.stream()
            .map(this::toPlantNode)
            .toList());
  }

  private List<UUID> resolveVisiblePlantIds(AuthenticatedUser user) {
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return plants.findAll().stream().map(PlantEntity::getId).toList();
    }
    var scope = plantScopes.effectiveScope(user);
    if (!"ASSIGNED".equals(scope.mode()) && !"UNRESTRICTED".equals(scope.mode())) {
      return List.of();
    }
    if ("UNRESTRICTED".equals(scope.mode())) {
      return plants.findAll().stream().map(PlantEntity::getId).toList();
    }
    return scope.availablePlants().stream().map(p -> UUID.fromString(p.id())).toList();
  }

  private PlantNode toPlantNode(UUID plantId) {
    var plant = plants.findById(plantId).orElse(null);
    if (plant == null) {
      return null;
    }
    var departmentNodes = departments.findAllByPlantId(plantId, false).stream()
        .map(this::toDepartmentNode)
        .toList();
    var sectionNodes = sections.findAllByPlantId(plantId, false).stream()
        .map(this::toSectionNode)
        .toList();
    return new PlantNode(plant.getId(), plant.getCode(), plant.getName(), departmentNodes, sectionNodes);
  }

  private DepartmentNode toDepartmentNode(DepartmentEntity department) {
    var members = departmentMembers.findByDepartmentId(department.getId()).stream()
        .map(m -> new HierarchyMember(m.getUserId()))
        .toList();
    return new DepartmentNode(
        department.getId(),
        department.getName(),
        department.getSpvId(),
        department.getMgId(),
        department.isActive(),
        members);
  }

  private SectionNode toSectionNode(SectionEntity section) {
    var machineGroupIds = machineGroups.findIdsBySectionId(section.getId());
    return new SectionNode(
        section.getId(),
        section.getCode(),
        section.getName(),
        section.getLeaderUserId(),
        machineGroupIds);
  }

  public record OrganizationHierarchyView(List<PlantNode> plants) {
  }

  public record PlantNode(
      UUID id,
      String code,
      String name,
      List<DepartmentNode> departments,
      List<SectionNode> sections) {
  }

  public record DepartmentNode(
      UUID id,
      String name,
      UUID spvId,
      UUID mgId,
      boolean active,
      List<HierarchyMember> members) {
  }

  public record HierarchyMember(UUID userId) {
  }

  public record SectionNode(
      UUID id,
      String code,
      String name,
      UUID leaderUserId,
      List<UUID> machineGroupIds) {
  }
}
