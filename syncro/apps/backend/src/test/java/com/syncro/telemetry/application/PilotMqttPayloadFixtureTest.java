package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.syncro.sparepart.domain.SparepartDerivation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

  /**
   * Stricter mapper for the fixture-integrity assertions only: rejects duplicate JSON keys
   * (readTree silently collapses them last-wins, which would mask a drifted fixture).
   */
  private final ObjectMapper assertionsMapper = new ObjectMapper()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

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

    JsonNode beforeThresholdRoot = assertionsMapper.readTree(beforeThresholdRaw);
    JsonNode thresholdRoot = assertionsMapper.readTree(thresholdRaw);
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

    // Files stay stable, parseable, BOM-less UTF-8, LF-only, with a trailing newline.
    // No-CR matters beyond cosmetics: story 7-3 publishes these bytes VERBATIM, and a
    // CRLF checkout would change the wire bytes per machine (syncro/tests/fixtures/
    // .gitattributes pins eol=lf so this can never regress silently).
    for (String raw : List.of(beforeThresholdRaw, thresholdRaw)) {
      assertThat(raw).endsWith("\n");
      assertThat(raw).doesNotContain("\r");
      assertThat(raw.charAt(0)).isNotEqualTo('\uFEFF');
    }
  }

  /**
   * Payload identity fields vs the canonical topic, mirroring the production
   * {@code TelemetryValidationService.checkIdentity} match (case-insensitive). Unlike
   * production — which skips validation when the identity fields are absent/null — these
   * fixtures are REQUIRED to carry them, so absence is a failure, not a skip.
   */
  private void assertIdentityMatchesCanonicalTopic(String json) throws IOException {
    Optional<TelemetryTopic> parsedTopic = TelemetryTopic.parse(CANONICAL_TOPIC);
    assertThat(parsedTopic).as("canonical topic %s must parse", CANONICAL_TOPIC).isPresent();
    TelemetryTopic topic = parsedTopic.orElseThrow();
    assertThat(topic.plantCode()).isEqualTo("GM1");
    assertThat(topic.machineCode()).isEqualTo("BF-08410");

    JsonNode root = assertionsMapper.readTree(json);
    JsonNode plantCode = root.get("plantCode");
    JsonNode machineCode = root.get("machineCode");
    assertThat(plantCode).as("plantCode must be present").isNotNull();
    assertThat(plantCode.isTextual()).as("plantCode must be textual").isTrue();
    assertThat(machineCode).as("machineCode must be present").isNotNull();
    assertThat(machineCode.isTextual()).as("machineCode must be textual").isTrue();
    assertThat(topic.plantCode().equalsIgnoreCase(plantCode.asText())).isTrue();
    assertThat(topic.machineCode().equalsIgnoreCase(machineCode.asText())).isTrue();
  }

  /**
   * Recomputes the exact evaluator chain so the fixtures can never drift across the alert boundary:
   * {@code CountingDeltaCalculator.delta(baseline, counting)} then the
   * {@code SparepartLifetimeEvaluator} percentage (HALF_UP, 2 decimals) then the
   * {@code SparepartAlertService} comparator semantics (skip when {@code compareTo < 0}, i.e.
   * fires at {@code >=} — SparepartAlertService.java line 83). The delta call IS production
   * code; the percentage and comparator are a verified mirror because
   * {@code SparepartLifetimeEvaluator} requires a repository and cannot run hermetically
   * (extracting a pure function is deferred as DW-74).
   */
  private void assertBoundaryMath(long counting, long expectedConsumed, String expectedPercentage,
      boolean belowThreshold) {
    long consumed = CountingDeltaCalculator.delta(BASELINE_COUNTER, counting);
    assertThat(consumed).isEqualTo(expectedConsumed);

    // DW-74: run the production percentage formula via the extracted pure function instead
    // of the inline mirror, so evaluator drift (rounding/comparator direction) fails here.
    BigDecimal consumedPercentage =
        SparepartDerivation.consumedPercentage(consumed, EXPECTED_PRODUCTION_COUNT);
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
   * the system-property override is AUTHORITATIVE — when set but invalid it fails immediately
   * instead of silently testing the committed copies. Relative candidates cover the Maven surefire
   * working directory (module basedir {@code syncro/apps/backend}), a {@code syncro/} working
   * directory, and the git repo root.
   */
  private static Path resolveFixturesDirectory() {
    String override = System.getProperty(FIXTURES_DIR_PROPERTY);
    if (override != null && !override.isBlank()) {
      Path overrideDir = Path.of(override);
      if (!hasBothFixtures(overrideDir)) {
        throw new IllegalStateException(
            "System property " + FIXTURES_DIR_PROPERTY + " points at " + overrideDir.toAbsolutePath()
                + " which does not contain both pilot fixtures (" + BEFORE_THRESHOLD_FILE + ", "
                + THRESHOLD_FILE + "); refusing to fall back to other copies.");
      }
      return overrideDir;
    }

    List<Path> candidates = List.of(
        Path.of("..", "..", "tests", "fixtures"),
        Path.of("tests", "fixtures"),
        Path.of("syncro", "tests", "fixtures"));
    for (Path candidate : candidates) {
      if (hasBothFixtures(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Cannot locate the pilot MQTT payload fixtures (" + BEFORE_THRESHOLD_FILE + ", " + THRESHOLD_FILE
            + "); tried directories: " + candidates + ". Use -D" + FIXTURES_DIR_PROPERTY
            + "=<dir> to point at syncro/tests/fixtures explicitly.");
  }

  private static boolean hasBothFixtures(Path directory) {
    return Files.isRegularFile(directory.resolve(BEFORE_THRESHOLD_FILE))
        && Files.isRegularFile(directory.resolve(THRESHOLD_FILE));
  }
}
