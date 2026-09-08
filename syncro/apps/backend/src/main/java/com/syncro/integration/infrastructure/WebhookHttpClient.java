package com.syncro.integration.infrastructure;

import com.syncro.config.WebhookProperties;
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
 * HTTP adapter for outbound webhook POSTs (story 22-1, blueprint I4).
 *
 * <p>Mirrors {@code WahaClient}: a configurable connect + read timeout
 * ({@code syncro.webhook.resilience.timeout}) and a Resilience4j circuit breaker that
 * opens after the configured failure-rate threshold. 5xx responses and
 * connection/timeout failures THROW so the breaker records them as failures; 4xx
 * responses return a failed {@link Result} without tripping the breaker (a client
 * error is deterministic — retrying can never succeed and must not block deliveries to
 * healthy subscribers).
 *
 * <p>Security invariants: the HMAC secret is never logged (it only ever appears inside
 * the precomputed signature header value, which is also never logged); response detail
 * is truncated to {@value MAX_DETAIL_LENGTH} characters.
 */
@Component
public class WebhookHttpClient {

  private static final Logger log = LoggerFactory.getLogger(WebhookHttpClient.class);
  static final int MAX_DETAIL_LENGTH = 512;
  static final String CIRCUIT_BREAKER_NAME = "webhook";
  /** Stable detail string identifying a circuit-open skip on the delivery row. */
  public static final String CIRCUIT_OPEN_DETAIL = "Circuit breaker is OPEN";

  private final RestClient restClient;
  private final CircuitBreaker circuitBreaker;

  public WebhookHttpClient(WebhookProperties properties,
      CircuitBreakerRegistry circuitBreakerRegistry) {
    var resilience = properties.resilience();

    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(resilience.timeout());
    requestFactory.setReadTimeout(resilience.timeout());

    this.restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .build();

    CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
        .failureRateThreshold(resilience.failureRateThreshold())
        .minimumNumberOfCalls(resilience.minimumNumberOfCalls())
        .slidingWindowSize(resilience.slidingWindowSize())
        .waitDurationInOpenState(resilience.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(resilience.permittedCallsInHalfOpen())
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        // 5xx server errors and connection/timeout failures trip the circuit; 4xx client
        // errors are returned as failed Results in doPost() and never reach this point.
        .recordExceptions(WebhookHttpStatusException.class, ResourceAccessException.class,
            Exception.class)
        .ignoreExceptions(CallNotPermittedException.class)
        .build();

    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME, cbConfig);
  }

  /**
   * POSTs a signed JSON payload to the subscriber endpoint. Never throws: a circuit-open
   * state returns {@code success=false, httpStatus=0, detail=CIRCUIT_OPEN_DETAIL}
   * without making an HTTP call.
   *
   * @param url             the config's endpoint URL (typed config, never user-composed)
   * @param jsonBody        the serialized payload to deliver
   * @param signatureHeader the {@code X-Syncro-Signature} hex HMAC value (never logged)
   * @param traceId         correlation ID for log tracing
   */
  public Result post(String url, String jsonBody, String signatureHeader, String traceId) {
    try {
      return circuitBreaker.executeSupplier(() -> doPost(url, jsonBody, signatureHeader, traceId));
    } catch (CallNotPermittedException e) {
      log.info("[WEBHOOK][traceId={}] Circuit breaker OPEN — delivery skipped state={}",
          traceId, circuitBreaker.getState());
      return new Result(false, 0, CIRCUIT_OPEN_DETAIL);
    } catch (WebhookHttpStatusException e) {
      // Non-2xx 5xx already logged in doPost; the circuit breaker recorded the failure.
      return new Result(false, e.httpStatus, truncate(e.detail));
    } catch (ResourceAccessException e) {
      log.error("[WEBHOOK][traceId={}] Timeout or connection error: {}", traceId, e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()));
    } catch (Exception e) {
      log.error("[WEBHOOK][traceId={}] Unexpected error: {}", traceId, e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()));
    }
  }

  /** Returns the underlying circuit breaker (used by tests). */
  public CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }

  /**
   * Performs the actual HTTP call. Throws on 5xx so the circuit breaker records it as a
   * failure. 4xx client errors are returned as a failed {@link Result} without throwing
   * (deterministic — retry won't help, and must not open the circuit for other configs).
   */
  private Result doPost(String url, String jsonBody, String signatureHeader, String traceId) {
    ResponseEntity<String> response = restClient.post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Syncro-Signature", signatureHeader)
        .body(jsonBody)
        .retrieve()
        .onStatus(HttpStatusCode::isError, (req, res) -> {
          // suppress default exception so we can throw our own typed one below
        })
        .toEntity(String.class);

    String detail = truncate(response.getBody());
    int statusCode = response.getStatusCode().value();
    log.info("[WEBHOOK][traceId={}] delivery attempt status={}", traceId, statusCode);

    if (response.getStatusCode().is5xxServerError()) {
      // 5xx is a transient server error — throw so the circuit breaker records a failure
      throw new WebhookHttpStatusException(statusCode, detail);
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      return new Result(false, statusCode, detail);
    }
    return new Result(true, statusCode, detail);
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

  public record Result(boolean success, int httpStatus, String detail) {
  }

  /**
   * Thrown when a subscriber returns a 5xx status. Allows the circuit breaker to record
   * the failure while still carrying the status code and detail for the caller.
   */
  static final class WebhookHttpStatusException extends RuntimeException {

    final int httpStatus;
    final String detail;

    WebhookHttpStatusException(int httpStatus, String detail) {
      super("Webhook HTTP " + httpStatus);
      this.httpStatus = httpStatus;
      this.detail = detail;
    }
  }
}
