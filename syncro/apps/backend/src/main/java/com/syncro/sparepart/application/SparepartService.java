package com.syncro.sparepart.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartService {
  private static final int MAX_PAGE_SIZE = 200;
  private static final String DUPLICATE_CODE_CONSTRAINT = "uq_spareparts_lower_code";
  private static final String DUPLICATE_NAME_CONSTRAINT = "uq_spareparts_lower_name";

  private final SparepartRepository spareparts;
  private final SparepartTaxonomyRepository taxonomy;
  private final MachineRepository machines;
  private final Clock clock;

  public SparepartService(SparepartRepository spareparts, SparepartTaxonomyRepository taxonomy, MachineRepository machines, Clock clock) {
    this.spareparts = spareparts;
    this.taxonomy = taxonomy;
    this.machines = machines;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public SparepartListView list(AuthenticatedUser user, SparepartFilters filters) {
    var search = normalizeSearch(filters.search());
    var page = normalizePage(filters.page());
    var size = normalizeSize(filters.size());
    var pageable = PageRequest.of(page, size);
    var result = search.isEmpty()
        ? spareparts.search(filters.categoryId(), filters.brandId(), filters.kindId(), filters.typeId(), pageable)
        : spareparts.search(filters.categoryId(), filters.brandId(), filters.kindId(), filters.typeId(), search, pageable);
    return new SparepartListView(result.stream().map(this::toView).toList(), result.getTotalElements(), page, size);
  }

  @Transactional(readOnly = true)
  public SparepartView get(AuthenticatedUser user, UUID sparepartId) {
    return toView(find(sparepartId));
  }

  @Transactional
  public SparepartView create(AuthenticatedUser user, SparepartCommand command) {
    requireMutationRole(user);
    var normalized = normalize(command);
    if (spareparts.existsByCodeIgnoreCase(normalized.code()) || spareparts.existsByNameIgnoreCase(normalized.name())) {
      throw new DuplicateSparepartException();
    }
    var now = Instant.now(clock);
    var machine = resolveMachine(user, normalized.machineId());
    var taxonomies = resolveTaxonomies(normalized);
    return toView(save(new SparepartEntity(
        UUID.randomUUID(),
        normalized.code(),
        normalized.name(),
        machine,
        taxonomies.category(),
        taxonomies.brand(),
        taxonomies.kind(),
        taxonomies.type(),
        now,
        now)));
  }

  @Transactional
  public SparepartView update(AuthenticatedUser user, UUID sparepartId, SparepartCommand command) {
    requireMutationRole(user);
    var sparepart = find(sparepartId);
    var normalized = normalize(command);
    var existingCode = spareparts.findByCodeIgnoreCase(normalized.code());
    var existingName = spareparts.findByNameIgnoreCase(normalized.name());
    if (isDifferentSparepart(existingCode, sparepartId) || isDifferentSparepart(existingName, sparepartId)) {
      throw new DuplicateSparepartException();
    }
    var machine = resolveMachine(user, normalized.machineId());
    var taxonomies = resolveTaxonomies(normalized);
    sparepart.update(
        normalized.code(),
        normalized.name(),
        machine,
        taxonomies.category(),
        taxonomies.brand(),
        taxonomies.kind(),
        taxonomies.type(),
        Instant.now(clock));
    return toView(save(sparepart));
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID sparepartId) {
    requireMutationRole(user);
    var sparepart = find(sparepartId);
    try {
      spareparts.delete(sparepart);
      spareparts.flush();
    } catch (DataIntegrityViolationException exception) {
      throw new SparepartDataIntegrityException();
    }
  }

  private SparepartEntity find(UUID sparepartId) {
    return spareparts.findById(sparepartId).orElseThrow(SparepartNotFoundException::new);
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new SparepartMutationForbiddenException();
    }
  }

  private SparepartCommand normalize(SparepartCommand command) {
    return new SparepartCommand(
        normalizeCode(command.code()),
        normalizeName(command.name()),
        requiredId(command.machineId()),
        requiredId(command.categoryId()),
        requiredId(command.brandId()),
        requiredId(command.kindId()),
        requiredId(command.typeId()));
  }

  private String normalizeSearch(String search) {
    if (search == null) {
      return "";
    }
    var trimmed = search.trim().toLowerCase(Locale.ROOT);
    return trimmed.isEmpty() ? "" : escapeLikePattern(trimmed);
  }

  private String escapeLikePattern(String search) {
    return search
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");
  }

  private int normalizePage(int page) {
    if (page < 0) {
      throw new SparepartValidationException();
    }
    return page;
  }

  private int normalizeSize(int size) {
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new SparepartValidationException();
    }
    return size;
  }

  private UUID requiredId(UUID id) {
    if (id == null) {
      throw new SparepartValidationException();
    }
    return id;
  }

  private MachineEntity resolveMachine(AuthenticatedUser user, UUID machineId) {
    var machine = machines.findByIdWithPlantAndGroup(machineId).orElseThrow(SparepartMachineNotFoundException::new);
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && !user.assignedPlantIds().contains(machine.getPlant().getId())) {
      throw new SparepartMachineNotFoundException();
    }
    return machine;
  }

  private TaxonomyRefs resolveTaxonomies(SparepartCommand command) {
    var refs = new TaxonomyRefs(
        taxonomy(command.categoryId(), SparepartTaxonomyDimension.CATEGORY),
        taxonomy(command.brandId(), SparepartTaxonomyDimension.BRAND),
        taxonomy(command.kindId(), SparepartTaxonomyDimension.KIND),
        taxonomy(command.typeId(), SparepartTaxonomyDimension.TYPE));
    validateLinkedTaxonomy(refs.category(), refs.brand());
    validateLinkedTaxonomy(refs.category(), refs.kind());
    validateLinkedTaxonomy(refs.category(), refs.type());
    return refs;
  }

  private SparepartTaxonomyEntity taxonomy(UUID id, SparepartTaxonomyDimension expectedDimension) {
    var entry = taxonomy.findById(id).orElseThrow(SparepartTaxonomyReferenceNotFoundException::new);
    if (entry.getDimension() != expectedDimension) {
      throw new SparepartTaxonomyDimensionMismatchException();
    }
    return entry;
  }

  private void validateLinkedTaxonomy(SparepartTaxonomyEntity category, SparepartTaxonomyEntity dependent) {
    if (dependent.getCategory() == null || !dependent.getCategory().getId().equals(category.getId())) {
      throw new SparepartTaxonomyDimensionMismatchException();
    }
  }

  private boolean isDifferentSparepart(Optional<SparepartEntity> existing, UUID sparepartId) {
    return existing.isPresent() && !existing.get().getId().equals(sparepartId);
  }

  private SparepartEntity save(SparepartEntity sparepart) {
    try {
      return spareparts.saveAndFlush(sparepart);
    } catch (DataIntegrityViolationException exception) {
      if (isDuplicateSparepartViolation(exception)) {
        throw new DuplicateSparepartException();
      }
      throw new SparepartDataIntegrityException();
    }
  }

  private boolean isDuplicateSparepartViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof ConstraintViolationException constraint
          && (DUPLICATE_CODE_CONSTRAINT.equalsIgnoreCase(constraint.getConstraintName())
              || DUPLICATE_NAME_CONSTRAINT.equalsIgnoreCase(constraint.getConstraintName()))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private String normalizeCode(String code) {
    if (code == null) {
      throw new SparepartValidationException();
    }
    var trimmed = code.trim();
    if (trimmed.isEmpty() || trimmed.length() > 64) {
      throw new SparepartValidationException();
    }
    return trimmed;
  }

  private String normalizeName(String name) {
    if (name == null) {
      throw new SparepartValidationException();
    }
    var trimmed = name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 255) {
      throw new SparepartValidationException();
    }
    return trimmed;
  }

  private SparepartView toView(SparepartEntity sparepart) {
    return new SparepartView(
        sparepart.getId(),
        sparepart.getCode(),
        sparepart.getName(),
        toMachineRef(sparepart.getMachine()),
        toTaxonomyRef(sparepart.getCategory()),
        toTaxonomyRef(sparepart.getBrand()),
        toTaxonomyRef(sparepart.getKind()),
        toTaxonomyRef(sparepart.getType()),
        sparepart.getCreatedAt(),
        sparepart.getUpdatedAt());
  }

  private SparepartMachineRefView toMachineRef(MachineEntity machine) {
    var plant = machine.getPlant();
    return new SparepartMachineRefView(machine.getId(), machine.getCode(), machine.getName(), plant.getId(), plant.getCode(), plant.getName());
  }

  private SparepartTaxonomyRefView toTaxonomyRef(SparepartTaxonomyEntity taxonomy) {
    return new SparepartTaxonomyRefView(taxonomy.getId(), taxonomy.getCode(), taxonomy.getName());
  }

  private record TaxonomyRefs(
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type) {
  }

  public record SparepartCommand(String code, String name, UUID machineId, UUID categoryId, UUID brandId, UUID kindId, UUID typeId) {
  }

  public record SparepartFilters(UUID categoryId, UUID brandId, UUID kindId, UUID typeId, String search, int page, int size) {
  }

  public record SparepartListView(List<SparepartView> items, long totalElements, int page, int size) {
  }

  public record SparepartMachineRefView(UUID id, String code, String name, UUID plantId, String plantCode, String plantName) {
  }

  public record SparepartTaxonomyRefView(UUID id, String code, String name) {
  }

  public record SparepartView(
      UUID id,
      String code,
      String name,
      SparepartMachineRefView machine,
      SparepartTaxonomyRefView category,
      SparepartTaxonomyRefView brand,
      SparepartTaxonomyRefView kind,
      SparepartTaxonomyRefView type,
      Instant createdAt,
      Instant updatedAt) {
  }

  public static class DuplicateSparepartException extends RuntimeException {
  }

  public static class SparepartDataIntegrityException extends RuntimeException {
  }

  public static class SparepartMutationForbiddenException extends RuntimeException {
  }

  public static class SparepartNotFoundException extends RuntimeException {
  }

  public static class SparepartMachineNotFoundException extends RuntimeException {
  }

  public static class SparepartTaxonomyDimensionMismatchException extends RuntimeException {
  }

  public static class SparepartTaxonomyReferenceNotFoundException extends RuntimeException {
  }

  public static class SparepartValidationException extends RuntimeException {
  }
}
