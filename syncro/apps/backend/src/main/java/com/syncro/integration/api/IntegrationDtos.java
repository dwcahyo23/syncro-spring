package com.syncro.integration.api;

import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response records for the webhook config + delivery-log surface (story 22-1). */
public final class IntegrationDtos {

  private IntegrationDtos() {
  }

  /**
   * Create an OUTBOUND (or INBOUND-stub) webhook config. Event-type elements are bounded to
   * the V1 {@code event_type VARCHAR(100)} width (review 22-1 P3) so a long value answers 400
   * instead of a DB truncation 500; the secret has a 16-char floor (review 22-1 P16) — a short
   * secret is cryptographically weak and the "****"+last-4 mask would reveal most of it.
   */
  public record CreateWebhookConfigRequest(
      @NotBlank @Size(max = 200)
      @Schema(nullable = false, description = "Globally unique config name") String name,
      @NotNull
      @Schema(nullable = false, description = "INBOUND configs are stored but never dispatched (22-1 scope)") WebhookDirection direction,
      @Size(max = 50) @Valid
      @Schema(description = "Subscribed event types (CLOSED/DONE/... workorder events, ALERT_OPENED)") List<@NotBlank @Size(max = 100) String> eventTypes,
      @Size(max = 2048)
      @Schema(description = "Subscriber endpoint URL — required for OUTBOUND") String endpointUrl,
      @Size(min = 16, max = 512)
      @Schema(description = "HMAC-SHA256 signing secret — required for OUTBOUND; masked in every response") String hmacSecret,
      @Schema(description = "Dispatch toggle; defaults to true") Boolean active) {
  }

  /** Partial update: null fields keep their current value; a non-null secret rotates it. */
  public record UpdateWebhookConfigRequest(
      @Size(max = 50) @Valid List<@NotBlank @Size(max = 100) String> eventTypes,
      @Size(max = 2048) String endpointUrl,
      @Size(min = 16, max = 512) String hmacSecret,
      Boolean active) {
  }

  /** Config projection — hmacSecret is masked to its last 4 characters, never raw. */
  public record WebhookConfigView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false) String name,
      @Schema(nullable = false) WebhookDirection direction,
      @Schema(nullable = false) List<String> eventTypes,
      String endpointUrl,
      @Schema(description = "Masked secret (last 4 characters)") String hmacSecretMasked,
      @Schema(nullable = false) boolean active,
      @Schema(nullable = false) Instant createdAt,
      @Schema(nullable = false) Instant updatedAt,
      @Schema(nullable = false) long version) {
  }

  /** Delivery projection — response body is truncated server-side; no secret, no signature. */
  public record DeliveryView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false) UUID webhookConfigId,
      @Schema(nullable = false) String eventType,
      @Schema(nullable = false) WebhookDeliveryStatus status,
      Integer responseCode,
      String responseBody,
      Integer latencyMs,
      @Schema(nullable = false) int attemptCount,
      @Schema(nullable = false) int maxAttempts,
      Instant nextRetryAt,
      String traceId,
      String idempotencyKey,
      @Schema(nullable = false) Instant createdAt,
      @Schema(nullable = false) Instant updatedAt) {
  }

  /** House pagination shape (LoginAuditListResponse precedent). */
  public record DeliveryListResponse(
      List<DeliveryView> items,
      long totalElements,
      int totalPages,
      int page,
      int size,
      String sort) {
  }
}
