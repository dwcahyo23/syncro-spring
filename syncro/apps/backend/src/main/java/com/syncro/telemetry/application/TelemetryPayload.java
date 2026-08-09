package com.syncro.telemetry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record TelemetryPayload(boolean running, double runtimeHours, long counting, Map<String, JsonNode> optionalFields) {

  private static final int MAX_OPTIONAL_STRING_VALUE_LENGTH = 4096;
  private static final Set<String> BASE_FIELD_NAMES = Set.of(
      "running", "runtimeHours", "counting", "countingDelta", "plantCode", "machineCode", "traceId", "receivedAt");

  public TelemetryPayload(boolean running, double runtimeHours, long counting) {
    this(running, runtimeHours, counting, Map.of());
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
    try {
      root = objectMapper.readTree(json);
    } catch (Exception exception) {
      return new ParseResult.Rejected("unparseable_payload", null);
    }
    if (root == null || !root.isObject()) {
      return new ParseResult.Rejected("unparseable_payload", null);
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
      return new ParseResult.Rejected("out_of_range", "counting");
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
        new TelemetryPayload(runningNode.booleanValue(), runtimeNode.doubleValue(), countingNode.longValue(), optionalFields));
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
