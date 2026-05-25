package com.syncro;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
    "syncro.waha.api-key=test"
})
class SyncroBackendApplicationTests {

  @Test
  void contextLoads() {
  }
}
