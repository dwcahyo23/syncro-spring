package com.syncro.machine.infrastructure;

import com.syncro.auth.application.UserJobScopeReader;
import com.syncro.machine.domain.ResponsibilityLevel;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Machine-module adapter for the auth-side {@link UserJobScopeReader} port. Translates the
 * uppercase level-string contract into {@link ResponsibilityLevel} and delegates to the
 * responsibility repository. Unknown levels fail fast — contract drift must be loud.
 */
@Component
public class MachineResponsibilityJobScopeAdapter implements UserJobScopeReader {

  private final MachineResponsibilityRepository repository;

  public MachineResponsibilityJobScopeAdapter(MachineResponsibilityRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean hasAnyLevel(UUID userId, Set<String> responsibilityLevels) {
    Collection<ResponsibilityLevel> levels = responsibilityLevels.stream()
        .map(ResponsibilityLevel::valueOf)
        .toList();
    return repository.existsByUserIdAndResponsibilityLevelIn(userId, levels);
  }
}
