package com.syncro.notification.infrastructure;

import com.syncro.config.WahaProperties;
import com.syncro.config.WahaResilienceProperties;
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
 * HTTP adapter for WAHA (WhatsApp API) send-text calls.
 *
 * <p>Wraps every outbound call with:
 * <ol>
 *   <li>A configurable connect + read timeout ({@code syncro.waha.resilience.timeout}).</li>
 *   <li>A Resilience4j circuit breaker that opens after the configured failure-rate
 *       threshold is exceeded, preventing further calls until a half-open probe succeeds.</li>
 * </ol>
 *
 * <p>Security invariants:
 * <ul>
 *   <li>The WAHA API key is never logged — it is injected as a default header only.</li>
 *   <li>Recipient phone numbers are never logged.</li>
 *   <li>Response / error detail is truncated to {@value MAX_DETAIL_LENGTH} characters.</li>
 * </ul>
 */
@Component
public class WahaClient {

  private static final Logger log = LoggerFactory.getLogger(WahaClient.class);
  static final int MAX_DETAIL_LENGTH = 512;
  static final String CIRCUIT_BREAKER_NAME = "waha";
  /** Stable detail string written to notification_attempts when circuit is OPEN. */
  public static final String CIRCUIT_OPEN_DETAIL = "Circuit breaker is OPEN";

  private final RestClient restClient;
  private final CircuitBreaker circuitBreaker;

  public WahaClient(WahaProperties wahaProperties,
      WahaResilienceProperties resilienceProperties,
      CircuitBreakerRegistry circuitBreakerRegistry) {

    // Configure RestClient with connect + read timeout from properties
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(resilienceProperties.timeout());
    requestFactory.setReadTimeout(resilienceProperties.timeout());

    this.restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .baseUrl(wahaProperties.url())
        .defaultHeader("X-Api-Key", wahaProperties.apiKey())
        .build();

    // Build circuit breaker from registry; unique config per "waha" instance
    CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
        .failureRateThreshold(resilienceProperties.failureRateThreshold())
        .minimumNumberOfCalls(resilienceProperties.minimumNumberOfCalls())
        .slidingWindowSize(resilienceProperties.slidingWindowSize())
        .waitDurationInOpenState(resilienceProperties.waitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(resilienceProperties.permittedCallsInHalfOpen())
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        // 5xx server errors and connection/timeout failures trip the circuit; 4xx client errors
        // are returned as failed Results in doSend() and intentionally never reach this point.
        .recordExceptions(WahaHttpStatusException.class, ResourceAccessException.class,
            Exception.class)
        .ignoreExceptions(CallNotPermittedException.class)
        .build();

    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME, cbConfig);
  }

  /**
   * Sends a text message to the given phone number via WAHA.
   *
   * <p>Returns a {@link Result} — never throws. A circuit-open state returns
   * {@code success=false, httpStatus=0, detail="Circuit breaker is OPEN"} without making an
   * HTTP call.
   *
   * @param recipientPhone the recipient's phone number (never logged)
   * @param messageText    the rendered WhatsApp message body
   * @param traceId        correlation ID for log tracing
   */
  public Result send(String recipientPhone, String messageText, String traceId) {
    try {
      return circuitBreaker.executeSupplier(() -> doSend(recipientPhone, messageText, traceId));
    } catch (CallNotPermittedException e) {
      log.info("[WAHA][traceId={}] Circuit breaker OPEN — send skipped state={}",
          traceId, circuitBreaker.getState());
      return new Result(false, 0, CIRCUIT_OPEN_DETAIL);
    } catch (WahaHttpStatusException e) {
      // Non-2xx response already logged in doSend; circuit breaker recorded the failure
      return new Result(false, e.httpStatus, truncate(e.detail));
    } catch (ResourceAccessException e) {
      log.error("[WAHA][traceId={}] Timeout or connection error: {}", traceId, e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()));
    } catch (Exception e) {
      log.error("[WAHA][traceId={}] Unexpected error: {}", traceId, e.getMessage());
      return new Result(false, 0, truncate(e.getMessage()));
    }
  }

  /**
   * Returns the underlying circuit breaker (used by {@link WahaCircuitBreakerHealthIndicator}
   * and tests).
   */
  public CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }

  // -------------------------------------------------------------------------
  // Internal
  // -------------------------------------------------------------------------

  /**
   * Performs the actual HTTP call. Throws on 5xx so the circuit breaker records it as a
   * failure. 4xx client errors are returned as a failed {@link Result} without throwing
   * (deterministic — retry won't help, and must not open the circuit for other jobs).
   */
  private Result doSend(String recipientPhone, String messageText, String traceId) {
    var payload = new SendTextRequest(recipientPhone + "@c.us", messageText, "default");

    ResponseEntity<String> response = restClient.post()
        .uri("/api/sendText")
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload)
        .retrieve()
        .onStatus(HttpStatusCode::isError, (req, res) -> {
          // suppress default exception so we can throw our own typed one below
        })
        .toEntity(String.class);

    String detail = truncate(response.getBody());
    int statusCode = response.getStatusCode().value();
    log.info("[WAHA][traceId={}] send attempt phone=*** status={}", traceId, statusCode);

    if (response.getStatusCode().is5xxServerError()) {
      // GOWS reports an unregistered phone number as HTTP 500 with body "no LID found".
      // That is a deterministic invalid-recipient error — retrying can never succeed, so
      // mirror the 4xx path and return a failed Result instead of tripping the circuit,
      // which would block delivery of all other notification jobs. Other 5xx bodies remain
      // transient server errors and still record a failure.
      if (detail != null && detail.contains("no LID found")) {
        return new Result(false, statusCode, detail);
      }
      // 5xx is a transient server error — throw so the circuit breaker records it as a failure
      throw new WahaHttpStatusException(statusCode, detail);
    }
    if (!response.getStatusCode().is2xxSuccessful()) {
      // 4xx is a deterministic client error (bad phone, invalid payload) — report the failure
      // but do NOT trip the circuit: retrying later will never succeed and it must not block
      // delivery of other, well-formed jobs.
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

  record SendTextRequest(String chatId, String text, String session) {
  }

  /**
   * Thrown when WAHA returns a non-2xx HTTP status. Allows the circuit breaker to record
   * the failure while still carrying the status code and detail for the caller.
   */
  static final class WahaHttpStatusException extends RuntimeException {

    final int httpStatus;
    final String detail;

    WahaHttpStatusException(int httpStatus, String detail) {
      super("WAHA HTTP " + httpStatus);
      this.httpStatus = httpStatus;
      this.detail = detail;
    }
  }
}
