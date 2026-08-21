package com.syncro.telemetry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record TelemetryPayload(boolean running, double runtimeHours, long counting, String schemaVersion,
    String messageId, Instant timestamp, Map<String, JsonNode> optionalFields) {

  public static final String SUPPORTED_SCHEMA_VERSION = "1.0";

  /**
   * Rejection reason for implausible field values (negative runtimeHours/counting). Shared
   * constant: the data-quality tracker classifies quarantined messages with this reason as
   * anomalies, so the two must not drift apart as independent literals.
   */
  public static final String REASON_OUT_OF_RANGE = "out_of_range";

  private static final int MAX_MESSAGE_ID_LENGTH = 255;
  private static final int MAX_OPTIONAL_STRING_VALUE_LENGTH = 4096;
  private static final Set<String> BASE_FIELD_NAMES = Set.of(
      "running", "runtimeHours", "counting", "countingDelta", "plantCode", "machineCode", "traceId", "receivedAt",
      "schemaVersion", "messageId", "timestamp");

  public TelemetryPayload(boolean running, double runtimeHours, long counting, String schemaVersion, String messageId,
      Instant timestamp) {
    this(running, runtimeHours, counting, schemaVersion, messageId, timestamp, Map.of());
  }

  public sealed interface ParseResult permits ParseResult.Accepted, ParseResult.Rejected {
    record Accepted(TelemetryPayload payload) implements ParseResult {
    }

    record Rejected(String reason, String field) implements ParseResult {
    }
  }

  public static ParseResult parse(String json, ObjectMapper objectMapper) {
    return parse(json, objectMapper, Set.of());
  }

  public static ParseResult parse(String json, ObjectMapper objectMapper, Set<String> configuredOptionalFields) {
    JsonNode root;
    try (var parser = objectMapper.createParser(json)) {
      root = objectMapper.readTree(parser);
      if (parser.nextToken() != null) {
        return new ParseResult.Rejected("unparseable_payload", null);
      }
    } catch (Exception exception) {
      return new ParseResult.Rejected("unparseable_payload", null);
    }
    if (root == null || !root.isObject()) {
      return new ParseResult.Rejected("unparseable_payload", null);
    }
    JsonNode schemaVersionNode = root.get("schemaVersion");
    if (schemaVersionNode == null || schemaVersionNode.isNull()) {
      return new ParseResult.Rejected("missing_contract_field", "schemaVersion");
    }
    if (!schemaVersionNode.isTextual() || schemaVersionNode.asText().isBlank()) {
      return new ParseResult.Rejected("invalid_field_type", "schemaVersion");
    }
    if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersionNode.asText())) {
      return new ParseResult.Rejected("unsupported_schema_version", "schemaVersion");
    }
    JsonNode messageIdNode = root.get("messageId");
    if (messageIdNode == null || messageIdNode.isNull()) {
      return new ParseResult.Rejected("missing_contract_field", "messageId");
    }
    if (!messageIdNode.isTextual()) {
      return new ParseResult.Rejected("invalid_field_type", "messageId");
    }
    String messageId = messageIdNode.asText().trim();
    if (messageId.isBlank() || messageId.length() > MAX_MESSAGE_ID_LENGTH
        || messageId.chars().anyMatch(character -> character < 0x20)) {
      return new ParseResult.Rejected("invalid_field_type", "messageId");
    }
    JsonNode timestampNode = root.get("timestamp");
    if (timestampNode == null || timestampNode.isNull()) {
      return new ParseResult.Rejected("missing_contract_field", "timestamp");
    }
    if (!timestampNode.isTextual()) {
      return new ParseResult.Rejected("invalid_timestamp", "timestamp");
    }
    Instant timestamp;
    try {
      timestamp = Instant.parse(timestampNode.asText());
    } catch (DateTimeParseException exception) {
      return new ParseResult.Rejected("invalid_timestamp", "timestamp");
    }
    JsonNode runningNode = root.get("running");
    if (runningNode == null || runningNode.isNull()) {
      return new ParseResult.Rejected("missing_base_field", "running");
    }
    if (!runningNode.isBoolean()) {
      return new ParseResult.Rejected("invalid_field_type", "running");
    }
    JsonNode runtimeNode = root.get("runtimeHours");
    if (runtimeNode == null || runtimeNode.isNull()) {
      return new ParseResult.Rejected("missing_base_field", "runtimeHours");
    }
    if (!runtimeNode.isNumber() || !Double.isFinite(runtimeNode.doubleValue())) {
      return new ParseResult.Rejected("invalid_field_type", "runtimeHours");
    }
    if (runtimeNode.doubleValue() < 0) {
      return new ParseResult.Rejected(REASON_OUT_OF_RANGE, "runtimeHours");
    }
    JsonNode countingNode = root.get("counting");
    if (countingNode == null || countingNode.isNull()) {
      return new ParseResult.Rejected("missing_base_field", "counting");
    }
    boolean integralCounting = countingNode.isIntegralNumber()
        || (countingNode.isFloatingPointNumber() && countingNode.doubleValue() == Math.floor(countingNode.doubleValue()));
    if (!integralCounting || !countingNode.canConvertToLong()) {
      return new ParseResult.Rejected("invalid_field_type", "counting");
    }
    if (countingNode.longValue() < 0) {
      return new ParseResult.Rejected(REASON_OUT_OF_RANGE, "counting");
    }
    Map<String, JsonNode> optionalFields = Map.of();
    if (configuredOptionalFields == null) {
      configuredOptionalFields = Set.of();
    }
    if (!configuredOptionalFields.isEmpty()) {
      var collected = new LinkedHashMap<String, JsonNode>();
      var fieldNames = root.fieldNames();
      while (fieldNames.hasNext()) {
        String name = fieldNames.next();
        if (!configuredOptionalFields.contains(name) || BASE_FIELD_NAMES.contains(name) || name.startsWith("_")) {
          continue;
        }
        JsonNode node = root.get(name);
        if (node.isNull()) {
          return new ParseResult.Rejected("invalid_field_type", name);
        }
        if (!isAcceptedScalar(node)) {
          return new ParseResult.Rejected("invalid_field_type", name);
        }
        collected.put(name, node);
      }
      optionalFields = Collections.unmodifiableMap(collected);
    }
    return new ParseResult.Accepted(
        new TelemetryPayload(runningNode.booleanValue(), runtimeNode.doubleValue(), countingNode.longValue(),
            schemaVersionNode.asText(), messageId, timestamp, optionalFields));
  }

  private static boolean isAcceptedScalar(JsonNode node) {
    if (node.isNumber()) {
      if (node.isIntegralNumber() && !node.canConvertToLong()) {
        return false;
      }
      return Double.isFinite(node.doubleValue());
    }
    if (node.isTextual()) {
      return node.asText().length() <= MAX_OPTIONAL_STRING_VALUE_LENGTH;
    }
    return node.isBoolean();
  }
}
