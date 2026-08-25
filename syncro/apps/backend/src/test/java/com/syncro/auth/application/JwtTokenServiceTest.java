package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 9-4: pre-deploy tokens carrying legacy role names (MANAGE/VIEWER) fail as
 * clean invalid-token auth failures (401 re-login), never a 500 from valueOf.
 */
class JwtTokenServiceTest {

  private static final String SECRET = "test-secret-for-jwt-unit-0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final JwtTokenService service =
      new JwtTokenService(new JwtProperties(SECRET, "syncro-test", 30), clock);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("9.4-AUTH-001 P1 current tokens round-trip through parse")
  void currentTokenRoundTrips() {
    var user = new AuthUserEntity(
        java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "tech@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true, NOW, NOW);

    var parsed = service.parse(service.createToken(user));

    assertThat(parsed.id()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(parsed.loginIdentifier()).isEqualTo("tech@syncro.dev");
    assertThat(parsed.applicationRole()).isEqualTo(ApplicationRole.TECHNICIAN);
  }

  @Test
  @DisplayName("9.4-AUTH-002 P0 legacy MANAGE/VIEWER role claims are rejected as invalid tokens")
  void legacyRoleClaimsFailClosed() {
    assertThatThrownBy(() -> service.parse(signedLegacyToken("MANAGE")))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
    assertThatThrownBy(() -> service.parse(signedLegacyToken("VIEWER")))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
    assertThatThrownBy(() -> service.parse(signedLegacyToken("NOT_A_ROLE")))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
  }

  /** Mints a structurally valid, correctly signed token whose role claim predates V45. */
  private String signedLegacyToken(String role) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("iss", "syncro-test");
    payload.put("sub", "22222222-2222-2222-2222-222222222222");
    payload.put("loginIdentifier", "legacy@syncro.dev");
    payload.put("role", role);
    payload.put("iat", NOW.getEpochSecond());
    payload.put("exp", NOW.plusSeconds(600).getEpochSecond());
    try {
      var header = base64Url(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
      var body = base64Url(objectMapper.writeValueAsBytes(payload));
      var unsigned = header + "." + body;
      return unsigned + "." + sign(unsigned);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String sign(String value) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return base64Url(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  private String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
