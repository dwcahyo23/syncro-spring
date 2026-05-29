package com.syncro.sparepart.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartTaxonomyService {
  private static final String DUPLICATE_TAXONOMY_CODE_CONSTRAINT = "uq_sparepart_taxonomy_dimension_lower_code";
  private static final String DUPLICATE_TAXONOMY_NAME_CONSTRAINT = "uq_sparepart_taxonomy_dimension_lower_name";
  private static final Set<String> CONTROLLED_CATEGORY_CODES = Set.of("ELECTRIC", "MECHANIC", "PNEUMATIC", "HYDRAULIC", "ELECTRONIC");

  private final SparepartTaxonomyRepository taxonomy;
  private final Clock clock;

  public SparepartTaxonomyService(SparepartTaxonomyRepository taxonomy, Clock clock) {
    this.taxonomy = taxonomy;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<SparepartTaxonomyView> list(AuthenticatedUser user, SparepartTaxonomyDimension dimension) {
    return list(user, dimension, null);
  }

  @Transactional(readOnly = true)
  public List<SparepartTaxonomyView> list(AuthenticatedUser user, SparepartTaxonomyDimension dimension, UUID categoryId) {
    if (dimension == null && categoryId != null) {
      throw new SparepartTaxonomyValidationException();
    }
    var entries = dimension == null
        ? taxonomy.findAllByOrderByDimensionAscNameAsc()
        : taxonomy.findByDimensionAndCategoryIdOrderByNameAsc(dimension, categoryId);
    return entries.stream().map(this::toView).toList();
  }

  @Transactional(readOnly = true)
  public SparepartTaxonomyView get(AuthenticatedUser user, UUID taxonomyId) {
    return toView(find(taxonomyId));
  }

  @Transactional
  public SparepartTaxonomyView create(AuthenticatedUser user, SparepartTaxonomyCommand command) {
    requireMutationRole(user);
    validateCommand(command);
    var code = normalizeCode(command.code());
    var name = normalizeName(command.name());
    validateControlledCategory(command.dimension(), code);
    var category = resolveCategory(command.dimension(), command.categoryId());
    if (taxonomy.existsByDimensionAndCodeIgnoreCase(command.dimension(), code)
        || taxonomy.existsByDimensionAndNameIgnoreCase(command.dimension(), name)) {
      throw new DuplicateSparepartTaxonomyException();
    }
    var now = Instant.now(clock);
    return toView(save(new SparepartTaxonomyEntity(UUID.randomUUID(), command.dimension(), code, name, category, now, now)));
  }

  @Transactional
  public SparepartTaxonomyView update(AuthenticatedUser user, UUID taxonomyId, SparepartTaxonomyCommand command) {
    requireMutationRole(user);
    validateCommand(command);
    var entry = find(taxonomyId);
    if (command.dimension() != entry.getDimension()) {
      throw new SparepartTaxonomyValidationException();
    }
    var code = normalizeCode(command.code());
    var name = normalizeName(command.name());
    validateControlledCategory(command.dimension(), code);
    var category = resolveCategory(command.dimension(), command.categoryId());
    var existingCode = taxonomy.findByDimensionAndCodeIgnoreCase(entry.getDimension(), code);
    var existingName = taxonomy.findByDimensionAndNameIgnoreCase(entry.getDimension(), name);
    if (isDifferentEntry(existingCode, taxonomyId) || isDifferentEntry(existingName, taxonomyId)) {
      throw new DuplicateSparepartTaxonomyException();
    }
    entry.update(code, name, category, Instant.now(clock));
    return toView(save(entry));
  }

  @Transactional
  public void delete(AuthenticatedUser user, UUID taxonomyId) {
    requireMutationRole(user);
    var entry = find(taxonomyId);
    try {
      taxonomy.delete(entry);
      taxonomy.flush();
    } catch (DataIntegrityViolationException exception) {
      throw new SparepartTaxonomyDataIntegrityException();
    }
  }

  private SparepartTaxonomyEntity find(UUID taxonomyId) {
    return taxonomy.findById(taxonomyId).orElseThrow(SparepartTaxonomyNotFoundException::new);
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN && user.applicationRole() != ApplicationRole.MANAGE) {
      throw new SparepartTaxonomyMutationForbiddenException();
    }
  }

  private void validateCommand(SparepartTaxonomyCommand command) {
    if (command.dimension() == null) {
      throw new SparepartTaxonomyValidationException();
    }
    normalizeCode(command.code());
    normalizeName(command.name());
  }

  private SparepartTaxonomyEntity resolveCategory(SparepartTaxonomyDimension dimension, UUID categoryId) {
    if (dimension == SparepartTaxonomyDimension.CATEGORY) {
      if (categoryId != null) {
        throw new SparepartTaxonomyValidationException();
      }
      return null;
    }
    if (categoryId == null) {
      throw new SparepartTaxonomyValidationException();
    }
    var category = find(categoryId);
    if (category.getDimension() != SparepartTaxonomyDimension.CATEGORY) {
      throw new SparepartTaxonomyValidationException();
    }
    return category;
  }

  private boolean isDifferentEntry(Optional<SparepartTaxonomyEntity> existing, UUID taxonomyId) {
    return existing.isPresent() && !existing.get().getId().equals(taxonomyId);
  }

  private SparepartTaxonomyEntity save(SparepartTaxonomyEntity entry) {
    try {
      return taxonomy.saveAndFlush(entry);
    } catch (DataIntegrityViolationException exception) {
      if (isDuplicateTaxonomyViolation(exception)) {
        throw new DuplicateSparepartTaxonomyException();
      }
      throw new SparepartTaxonomyDataIntegrityException();
    }
  }

  private boolean isDuplicateTaxonomyViolation(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof ConstraintViolationException constraint
          && (DUPLICATE_TAXONOMY_CODE_CONSTRAINT.equalsIgnoreCase(constraint.getConstraintName())
              || DUPLICATE_TAXONOMY_NAME_CONSTRAINT.equalsIgnoreCase(constraint.getConstraintName()))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private String normalizeCode(String code) {
    if (code == null) {
      throw new SparepartTaxonomyValidationException();
    }
    var trimmed = code.trim().toUpperCase(Locale.ROOT);
    if (trimmed.isEmpty() || trimmed.length() > 64) {
      throw new SparepartTaxonomyValidationException();
    }
    return trimmed;
  }

  private void validateControlledCategory(SparepartTaxonomyDimension dimension, String code) {
    if (dimension == SparepartTaxonomyDimension.CATEGORY && !CONTROLLED_CATEGORY_CODES.contains(code)) {
      throw new SparepartTaxonomyValidationException();
    }
  }

  private String normalizeName(String name) {
    if (name == null) {
      throw new SparepartTaxonomyValidationException();
    }
    var trimmed = name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 255) {
      throw new SparepartTaxonomyValidationException();
    }
    return trimmed;
  }

  private SparepartTaxonomyView toView(SparepartTaxonomyEntity entry) {
    return new SparepartTaxonomyView(
        entry.getId(),
        entry.getDimension(),
        entry.getCode(),
        entry.getName(),
        entry.getCategory() == null ? null : entry.getCategory().getId(),
        entry.getCreatedAt(),
        entry.getUpdatedAt());
  }

  public record SparepartTaxonomyCommand(SparepartTaxonomyDimension dimension, String code, String name, UUID categoryId) {
  }

  public record SparepartTaxonomyView(
      UUID id,
      SparepartTaxonomyDimension dimension,
      String code,
      String name,
      UUID categoryId,
      Instant createdAt,
      Instant updatedAt) {
  }

  public static class DuplicateSparepartTaxonomyException extends RuntimeException {
  }

  public static class SparepartTaxonomyDataIntegrityException extends RuntimeException {
  }

  public static class SparepartTaxonomyMutationForbiddenException extends RuntimeException {
  }

  public static class SparepartTaxonomyNotFoundException extends RuntimeException {
  }

  public static class SparepartTaxonomyValidationException extends RuntimeException {
  }
}
