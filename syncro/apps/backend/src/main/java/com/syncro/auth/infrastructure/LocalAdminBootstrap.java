package com.syncro.auth.infrastructure;

import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.LocalAdminProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "syncro.auth.local-admin", name = "enabled", havingValue = "true")
public class LocalAdminBootstrap implements ApplicationRunner {
  private final LocalAdminProperties properties;
  private final AuthUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public LocalAdminBootstrap(LocalAdminProperties properties, AuthUserRepository users, PasswordEncoder passwordEncoder,
      Clock clock) {
    this.properties = properties;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!properties.enabled()) {
      return;
    }
    users.findByLoginIdentifierIgnoreCase(properties.loginIdentifier()).orElseGet(() -> {
      var now = Instant.now(clock);
      return users.save(new AuthUserEntity(
          UUID.randomUUID(),
          properties.loginIdentifier(),
          passwordEncoder.encode(properties.password()),
          ApplicationRole.SUPER_ADMIN,
          true,
          now,
          now));
    });
  }
}
