package com.syncro.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.config.JwtProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
  };
  private final JwtProperties properties;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock clock;

  public JwtTokenService(JwtProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public String createToken(AuthUserEntity user) {
    var now = Instant.now(clock);
    var expiresAt = now.plusSeconds(expiresInSeconds());
    var header = Map.of("alg", "HS256", "typ", "JWT");
    var payload = new LinkedHashMap<String, Object>();
    payload.put("iss", properties.issuer());
    payload.put("sub", user.getId().toString());
    payload.put("loginIdentifier", user.getLoginIdentifier());
    payload.put("role", user.getApplicationRole().name());
    payload.put("iat", now.getEpochSecond());
    payload.put("exp", expiresAt.getEpochSecond());
    var unsignedToken = encodeJson(header) + "." + encodeJson(payload);
    return unsignedToken + "." + sign(unsignedToken);
  }

  public AuthenticatedUser parse(String token) {
    var parts = token.split("\\.");
    if (parts.length != 3) {
      throw new InvalidTokenException();
    }
    var unsignedToken = parts[0] + "." + parts[1];
    if (!constantTimeEquals(sign(unsignedToken), parts[2])) {
      throw new InvalidTokenException();
    }
    var payload = decodePayload(parts[1]);
    if (!properties.issuer().equals(payload.get("iss"))) {
      throw new InvalidTokenException();
    }
    var exp = asLong(payload.get("exp"));
    if (exp <= Instant.now(clock).getEpochSecond()) {
      throw new InvalidTokenException();
    }
    // Pre-deploy tokens may carry legacy role names (MANAGE/VIEWER); fail closed to
    // the standard invalid-token path (401 re-login) instead of a 500 from valueOf.
    ApplicationRole role;
    try {
      role = ApplicationRole.valueOf(String.valueOf(payload.get("role")));
    } catch (IllegalArgumentException exception) {
      throw new InvalidTokenException();
    }
    return new AuthenticatedUser(
        String.valueOf(payload.get("sub")),
        String.valueOf(payload.get("loginIdentifier")),
        role);
  }

  public long expiresInSeconds() {
    return properties.ttlMinutes() * 60;
  }

  private String encodeJson(Object value) {
    try {
      return base64Url(objectMapper.writeValueAsBytes(value));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to create auth token");
    }
  }

  private Map<String, Object> decodePayload(String value) {
    try {
      return objectMapper.readValue(Base64.getUrlDecoder().decode(value), PAYLOAD_TYPE);
    } catch (IllegalArgumentException | IOException exception) {
      throw new InvalidTokenException();
    }
  }

  private String sign(String value) {
    try {
      var mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return base64Url(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to sign auth token");
    }
  }

  private String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private boolean constantTimeEquals(String left, String right) {
    return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    throw new InvalidTokenException();
  }

  public record AuthenticatedUser(String id, String loginIdentifier, ApplicationRole applicationRole) {
  }

  public static class InvalidTokenException extends RuntimeException {
  }
}
