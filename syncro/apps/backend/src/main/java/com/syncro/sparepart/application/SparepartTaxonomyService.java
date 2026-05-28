package com.syncro.sparepart.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SparepartTaxonomyService {
  private static final String DUPLICATE_TAXONOMY_CONSTRAINT = "uq_sparepart_taxonomy_dimension_lower_name";

  private final SparepartTaxonomyRepository taxonomy;
  private final Clock clock;

  public SparepartTaxonomyService(SparepartTaxonomyRepository taxonomy, Clock clock) {
    this.taxonomy = taxonomy;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<SparepartTaxonomyView> list(AuthenticatedUser user, SparepartTaxonomyDimension dimension) {
    var entries = dimension == null
        ? taxonomy.findAllByOrderByDimensionAscNameAsc()
        : taxonomy.findByDimensionOrderByNameAsc(dimension);
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
    var name = normalizeName(command.name());
    if (taxonomy.existsByDimensionAndNameIgnoreCase(command.dimension(), name)) {
      throw new DuplicateSparepartTaxonomyException();
    }
    var now = Instant.now(clock);
    return toView(save(new SparepartTaxonomyEntity(UUID.randomUUID(), command.dimension(), name, now, now)));
  }

  @Transactional
  public SparepartTaxonomyView update(AuthenticatedUser user, UUID taxonomyId, SparepartTaxonomyCommand command) {
    requireMutationRole(user);
    validateCommand(command);
    var entry = find(taxonomyId);
    var name = normalizeName(command.name());
    var existing = taxonomy.findByDimensionAndNameIgnoreCase(entry.getDimension(), name);
    if (existing.isPresent() && !existing.get().getId().equals(taxonomyId)) {
      throw new DuplicateSparepartTaxonomyException();
    }
    entry.update(name, Instant.now(clock));
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
    normalizeName(command.name());
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
          && DUPLICATE_TAXONOMY_CONSTRAINT.equalsIgnoreCase(constraint.getConstraintName())) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
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
    return new SparepartTaxonomyView(entry.getId(), entry.getDimension(), entry.getName(), entry.getCreatedAt(), entry.getUpdatedAt());
  }

  public record SparepartTaxonomyCommand(SparepartTaxonomyDimension dimension, String name) {
  }

  public record SparepartTaxonomyView(UUID id, SparepartTaxonomyDimension dimension, String name, Instant createdAt, Instant updatedAt) {
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
