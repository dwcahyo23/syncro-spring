package com.syncro.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.domain.ApplicationRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthUserRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  void migrationCreatesAuthUsersWithUniqueLoginAndHashedPassword() {
    var now = Instant.parse("2026-05-26T00:00:00Z");
    var passwordHash = passwordEncoder.encode("syncro-admin-dev");
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        "admin@syncro.dev",
        passwordHash,
        ApplicationRole.SUPER_ADMIN,
        true,
        now,
        now));

    assertThat(user.getPasswordHash()).isNotEqualTo("syncro-admin-dev");
    assertThat(passwordEncoder.matches("syncro-admin-dev", user.getPasswordHash())).isTrue();
    assertThat(users.findByLoginIdentifierIgnoreCase("ADMIN@SYNCRO.DEV")).contains(user);

    var duplicate = new AuthUserEntity(
        UUID.randomUUID(),
        "admin@syncro.dev",
        passwordEncoder.encode("other-password"),
        ApplicationRole.VIEWER,
        true,
        now,
        now);

    assertThatThrownBy(() -> users.saveAndFlush(duplicate)).isInstanceOf(RuntimeException.class);
  }
}
