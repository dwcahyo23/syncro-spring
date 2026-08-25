package com.syncro.audit.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.audit.api.AuditLogDtos.AuditLogEntryView;
import com.syncro.audit.api.AuditLogDtos.AuditLogListResponse;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogEntity;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 200;
  private static final Set<String> ALLOWED_SORTS = Set.of("createdAt", "actorName", "entityType", "action");
  private static final Instant NO_LOWER_BOUND = Instant.EPOCH;
  private static final Instant NO_UPPER_BOUND =
      Instant.ofEpochSecond(Long.MAX_VALUE / 1_000_000_000L, Long.MAX_VALUE % 1_000_000_000L);
  private static final TypeReference<Map<String, Object>> VALUES_TYPE = new TypeReference<>() {
  };

  private final AuditLogRepository auditLogs;
  private final PlantScopeService plantScopes;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public AuditLogService(AuditLogRepository auditLogs, PlantScopeService plantScopes) {
    this.auditLogs = auditLogs;
    this.plantScopes = plantScopes;
  }

  @Transactional(readOnly = true)
  public AuditLogListResponse list(AuthenticatedUser user, AuditLogQuery query) {
    var scope = plantScopes.effectiveScope(user);
    var unrestricted = user.applicationRole() == ApplicationRole.SUPER_ADMIN;
    List<UUID> plantIds = List.of();
    if (!unrestricted && !"EMPTY".equals(scope.mode())) {
      plantIds = scope.availablePlants().stream()
          .map(plant -> UUID.fromString(plant.id()))
          .toList();
    }
    if (query.plantId() != null) {
      plantScopes.requirePlantAccess(user, query.plantId());
    }
    var page = Math.max(query.page(), 0);
    var size = normalizeSize(query.size());
    var sort = normalizeSort(query.sort());
    var result = auditLogs.search(
        query.entityType(),
        query.entityId(),
        normalizeActor(query.actor()),
        query.plantId(),
        query.from() == null ? NO_LOWER_BOUND : query.from(),
        query.to() == null ? NO_UPPER_BOUND : query.to(),
        unrestricted,
        plantIds,
        PageRequest.of(page, size, sort));
    return new AuditLogListResponse(
        result.stream().map(this::toView).toList(),
        result.getTotalElements(),
        page,
        size,
        sortName(sort));
  }

  private AuditLogEntryView toView(AuditLogEntity entry) {
    return new AuditLogEntryView(
        entry.getId(),
        entry.getActorId(),
        entry.getActorName(),
        entry.getAction(),
        entry.getEntityType(),
        entry.getEntityId(),
        entry.getEntityLabel(),
        entry.getPlantId(),
        readJson(entry.getPreviousValue()),
        readJson(entry.getNewValue()),
        entry.getCreatedAt(),
        entry.getDecisionId());
  }

  private Map<String, Object> readJson(String value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.readValue(value, VALUES_TYPE);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to decode audit values", exception);
    }
  }

  private String normalizeActor(String actor) {
    if (actor == null || actor.isBlank()) {
      return null;
    }
    return "%" + actor.trim().toLowerCase(Locale.ROOT)
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%";
  }

  private int normalizeSize(int size) {
    if (size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private Sort normalizeSort(String sort) {
    if (sort == null || sort.isBlank()) {
      return Sort.by(Sort.Direction.DESC, "createdAt");
    }
    var parts = sort.split(",", 2);
    var property = parts[0].trim();
    if (!ALLOWED_SORTS.contains(property)) {
      throw new InvalidAuditLogQueryException();
    }
    var direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : Sort.Direction.ASC;
    return Sort.by(direction, property);
  }

  private String sortName(Sort sort) {
    var order = sort.iterator().next();
    return order.getProperty() + "," + order.getDirection().name().toLowerCase(Locale.ROOT);
  }

  public record AuditLogQuery(
      AuditEntityType entityType,
      UUID entityId,
      String actor,
      UUID plantId,
      Instant from,
      Instant to,
      int page,
      int size,
      String sort) {
  }

  public static class InvalidAuditLogQueryException extends RuntimeException {
  }
}
