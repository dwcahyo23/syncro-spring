package com.syncro.telemetry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record TelemetryPayload(boolean running, double runtimeHours, long counting) {

  public sealed interface ParseResult permits ParseResult.Accepted, ParseResult.Rejected {
    record Accepted(TelemetryPayload payload) implements ParseResult {
    }

    record Rejected(String reason, String field) implements ParseResult {
    }
  }

  public static ParseResult parse(String json, ObjectMapper objectMapper) {
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
    return new ParseResult.Accepted(
        new TelemetryPayload(runningNode.booleanValue(), runtimeNode.doubleValue(), countingNode.longValue()));
  }
}