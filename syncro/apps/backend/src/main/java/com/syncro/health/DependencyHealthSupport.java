package com.syncro.health;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Shared enrichment for Actuator health components so every dependency health result
 * carries the operational status contract fields: {@code statusLabel}, {@code statusSeverity},
 * {@code timestamp}, and — where available — {@code statusReason} and {@code traceId}.
 *
 * <p>Severity mapping per the architecture operational-status contract (UP → SUCCESS,
 * DOWN → CRITICAL, OUT_OF_SERVICE → WARNING, UNKNOWN → NEUTRAL). Kept pure and reusable
 * across all five dependency health indicators so labels are never hardcoded per indicator.
 */
public final class DependencyHealthSupport {

  private DependencyHealthSupport() {
  }

  /** Enriches a health result with the canonical AC 6 fields (no reason / trace id). */
  public static Health enrich(Health health, Clock clock) {
    return enrich(health, clock, null, null);
  }

  /**
   * Enriches a health result with the canonical AC 6 fields.
   *
   * @param health       the raw indicator result
   * @param clock        the application clock for the {@code timestamp}
   * @param statusReason optional human-readable reason (e.g. last error / circuit state)
   * @param traceId      optional trace id, only where applicable
   */
  public static Health enrich(Health health, Clock clock, String statusReason, String traceId) {
    Status status = health.getStatus();
    Health.Builder builder = new Health.Builder(status, health.getDetails());
    builder
        .withDetail("statusLabel", statusLabel(status))
        .withDetail("statusSeverity", statusSeverity(status))
        .withDetail("timestamp", Instant.now(clock).toString());
    if (statusReason != null) {
      builder.withDetail("statusReason", statusReason);
    }
    if (traceId != null) {
      builder.withDetail("traceId", traceId);
    }
    return builder.build();
  }

  /** Human-readable label for a health status (e.g. {@code Up}, {@code Down}). */
  public static String statusLabel(Status status) {
    return switch (status.getCode()) {
      case "UP" -> "Up";
      case "DOWN" -> "Down";
      case "OUT_OF_SERVICE" -> "Out of Service";
      case "UNKNOWN" -> "Unknown";
      default -> status.getCode();
    };
  }

  /** Canonical severity for a health status per the operational status contract. */
  public static String statusSeverity(Status status) {
    return switch (status.getCode()) {
      case "UP" -> "SUCCESS";
      case "DOWN" -> "CRITICAL";
      case "OUT_OF_SERVICE" -> "WARNING";
      case "UNKNOWN" -> "NEUTRAL";
      default -> "NEUTRAL";
    };
  }

  /**
   * Maps an exception to a stable, non-leaking reason code for public health payloads.
   *
   * <p>{@code /actuator/health} is unauthenticated ({@code permitAll}) with
   * {@code show-details: always}, so the raw {@code exception.getMessage()} is never emitted —
   * internal hostnames/URLs from JDBC/Redis/Influx clients must not reach that endpoint. The
   * full exception stays visible in the server logs. Classification is keyword-based on the
   * message and the exception class name; only the returned code is ever rendered.
   */
  public static String reasonCode(Exception exception) {
    String message = exception.getMessage();
    String text = ((message == null ? "" : message) + " " + exception.getClass().getSimpleName())
        .toUpperCase(Locale.ROOT);
    if (text.contains("TIMEOUT") || text.contains("TIMED OUT")) {
      return "TIMEOUT";
    }
    if (text.contains("NO ROUTE TO HOST") || text.contains("NETWORK UNREACHABLE")
        || text.contains("NOROUTETOHOSTEXCEPTION")) {
      return "NETWORK_UNREACHABLE";
    }
    if (text.contains("CONNECTION REFUSED") || text.contains("CONNECT REFUSED")
        || text.contains("COULD NOT CONNECT") || text.contains("UNABLE TO CONNECT")
        || text.contains("CONNECTEXCEPTION") || text.contains("CONNECTIONFAILURE")) {
      return "CONNECTION_REFUSED";
    }
    if (text.contains("UNKNOWN HOST") || text.contains("UNKNOWNHOSTEXCEPTION") || text.contains("DNS")) {
      return "DNS_FAILURE";
    }
    if (text.contains("UNAUTHORIZED") || text.contains("AUTHENTICATION")
        || text.contains("NOAUTH")) {
      return "UNAUTHORIZED";
    }
    if (text.contains("429") || text.contains("TOO MANY REQUESTS")) {
      return "RATE_LIMITED";
    }
    if (text.contains("INTERNAL SERVER ERROR") || text.contains("SERVICE UNAVAILABLE")
        || text.contains("BAD GATEWAY")) {
      return "SERVER_ERROR";
    }
    return "CONNECTION_FAILED";
  }
}
