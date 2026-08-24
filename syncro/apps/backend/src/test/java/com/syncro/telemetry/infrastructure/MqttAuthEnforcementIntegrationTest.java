package com.syncro.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves EMQX password authentication is enforced with the repo's auth config (DW-1).
 * Boots a real emqx:6.2.2 with the committed {@code emqx.conf} and
 * {@code auth-bootstrap.csv}, then exercises the MQTT CONNECT path with the bootstrap
 * credentials the local backend now defaults to.
 */
@Testcontainers
class MqttAuthEnforcementIntegrationTest {

  private static final String BACKEND_USER = "syncro_backend";
  private static final String EMQX_CONF = readFile("infra/emqx/etc/emqx.conf");
  private static final String AUTH_CSV = readFile("infra/emqx/etc/auth-bootstrap.csv");
  private static final String BACKEND_PASSWORD = backendPasswordFromCsv();

  private static String backendPasswordFromCsv() {
    return AUTH_CSV.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .map(line -> line.split(","))
        .filter(parts -> parts.length >= 3 && BACKEND_USER.equals(parts[0]))
        .map(parts -> parts[1])
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("auth-bootstrap.csv missing syncro_backend row"));
  }

  private static String readFile(String repoRelative) {
    try {
      return Files.readString(Path.of("..", "..", repoRelative).toAbsolutePath().normalize());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Container
  static final GenericContainer<?> emqx = new GenericContainer<>("emqx/emqx:6.2.2")
      .withReuse(true)
      .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-c",
          "cat > /opt/emqx/etc/emqx.conf << 'EOF'\n" + EMQX_CONF + "\nEOF\n"
              + "mkdir -p /opt/emqx/etc/syncro\n"
              + "cat > /opt/emqx/etc/syncro/auth-bootstrap.csv << 'EOF'\n" + AUTH_CSV + "\nEOF\n"
              + "exec /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground"))
      .withExposedPorts(1883)
      .waitingFor(Wait.forLogMessage(".*running now.*", 1)
          .withStartupTimeout(Duration.ofSeconds(180)));

  @Test
  @DisplayName("DW-1-AUTH-001 valid bootstrap credentials connect successfully")
  void validCredentialsConnectSucceeds() throws MqttException {
    var options = new MqttConnectOptions();
    options.setUserName(BACKEND_USER);
    options.setPassword(BACKEND_PASSWORD.toCharArray());
    options.setConnectionTimeout(15);
    var client = new MqttClient(brokerUrl(), MqttClient.generateClientId());
    try {
      client.connect(options);
      assertThat(client.isConnected()).isTrue();
    } finally {
      if (client.isConnected()) {
        client.disconnect();
      }
      client.close();
    }
  }

  @Test
  @DisplayName("DW-1-AUTH-002 wrong password is rejected by the broker")
  void wrongPasswordIsRejected() throws MqttException {
    var options = new MqttConnectOptions();
    options.setUserName(BACKEND_USER);
    options.setPassword("wrong-password".toCharArray());
    options.setConnectionTimeout(15);
    var client = new MqttClient(brokerUrl(), MqttClient.generateClientId());
    try {
      assertThatThrownBy(() -> client.connect(options)).isInstanceOf(MqttSecurityException.class);
    } finally {
      client.close();
    }
  }

  private static String brokerUrl() {
    return "tcp://" + emqx.getHost() + ":" + emqx.getMappedPort(1883);
  }
}