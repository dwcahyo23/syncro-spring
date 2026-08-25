package com.syncro.notification.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.notification.domain.WahaTemplate;
import com.syncro.notification.infrastructure.WahaTemplateEntity;
import com.syncro.notification.infrastructure.WahaTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WahaTemplateService {

  private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{[^}]+\\}");

  private final WahaTemplateRepository repository;
  private final Clock clock;

  public WahaTemplateService(WahaTemplateRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public WahaTemplate getActiveTemplate() {
    return repository.findByTemplateKey(WahaTemplate.DEFAULT_KEY)
        .map(this::toDomain)
        .orElseThrow(WahaTemplateNotFoundException::new);
  }

  @Transactional
  public WahaTemplate upsertTemplate(String body, AuthenticatedUser actor) {
    if (actor.applicationRole() != com.syncro.auth.domain.ApplicationRole.SUPER_ADMIN
        && actor.applicationRole() != com.syncro.auth.domain.ApplicationRole.MANAGER_MAINTENANCE) {
      throw new WahaTemplateForbiddenException();
    }
    validateVariables(body);

    Instant now = Instant.now(clock);
    repository.upsert(UUID.randomUUID(), WahaTemplate.DEFAULT_KEY, body, now);
    WahaTemplateEntity saved = repository.findByTemplateKey(WahaTemplate.DEFAULT_KEY)
        .orElseThrow(WahaTemplateNotFoundException::new);
    return toDomain(saved);
  }

  private void validateVariables(String body) {
    Set<String> unknown = new HashSet<>();
    Matcher matcher = VARIABLE_PATTERN.matcher(body);
    while (matcher.find()) {
      String token = matcher.group();
      if (!WahaTemplate.KNOWN_VARIABLES.contains(token)) {
        unknown.add(token);
      }
    }
    if (!unknown.isEmpty()) {
      throw new WahaTemplateValidationException(unknown);
    }
  }

  private WahaTemplate toDomain(WahaTemplateEntity entity) {
    return new WahaTemplate(
        entity.getId(),
        entity.getTemplateKey(),
        entity.getBody(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  public static class WahaTemplateForbiddenException extends RuntimeException {
    public WahaTemplateForbiddenException() {
      super("You do not have permission to modify WAHA templates.");
    }
  }

  public static class WahaTemplateNotFoundException extends RuntimeException {
    public WahaTemplateNotFoundException() {
      super("WAHA template not found.");
    }
  }

  public static class WahaTemplateValidationException extends RuntimeException {
    private final Set<String> unknownVariables;

    public WahaTemplateValidationException(Set<String> unknownVariables) {
      super("Template contains unknown variables: " + unknownVariables);
      this.unknownVariables = unknownVariables;
    }

    public Set<String> getUnknownVariables() {
      return unknownVariables;
    }
  }
}
