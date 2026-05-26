package com.syncro;

import com.syncro.auth.infrastructure.AuthUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
    "server.port=0",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "spring.data.redis.host=localhost",
    "spring.data.redis.port=6379",
    "syncro.mqtt.host=localhost",
    "syncro.mqtt.port=1883",
    "syncro.mqtt.username=test",
    "syncro.mqtt.password=test",
    "syncro.mqtt.client-id=syncro-test",
    "syncro.mqtt.topic-filter=factory/+/+/telemetry",
    "syncro.influxdb.url=http://localhost:8086",
    "syncro.influxdb.username=test",
    "syncro.influxdb.password=test",
    "syncro.influxdb.token=test",
    "syncro.influxdb.org=test",
    "syncro.influxdb.bucket=test",
    "syncro.waha.url=http://localhost:3000",
    "syncro.waha.api-key=test",
    "syncro.auth.jwt.secret=test-secret-for-context-loads-32",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
class SyncroBackendApplicationTests {

  @MockitoBean
  private AuthUserRepository authUserRepository;

  @Test
  void contextLoads() {
  }
}
