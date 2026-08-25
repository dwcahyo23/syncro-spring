package com.syncro.authz.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.config.OpaProperties;
import com.syncro.config.OpaResilienceProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter for the OPA sidecar data-API rule calls ({@code POST /v1/data/syncro/authz/{rule}}).
 *
 * <p>Construction mirrors {@code WahaClient}: configurable connect + read timeout and a
 * programmatic Resilience4j circuit breaker named {@code opa}. 5xx responses and
 * connection/timeout failures trip the circuit; every failure is reported through the
 * never-throw {@link Result} envelope so callers can turn it into a policy outcome.
 */
@Component
public class OpaClient {

  private static final Logger log = LoggerFactory.getLogger(OpaClient.class);
  static final int MAX_DETAIL_LENGTH = 512;
  static final String CIRCUIT_BREAKER_NAME = "opa";
  /** Stable detail string returned when the circuit is OPEN. */
  public static final String CIRCUIT_OPEN_DETAIL = "Circuit breaker is OPEN";
  static final String RULE_URI_TEMPLATE = "/v1/data/syncro/authz/%s";

  private final RestClient restClient;
  private final CircuitBreaker circuitBreaker;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OpaClient(OpaProperties opaProperties,
      OpaResilienceProperties resilienceProperties,
      CircuitBreakerRegistry circuitBreakerRegistry) {

    // Configure RestClient with connect + read timeout from properties
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(resilienceProperties.timeout());
    requestFactory.setReadTimeout(resilienceProperties.timeout());

    this.restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .baseUrl(opaProperties.url())
        .build();

    // Build circuit breaker from registry; unique config per "opa" instance
    CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
        .failureRateThreshold(resilienceProperties.failureRateThreshold())
        .minimumNumberOfCalls(resilienceProperties.minimumNumberOfCalls())
        .slidingWindowSize(resilienceProperties.slidingWindowSize())
        .waitDurationInOpenState(resilienceProperties.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(resilienceProperties.permittedCallsInHalfOpen())
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        // 5xx server errors and connection/timeout failures trip the circuit; other non-2xx
        // responses are returned as failed Results in doPost() and never reach this point.
        .recordExceptions(OpaHttpStatusException.class, ResourceAccessException.class,
            Exception.class)
        .ignoreExceptions(CallNotPermittedException.class)
        .build();

    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME, cbConfig);
  }

  /**
   * Evaluates the given rule ({@code allow} or {@code actions}) with the supplied input.
   *
   * <p>Returns a {@link Result} — never throws. A circuit-open state returns
   * {@code success=false, httpStatus=0, detail="Circuit breaker is OPEN"} without making an
   * HTTP call.
   *
   * @param rule  rule name below /v1/data/syncro/authz/ (never contains user input)
   * @param input assembled OpaInput payload (contains no secrets by schema)
   */
  public Result post(String rule, Object input) {
    try {
      return circuitBreaker.executeSupplier(() -> doPost(rule, input));
    } catch (CallNotPermittedException e) {
      log.info("[OPA] Circuit breaker OPEN — rule {} skipped state={}", rule,
          circuitBreaker.getState());
      return new Result(false, 0, CIRCUIT_OPEN_DETAIL, null, false);
    } catch (OpaHttpStatusException e) {
      // Non-2xx response already logged via body truncation; circuit recorded the failure
      return new Result(false, e.httpStatus, truncate(e.detail), null, false);
    } catch (ResourceAccessException e) {
      log.error("[OPA] Timeout or connection error: {}", e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()), null, false);
    } catch (Exception e) {
      log.error("[OPA] Unexpected error: {}", e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()), null, false);
    }
  }

  /**
   * Returns the underlying circuit breaker (used by tests).
   */
  public CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }

  // -------------------------------------------------------------------------
  // Internal
  // -------------------------------------------------------------------------

  /**
   * Performs the actual HTTP call. Throws on 5xx so the circuit breaker records it as a
   * failure. Other non-2xx statuses are returned as a failed {@link Result} without
   * throwing (deterministic — retry won't help).
   */
  private Result doPost(String rule, Object input) {
    ResponseEntity<String> response = restClient.post()
        .uri(RULE_URI_TEMPLATE.formatted(rule))
        .contentType(MediaType.APPLICATION_JSON)
        .body(input)
        .retrieve()
        .onStatus(HttpStatusCode::isError, (req, res) -> {
          // suppress default exception so we can throw our own typed one below
        })
        .toEntity(String.class);

    int statusCode = response.getStatusCode().value();
    String detail = truncate(response.getBody());

    if (response.getStatusCode().is5xxServerError()) {
      // 5xx is a transient server error — throw so the circuit breaker records it as a failure
      throw new OpaHttpStatusException(statusCode, detail);
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      return new Result(false, statusCode, detail, null, false);
    }

    String decisionId = null;
    boolean allowed = false;
    try {
      var envelope = objectMapper.readTree(response.getBody());
      if (envelope.path("decision_id").isTextual()) {
        decisionId = envelope.path("decision_id").asText();
      }
      if (envelope.path("result").isBoolean()) {
        allowed = envelope.path("result").asBoolean();
      }
    } catch (JsonProcessingException e) {
      log.error("[OPA] Malformed decision envelope for rule {}: {}", rule, e.getMessage());
      return new Result(false, statusCode, detail, null, false);
    }
    log.debug("[OPA] rule {} evaluated status={} decisionId={}", rule, statusCode, decisionId);
    return new Result(true, statusCode, detail, decisionId, allowed);
  }

  static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > MAX_DETAIL_LENGTH ? value.substring(0, MAX_DETAIL_LENGTH) : value;
  }

  // -------------------------------------------------------------------------
  // Types
  // -------------------------------------------------------------------------

  /**
   * Raw rule-call envelope. {@code allowed} is meaningful only for boolean rules
   * ({@code allow}); array rules (actions) are interpreted by the caller from {@code body}.
   */
  public record Result(boolean success, int httpStatus, String body, String decisionId,
      boolean allowed) {
  }

  /**
   * Thrown when OPA returns a 5xx status. Allows the circuit breaker to record the
   * failure while still carrying the status code and detail for the caller.
   */
  static final class OpaHttpStatusException extends RuntimeException {

    final int httpStatus;
    final String detail;

    OpaHttpStatusException(int httpStatus, String detail) {
      super("OPA HTTP " + httpStatus);
      this.httpStatus = httpStatus;
      this.detail = detail;
    }
  }
}
