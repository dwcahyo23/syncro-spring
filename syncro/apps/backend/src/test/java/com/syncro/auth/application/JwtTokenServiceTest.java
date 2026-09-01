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

  // -------------------------------------------------------------------------
  // Story 14-4 auto-login tokens (FR-181)
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("14.4-AUTH-001 AUTO_LOGIN token round-trips with wa/wo claims")
  void autoLoginTokenRoundTrips() {
    var user = new AuthUserEntity(
        java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"),
        "pl@syncro.dev", "hash", ApplicationRole.PRODUCTION_LEADER, true, NOW, NOW);
    var token = service.createToken(user, java.time.Duration.ofMinutes(15),
        Map.of("typ", "AUTO_LOGIN", "wa", "6281234567890", "wo", "WO-260800001"));

    var parsed = service.parseAutoLogin(token);

    assertThat(parsed.user().id()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(parsed.user().applicationRole()).isEqualTo(ApplicationRole.PRODUCTION_LEADER);
    assertThat(parsed.whatsappNumber()).isEqualTo("6281234567890");
    assertThat(parsed.workOrderId()).isEqualTo("WO-260800001");
  }

  @Test
  @DisplayName("14.4-AUTH-002 expired AUTO_LOGIN token is rejected")
  void expiredAutoLoginRejected() {
    var user = new AuthUserEntity(
        java.util.UUID.fromString("44444444-4444-4444-4444-444444444444"),
        "pl@syncro.dev", "hash", ApplicationRole.PRODUCTION_LEADER, true, NOW, NOW);
    var token = service.createToken(user, java.time.Duration.ofMinutes(-1),
        Map.of("typ", "AUTO_LOGIN", "wa", "6281234567890", "wo", "WO-260800001"));

    assertThatThrownBy(() -> service.parseAutoLogin(token))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
  }

  @Test
  @DisplayName("14.4-AUTH-003 AUTO_LOGIN without typ claim is rejected by parseAutoLogin")
  void missingTypRejected() {
    var user = new AuthUserEntity(
        java.util.UUID.fromString("55555555-5555-5555-5555-555555555555"),
        "pl@syncro.dev", "hash", ApplicationRole.PRODUCTION_LEADER, true, NOW, NOW);
    // Regular token (no typ=AUTO_LOGIN) must not pass parseAutoLogin
    var token = service.createToken(user);

    assertThatThrownBy(() -> service.parseAutoLogin(token))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
  }

  @Test
  @DisplayName("14.4-AUTH-004 createToken with ttl+extraClaims does not clobber base claims")
  void extraClaimsCannotClobberBaseClaims() {
    var user = new AuthUserEntity(
        java.util.UUID.fromString("66666666-6666-6666-6666-666666666666"),
        "pl@syncro.dev", "hash", ApplicationRole.PRODUCTION_LEADER, true, NOW, NOW);
    var token = service.createToken(user, java.time.Duration.ofMinutes(5),
        Map.of("sub", "attacker", "typ", "AUTO_LOGIN", "wa", "6281234567890", "wo", "WO-260800001"));

    var parsed = service.parse(token);
    assertThat(parsed.id()).isEqualTo("66666666-6666-6666-6666-666666666666");
  }

  @Test
  @DisplayName("14.4-AUTH-005 parse rejects non-UUID subject (DW-137)")
  void parseRejectsNonUuidSubject() {
    assertThatThrownBy(() -> service.parse(signedTokenWithSubject("not-a-uuid")))
        .isInstanceOf(JwtTokenService.InvalidTokenException.class);
  }

  @Test
  @DisplayName("14.4-AUTH-006 parseAutoLogin rejects non-UUID subject (DW-137)")
  void parseAutoLoginRejectsNonUuidSubject() {
    assertThatThrownBy(() -> service.parseAutoLogin(signedAutoLoginWithSubject("not-a-uuid")))
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

  /** Mints a signed regular token whose subject is the given value (DW-137). */
  private String signedTokenWithSubject(String subject) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("iss", "syncro-test");
    payload.put("sub", subject);
    payload.put("loginIdentifier", "malformed@syncro.dev");
    payload.put("role", ApplicationRole.TECHNICIAN.name());
    payload.put("iat", NOW.getEpochSecond());
    payload.put("exp", NOW.plusSeconds(600).getEpochSecond());
    return mint(payload);
  }

  /** Mints a signed AUTO_LOGIN token whose subject is the given value (DW-137). */
  private String signedAutoLoginWithSubject(String subject) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("iss", "syncro-test");
    payload.put("typ", "AUTO_LOGIN");
    payload.put("sub", subject);
    payload.put("loginIdentifier", "malformed@syncro.dev");
    payload.put("role", ApplicationRole.PRODUCTION_LEADER.name());
    payload.put("wa", "6281234567890");
    payload.put("wo", "WO-260800001");
    payload.put("iat", NOW.getEpochSecond());
    payload.put("exp", NOW.plusSeconds(600).getEpochSecond());
    return mint(payload);
  }

  private String mint(Map<String, Object> payload) {
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
