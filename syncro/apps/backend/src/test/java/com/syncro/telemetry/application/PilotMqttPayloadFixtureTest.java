package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Hermetic verification of the cross-stack pilot MQTT payload fixtures committed at
 * {@code syncro/tests/fixtures/} (architecture.md "Seed, Fixtures, and Tests" — that directory is
 * the cross-stack E2E fixture home, and Story 7-3's publish scripts send these exact file bodies
 * verbatim to EMQX topic {@code factory/GM1/BF-08410/telemetry}).
 *
 * <p>The test reads the ACTUAL files (single source of truth — never an embedded copy) and proves,
 * using only production code ({@link TelemetryPayload#parse}, {@link TelemetryTopic#parse},
 * {@link CountingDeltaCalculator#delta} plus the percentage formula of
 * {@code SparepartLifetimeEvaluator}), that both fixtures satisfy the schema 1.0 wire contract,
 * carry the canonical GM1/BF-08410 identity, and sit on the correct side of the 90% sparepart
 * lifetime alert boundary pinned by the {@code pilot-seed.sql} header:
 * {@code counting 890 -> 89.00% < 90 -> no alert (Story 7-4)},
 * {@code counting 900 -> 90.00% >= 90 -> alert (Story 7-5)}. No Spring context, no containers.
 */
class PilotMqttPayloadFixtureTest {

  /** Canonical pilot topic from the 7-1 seed and PRD §12; 4 segments per {@link TelemetryTopic}. */
  private static final String CANONICAL_TOPIC = "factory/GM1/BF-08410/telemetry";

  /** pilot-seed.sql installation row: baseline_counter = 0. */
  private static final long BASELINE_COUNTER = 0L;

  /** pilot-seed.sql installation row: expected_production_count = 1000. */
  private static final long EXPECTED_PRODUCTION_COUNT = 1000L;

  /**
   * pilot-seed.sql installation row: threshold_percentage = 90 — the boundary
   * {@code SparepartAlertService} fires at ({@code consumedPercentage.compareTo(threshold) < 0}
   * skips, i.e. it fires at {@code >=}).
   */
  private static final int THRESHOLD_PERCENTAGE = 90;

  private static final String BEFORE_THRESHOLD_FILE = "mqtt-jbf19-before-threshold-payload.json";
  private static final String THRESHOLD_FILE = "mqtt-jbf19-threshold-payload.json";

  /**
   * The exact field set both fixtures must carry — nothing more, nothing less. Base telemetry plus
   * the mandatory contract fields (schemaVersion, messageId) and the optional identity pair
   * (plantCode, machineCode) that {@code TelemetryValidationService.checkIdentity} matches
   * case-insensitively against the topic.
   */
  private static final Set<String> CANONICAL_FIELD_SET =
      Set.of("schemaVersion", "messageId", "timestamp", "running", "runtimeHours", "counting", "plantCode", "machineCode");

  /** Mirrors the private TelemetryPayload.MAX_MESSAGE_ID_LENGTH contract limit. */
  private static final int MAX_MESSAGE_ID_LENGTH = 255;

  /** System-property override for IDE runs: -Dsyncro.pilot.fixtures.dir=&lt;dir&gt;. */
  private static final String FIXTURES_DIR_PROPERTY = "syncro.pilot.fixtures.dir";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void beforeThresholdFixtureIsAcceptedWirePayloadBelowAlertBoundary() throws IOException {
    String json = readFixture(BEFORE_THRESHOLD_FILE);

    var result = TelemetryPayload.parse(json, objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var payload = ((TelemetryPayload.ParseResult.Accepted) result).payload();
    assertThat(payload.running()).isTrue();
    assertThat(payload.runtimeHours()).isFinite().isGreaterThanOrEqualTo(0.0);
    assertThat(payload.counting()).isEqualTo(890L);
    assertThat(payload.schemaVersion()).isEqualTo(TelemetryPayload.SUPPORTED_SCHEMA_VERSION);
    assertIdentityMatchesCanonicalTopic(json);
    assertBoundaryMath(payload.counting(), 890L, "89.00", /* belowThreshold= */ true);
  }

  @Test
  void thresholdFixtureIsAcceptedWirePayloadReachingAlertBoundary() throws IOException {
    String json = readFixture(THRESHOLD_FILE);

    var result = TelemetryPayload.parse(json, objectMapper);

    assertThat(result).isInstanceOf(TelemetryPayload.ParseResult.Accepted.class);
    var payload = ((TelemetryPayload.ParseResult.Accepted) result).payload();
    assertThat(payload.running()).isTrue();
    assertThat(payload.runtimeHours()).isFinite().isGreaterThanOrEqualTo(0.0);
    assertThat(payload.counting()).isEqualTo(900L);
    assertThat(payload.schemaVersion()).isEqualTo(TelemetryPayload.SUPPORTED_SCHEMA_VERSION);
    assertIdentityMatchesCanonicalTopic(json);
    assertBoundaryMath(payload.counting(), 900L, "90.00", /* belowThreshold= */ false);
  }

  @Test
  void fixturesContainExactlyTheCanonicalFieldSet() throws IOException {
    String beforeThresholdRaw = readFixture(BEFORE_THRESHOLD_FILE);
    String thresholdRaw = readFixture(THRESHOLD_FILE);

    JsonNode beforeThresholdRoot = objectMapper.readTree(beforeThresholdRaw);
    JsonNode thresholdRoot = objectMapper.readTree(thresholdRaw);
    assertThat(beforeThresholdRoot.isObject()).as("%s root must be a JSON object", BEFORE_THRESHOLD_FILE).isTrue();
    assertThat(thresholdRoot.isObject()).as("%s root must be a JSON object", THRESHOLD_FILE).isTrue();

    assertThat(fieldNames(beforeThresholdRoot)).isEqualTo(new TreeSet<>(CANONICAL_FIELD_SET));
    assertThat(fieldNames(thresholdRoot)).isEqualTo(new TreeSet<>(CANONICAL_FIELD_SET));

    String beforeThresholdMessageId = beforeThresholdRoot.get("messageId").asText();
    String thresholdMessageId = thresholdRoot.get("messageId").asText();
    for (String messageId : List.of(beforeThresholdMessageId, thresholdMessageId)) {
      assertThat(messageId).isNotBlank();
      assertThat(messageId.length()).isLessThanOrEqualTo(MAX_MESSAGE_ID_LENGTH);
    }
    // Distinct so the before-threshold and threshold publishes never collide on one Redis dedupe key.
    assertThat(beforeThresholdMessageId).isNotEqualTo(thresholdMessageId);

    // Timestamps must be valid ISO-8601 UTC instants as committed (no placeholder syntax).
    assertThat(Instant.parse(beforeThresholdRoot.get("timestamp").asText()))
        .isEqualTo(Instant.parse("2026-08-22T00:00:00Z"));
    assertThat(Instant.parse(thresholdRoot.get("timestamp").asText()))
        .isEqualTo(Instant.parse("2026-08-22T00:01:00Z"));

    // Files stay stable, parseable, BOM-less UTF-8 with a trailing newline.
    for (String raw : List.of(beforeThresholdRaw, thresholdRaw)) {
      assertThat(raw).endsWith("\n");
      assertThat(raw.charAt(0)).isNotEqualTo('\uFEFF');
    }
  }

  /**
   * Payload identity fields vs the canonical topic, mirroring
   * {@code TelemetryValidationService.checkIdentity} (case-insensitive {@code equalsIgnoreCase},
   * validated only when present).
   */
  private void assertIdentityMatchesCanonicalTopic(String json) throws IOException {
    Optional<TelemetryTopic> topic = TelemetryTopic.parse(CANONICAL_TOPIC);
    assertThat(topic).as("canonical topic %s must parse", CANONICAL_TOPIC).isPresent();
    assertThat(topic.orElseThrow().plantCode()).isEqualTo("GM1");
    assertThat(topic.orElseThrow().machineCode()).isEqualTo("BF-08410");

    JsonNode root = objectMapper.readTree(json);
    assertThat(topic.orElseThrow().plantCode().equalsIgnoreCase(root.get("plantCode").asText())).isTrue();
    assertThat(topic.orElseThrow().machineCode().equalsIgnoreCase(root.get("machineCode").asText())).isTrue();
  }

  /**
   * Recomputes the exact evaluator chain so the fixtures can never drift across the alert boundary:
   * {@code CountingDeltaCalculator.delta(baseline, counting)} then the
   * {@code SparepartLifetimeEvaluator} percentage (HALF_UP, 2 decimals) then the
   * {@code SparepartAlertService} comparator semantics (skip when {@code compareTo < 0}).
   */
  private void assertBoundaryMath(long counting, long expectedConsumed, String expectedPercentage,
      boolean belowThreshold) {
    long consumed = CountingDeltaCalculator.delta(BASELINE_COUNTER, counting);
    assertThat(consumed).isEqualTo(expectedConsumed);

    BigDecimal consumedPercentage = BigDecimal.valueOf(consumed)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(EXPECTED_PRODUCTION_COUNT), 2, RoundingMode.HALF_UP);
    assertThat(consumedPercentage.toPlainString()).isEqualTo(expectedPercentage);

    int comparison = consumedPercentage.compareTo(BigDecimal.valueOf(THRESHOLD_PERCENTAGE));
    if (belowThreshold) {
      assertThat(comparison).as("%s must stay below the alert threshold", expectedPercentage).isNegative();
    } else {
      assertThat(comparison).as("%s must reach the alert boundary SparepartAlertService fires at", expectedPercentage)
          .isNotNegative();
    }
  }

  private static TreeSet<String> fieldNames(JsonNode root) {
    TreeSet<String> names = new TreeSet<>();
    root.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static String readFixture(String fileName) {
    try {
      return Files.readString(resolveFixturesDirectory().resolve(fileName), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException("Cannot read pilot fixture " + fileName, exception);
    }
  }

  /**
   * Repo-relative fixture resolution (single source of truth under {@code syncro/tests/fixtures/}):
   * system-property override first, then {@code ../../tests/fixtures} relative to the Maven surefire
   * working directory (module basedir {@code syncro/apps/backend}), then {@code tests/fixtures} for
   * a repo-root working directory ({@code syncro/}).
   */
  private static Path resolveFixturesDirectory() {
    List<Path> candidates = new ArrayList<>();
    String override = System.getProperty(FIXTURES_DIR_PROPERTY);
    if (override != null && !override.isBlank()) {
      candidates.add(Path.of(override));
    }
    candidates.add(Path.of("..", "..", "tests", "fixtures"));
    candidates.add(Path.of("tests", "fixtures"));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate.resolve(BEFORE_THRESHOLD_FILE))
          && Files.isRegularFile(candidate.resolve(THRESHOLD_FILE))) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Cannot locate the pilot MQTT payload fixtures (" + BEFORE_THRESHOLD_FILE + ", " + THRESHOLD_FILE
            + "); tried directories: " + candidates + ". Use -D" + FIXTURES_DIR_PROPERTY
            + "=<dir> to point at syncro/tests/fixtures explicitly.");
  }
}
