package com.syncro.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.auth.domain.ApplicationRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "server.port=0",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
@Testcontainers
@Transactional
class AuthUserRepositoryIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

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
