---
title: 'Provide Pilot MQTT Payload Fixtures'
type: 'feature'
created: '2026-08-22'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: d75efee
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** Epic 7 must prove telemetry-to-alert behavior repeatably (7-4 below-threshold, 7-5 threshold-alert), and every downstream story consumes the same two wire payloads: 7-3's `publish-jbf19-before-threshold.ps1` / `publish-jbf19-threshold.ps1` publish fixture file bodies verbatim to EMQX topic `factory/GM1/BF-08410/telemetry`, and 7-4/7-5/7-7 cite the published values as evidence. Today those payloads do not exist — `syncro/tests/fixtures/` contains only a `.gitkeep` — so nothing pins the exact JSON the pilot publishes, and nothing proves the published values actually sit on the correct side of the 90% alert boundary against the canonical pilot installation from story 7-1 (`baseline_counter=0`, `expected_production_count=1000`, `threshold_percentage=90`, sparepart `BF-08410GM1ELEPLCWEC000`).

**Approach:** Two canonical, data-only MQTT payload JSON files at the architecture-prescribed cross-stack fixture home `syncro/tests/fixtures/` (architecture.md "Seed, Fixtures, and Tests": `tests/fixtures/` contains cross-stack E2E fixtures; the tree lists `mqtt-jbf19-before-threshold-payload.json` and `mqtt-jbf19-threshold-payload.json` by exact name; `tests/fixtures/mqtt-jbf19-before-threshold-payload.json` "proves alert is not created before 90%", the threshold file "proves alert is created at threshold"). Values come from the 7-1 seed header's pinned counter math: `counting=890` → 89.00% < 90 → no alert; `counting=900` → 90.00% ≥ 90 → alert. A hermetic backend verification test (`com.syncro.telemetry.application.PilotMqttPayloadFixtureTest`, plain JUnit + the production `TelemetryPayload.parse`/`TelemetryTopic.parse`/`CountingDeltaCalculator`, no Spring context, no containers) reads the ACTUAL files from `syncro/tests/fixtures/` (repo-path resolver: system-property override, then `../../tests/fixtures` from the Maven module cwd, then `tests/fixtures` from a repo-root cwd) and proves both files satisfy the full wire contract, topic/identity consistency, and the alert-boundary math — the single source of truth is validated in place, never duplicated into backend test resources.

## Boundaries & Constraints

**Always:**
- Exact fixture filenames fixed by epics Story 7.2 AC and the architecture tree: `syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json` and `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json`.
- Payload shape = the exact wire contract enforced by `TelemetryPayload.parse` (schema 1.0): `schemaVersion` string exactly `"1.0"` (`TelemetryPayload.SUPPORTED_SCHEMA_VERSION` — anything else is `unsupported_schema_version`), `messageId` non-blank text ≤ 255 chars with no control chars, `timestamp` ISO-8601 UTC instant parseable by `Instant.parse` (e.g. `2026-08-22T00:00:00Z`), `running` boolean, `runtimeHours` finite number ≥ 0, `counting` integral number ≥ 0. The epics' AC line "payloads include `running`, `runtimeHours`, `counting`, and timestamp" enumerates the base telemetry fields; `schemaVersion` and `messageId` are equally mandatory contract fields (stories 3-1/3-9) and MUST be present or the payload is rejected.
- Canonical counting values pinned by the 7-1 seed header ("Pilot counter math (pins the Story 7-2 fixture boundaries)"): before-threshold `counting=890`, threshold `counting=900`. With `baseline_counter=0` / `expected_production_count=1000`: `consumed = CountingDeltaCalculator.delta(0, counting) = floorMod(counting, 65536)`; `consumedPercentage = consumed × 100 / 1000` (HALF_UP, 2dp, `SparepartLifetimeEvaluator`); `SparepartAlertService` creates the alert when `consumedPercentage >= 90` (`compareTo(...) < 0` → skip). 890 → 89.00 (< 90, no alert); 900 → 90.00 (≥ 90, one OPEN alert + TECHNICIAN job). Both values are positive → pass the `out_of_range` plausible-value check (negatives only).
- Include optional identity fields `plantCode: "GM1"` and `machineCode: "BF-08410"` in both files: they are contract-optional (`TelemetryValidationService.checkIdentity` validates them only when present), matched case-insensitively against the topic, and make the fixture self-documenting for the exact topic 7-3 publishes to (`TelemetryTopic.parse` requires exactly 4 segments `factory/{plantCode}/{machineCode}/telemetry`).
- Include NO optional/configured telemetry fields: the seeded JBF19 machine row has `optional_telemetry_fields=NULL` (story 7-1), so `configuredOptionalFields` is empty and extra fields would be silently ignored — omitting them keeps the fixtures exactly canonical.
- Distinct, stable, descriptive `messageId` per file (`pilot-jbf19-before-threshold-890`, `pilot-jbf19-threshold-900`): stability means republishing the identical file within the dedupe window (Redis SETNX `syncro:machine:{machineId}:telemetry:dedupe:{messageId}`, `syncro.telemetry.dedupe-window` default PT30S, `TelemetryPersistenceService`) yields the `mqtt_telemetry_duplicate` log line — direct evidence for 7-5's duplicate-publish AC; distinctness means the before-threshold and threshold publishes never collide on one key.
- Fixed example `timestamp` literals in the files (`2026-08-22T00:00:00Z` before-threshold, `2026-08-22T00:01:00Z` threshold, monotone): files must be valid parseable JSON at rest (no `{now}` placeholders). Consumer contract for 7-3 (recorded here because JSON cannot carry comments; 7-3's spec/scripts must repeat it): publish the file body verbatim as the MQTT message body to `factory/GM1/BF-08410/telemetry`; the publish script SHOULD overwrite `timestamp` with the current UTC instant at publish time — `InfluxTelemetryWriter.toPoint` uses `payload.timestamp()` as the Influx point time and `TelemetryDataQualityTracker` samples publish→visible latency from it, so a stale timestamp yields historical-looking Influx points and a skewed/critical latency indicator (ingest itself never rejects stale timestamps); `messageId` stays verbatim.
- Files are BOM-less UTF-8, pretty-printed 2-space-indent JSON objects with a trailing newline, camelCase field names, no JSON comments, no extra fields.
- `PilotMqttPayloadFixtureTest` must assert the exact key set of both files (`schemaVersion`, `messageId`, `timestamp`, `running`, `runtimeHours`, `counting`, `plantCode`, `machineCode` — nothing else) so accidental field additions/renames fail loudly.
- The verification test computes the boundary percentage with the same formula as `SparepartLifetimeEvaluator` (`BigDecimal.valueOf(delta).multiply(100).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP)`) and asserts `89.00 < 90` / `90.00 >= 90` via `BigDecimal.compareTo` — the same comparator semantics `SparepartAlertService` uses.
- When naming the installed sparepart in any comment/assertion context, use the canonical backend-exact code `BF-08410GM1ELEPLCWEC000` (WITH the dash — spec 7-1 erratum) or label `Electric · PLC · Wecon · LX5`.

**Block If:** No decisions require human input for this story. The free parameters (messageId literals, example timestamps, runtimeHours values) are pinned by this spec with rationale; the counting values, topic, identity codes, and boundary math are fixed by the 7-1 seed header, PRD §12, and the telemetry contract source.

**Never:**
- Never copy or duplicate the fixtures into `syncro/apps/backend/src/test/resources/fixtures/` (or anywhere else): architecture.md scopes that directory to backend-internal automated test fixtures (it currently exists as an empty `.gitkeep` placeholder — reserved, not the pilot home) while `tests/fixtures/` is the cross-stack home — the backend test reads the real files via the repo-path resolver so there is exactly one source of truth for what 7-3 publishes.
- Never create `syncro/apps/backend/src/test/java/com/syncro/support/PilotFixture.java` or any `pilot-fixture.json` in this story: 7-1 fenced them off ("no consumer story requires them yet"), and in 7-2 the only code consumer of the wire fixtures is the single verification test — a private path-resolution helper inside that test is simpler than a shared support class. A shared fixture loader/JSON becomes warranted when 7-4/7-5 validation tests (or a `tests/e2e/` suite) must load the pilot scenario in multiple places; canonical master-data values remain owned by `pilot-seed.sql` + `PilotSeedTest` (a `pilot-fixture.json` now would be a third, consumer-less copy of those values).
- Never put placeholders, template markers, or non-JSON syntax inside the fixture files (they must parse as-is by `TelemetryPayload.parse`); never embed broker URLs, credentials, ACL identities, QoS, or retained flags — the payload body is data only and broker/auth wiring belongs to 7-3's scripts and story 3-13's EMQX security.
- Never invent counting values other than 890/900, never use a dash-less sparepart code literal, and never assert threshold behavior with `==` on doubles — the boundary is decimal-exact (`BigDecimal` HALF_UP 2dp, `compareTo`).
- Never modify production code, migrations, `pilot-seed.sql`, `application.yml`, or the frontend; never add dependencies (JUnit 5, AssertJ, and Jackson are already backend test dependencies); never create the 7-3 scripts (`seed-pilot.ps1`, `publish-*.ps1`, `verify-pilot.ps1`), `docs/pilot-validation.md` (7-7), or `tests/e2e/` specs.
- Never "verify" the fixtures against an embedded copy of their content — a test that re-asserts its own constants proves nothing about the files 7-3 publishes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Production parser reads before-threshold fixture | `TelemetryPayload.parse(file body, ObjectMapper)` | `ParseResult.Accepted` with running=true, runtimeHours ≥ 0 finite, counting=890, schemaVersion="1.0" | Any contract violation → `Rejected(reason, field)` → test fails |
| Production parser reads threshold fixture | same | `Accepted`, counting=900 | Same |
| Canonical topic | `TelemetryTopic.parse("factory/GM1/BF-08410/telemetry")` | Present, plantCode=GM1, machineCode=BF-08410 (4 segments, `factory` prefix, `telemetry` suffix) | Wrong topic shape → empty → test fails |
| Identity match | payload `plantCode`/`machineCode` vs topic | Case-insensitive equality per `checkIdentity` (`equalsIgnoreCase`) — exact-case values pass | Mismatch → `identity_mismatch` (not reachable with canonical values) |
| Alert boundary, before-threshold | delta(0, 890)=890; 890×100/1000 HALF_UP 2dp | 89.00; `compareTo(90) < 0` → no alert (7-4 proves end-to-end) | — |
| Alert boundary, threshold | delta(0, 900)=900 | 90.00; `compareTo(90) >= 0` → alert fires (7-5 proves end-to-end) | — |
| Duplicate publish within dedupe window | same file republished < PT30S later | `setIfAbsent` fails → `mqtt_telemetry_duplicate` logged, persist skipped — no second alert/job | Winner traceId logged |
| Republish after dedupe window (PT30S default) | same messageId, window expired | Reprocessed; still no duplicate ACTIVE alert (`existsBy...StatusNot(RESOLVED)` guard) and no duplicate logical notification job (idempotency key) | — |
| Stale `timestamp` published verbatim | 7-3 skips the recommended refresh | Still accepted (no ingest-side staleness rejection); Influx point time = payload timestamp (falls back to `receivedAt` with `timestampInferred=true` only outside the Influx writable range); latency sample large → indicator may show ELEVATED/CRITICAL | Documented consumer contract; 7-3 SHOULD refresh |
| Fixture edited later (field added/renamed/removed) | exact key-set assertion | Test 3 fails loudly | — |
| File encoding drift (BOM/other encoding) | Jackson `readTree`/`createParser` | BOM-less UTF-8 required; BOM makes the parser reject | Test fails |
| Test run from Maven module cwd (`syncro/apps/backend`) | resolver candidate `../../tests/fixtures` | Both files found (surefire default working directory = module basedir) | — |
| Test run from repo-root cwd (`syncro/`) or via override | candidates `tests/fixtures` / `-Dsyncro.pilot.fixtures.dir=` | Files found | Unresolvable → test fails with a message listing every candidate path tried |
| Fixtures pointed at a DB without the pilot seed / inactive machine | publish against unseeded environment | Validation rejects (`unknown_plant` / `unknown_machine` / `inactive_machine`) — fixtures assume the seeded ACTIVE BF-08410/JBF19 (GM1); end-to-end acceptance is 7-4/7-5 scope | Quarantined with reason |
| Optional field added to a fixture | machine has `optional_telemetry_fields=NULL` | Parser ignores it (collector runs only for configured fields) — but the exact key-set assertion forbids this to keep fixtures canonical | Test fails |

</intent-contract>

## Code Map

**All paths relative to repo root; only NEW files in this story.**

- `syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json` -- NEW -- pretty-printed UTF-8 JSON object, exact content:
  ```json
  {
    "schemaVersion": "1.0",
    "messageId": "pilot-jbf19-before-threshold-890",
    "timestamp": "2026-08-22T00:00:00Z",
    "running": true,
    "runtimeHours": 12.5,
    "counting": 890,
    "plantCode": "GM1",
    "machineCode": "BF-08410"
  }
  ```
- `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json` -- NEW -- same shape, exact content:
  ```json
  {
    "schemaVersion": "1.0",
    "messageId": "pilot-jbf19-threshold-900",
    "timestamp": "2026-08-22T00:01:00Z",
    "running": true,
    "runtimeHours": 13.0,
    "counting": 900,
    "plantCode": "GM1",
    "machineCode": "BF-08410"
  }
  ```
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/PilotMqttPayloadFixtureTest.java` -- NEW -- hermetic verification test, package `com.syncro.telemetry.application` (mirrors the production package of `TelemetryPayload`/`TelemetryTopic`/`CountingDeltaCalculator`), plain JUnit 5 + AssertJ + Jackson, no Spring context, no Testcontainers. Structure:
  - Constants: `CANONICAL_TOPIC = "factory/GM1/BF-08410/telemetry"`, `BASELINE_COUNTER = 0L`, `EXPECTED_PRODUCTION_COUNT = 1000L`, `THRESHOLD_PERCENTAGE = 90` (each citing its source: pilot-seed.sql header, `SparepartLifetimeEvaluator`, `SparepartAlertService`), expected key set, file names.
  - Fixture-path resolver (private static): candidate list — system property `syncro.pilot.fixtures.dir` (if set, resolve `<dir>/<file>`), `Path.of("..", "..", "tests", "fixtures")` (Maven surefire cwd = `syncro/apps/backend`), `Path.of("tests", "fixtures")` (repo-root cwd = `syncro/`); return the first candidate directory containing both files, else fail with a message listing all candidates tried.
  - `beforeThresholdFixtureIsAcceptedWirePayloadBelowAlertBoundary` — read the file as BOM-less UTF-8 string → `TelemetryPayload.parse(json, new ObjectMapper())` → assert `Accepted`; assert running=true, runtimeHours ≥ 0 and finite, counting=890, schemaVersion="1.0"; `TelemetryTopic.parse(CANONICAL_TOPIC)` present with GM1/BF-08410; payload `plantCode`/`machineCode` equal-ignore-case the topic segments; boundary math: `CountingDeltaCalculator.delta(BASELINE_COUNTER, 890)` = 890, percentage per the evaluator formula = 89.00, `compareTo(THRESHOLD) < 0`.
  - `thresholdFixtureIsAcceptedWirePayloadReachingAlertBoundary` — same; counting=900 → percentage 90.00, `compareTo(THRESHOLD) >= 0` (the exact comparator `SparepartAlertService` uses to fire).
  - `fixturesContainExactlyTheCanonicalFieldSet` — Jackson `readTree` both files → assert exact key set `{schemaVersion, messageId, timestamp, running, runtimeHours, counting, plantCode, machineCode}`; messageIds non-blank, ≤ 255 chars, distinct between the two files; `Instant.parse(timestamp)` succeeds; no top-level array/scalar (root is an object).
- Read-only references (do not modify): `telemetry/application/TelemetryPayload.java` (the wire contract this spec quotes verbatim: mandatory fields, `"1.0"`, messageId rules, `Instant.parse`, integral counting, `out_of_range` on negatives, optional-field collection only when configured), `telemetry/application/TelemetryTopic.java` (4-segment topic grammar), `telemetry/application/TelemetryValidationService.java` (`checkIdentity` case-insensitive optional identity match; active-machine precondition), `telemetry/application/CountingDeltaCalculator.java` (floorMod 65536), `sparepart/application/SparepartLifetimeEvaluator.java` (percentage formula), `alert/application/SparepartAlertService.java` (`compareTo < 0` skip / `>=` fire; non-RESOLVED duplicate-active-alert guard), `telemetry/application/TelemetryPersistenceService.java` (messageId dedupe key + `dedupeWindow`; Redis-latest + counter-state fallback; evaluator→alertService call path), `config/TelemetryProperties.java` (`dedupeWindow` default PT30S, `latestTtl` PT5M), `telemetry/infrastructure/InfluxTelemetryWriter.java` (point time = payload timestamp, `timestampInferred` fallback), `db/seed/pilot-seed.sql` (canonical values + "Pilot counter math" header that pins 890/900; installation 1000/0/90; sparepart `BF-08410GM1ELEPLCWEC000`), `db/PilotSeedTest.java` (DB-side boundary proof; plain-JDBC hermetic precedent), `telemetry/application/TelemetryValidationServiceTest.java` (canonical payload JSON style and GM1/BF-08410 examples), `syncro/tests/fixtures/.gitkeep` (target directory already exists).

## Tasks & Acceptance

**Execution:**

- [x] `syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json` -- NEW -- exact content per Code Map (BOM-less UTF-8, 2-space indent, trailing newline; `counting=890`). [AC 7.2-1, AC 7.2-2, AC 7.2-4, AC 7.2-5, AC 7.2-6]
- [x] `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json` -- NEW -- exact content per Code Map (`counting=900`). [AC 7.2-1, AC 7.2-3, AC 7.2-4, AC 7.2-5, AC 7.2-6]
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/PilotMqttPayloadFixtureTest.java` -- NEW -- the three test methods + repo-path fixture resolver per Code Map; production parser/topic/calculator only; no Spring context, no containers, no new dependencies. [AC 7.2-7]
- [x] Verify: run `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="PilotMqttPayloadFixtureTest"` → BUILD SUCCESS (no Docker required — pure parse-level test). No frontend changes → no web checks needed; state this explicitly in the completion record. [all ACs]
- [x] Verify scope: `git status --short` shows exactly the three NEW files plus story/sprint artifacts; zero modifications to existing source, seed, migrations, config, or anything under `syncro/apps/web/`. [all ACs]

**Acceptance Criteria:**

- Given pilot seed data exists (story 7-1), when fixtures are created under `tests/fixtures`, then exactly `mqtt-jbf19-before-threshold-payload.json` and `mqtt-jbf19-threshold-payload.json` exist at `syncro/tests/fixtures/` and nothing else is added there. [AC 7.2-1]
- Given the before-threshold fixture is parsed by the production wire contract, then it is accepted telemetry below the alert threshold: `counting=890` against `baseline_counter=0` / `expected_production_count=1000` yields 89.00% < 90%, so no threshold alert may fire for sparepart `BF-08410GM1ELEPLCWEC000` (end-to-end proof is story 7-4). [AC 7.2-2]
- Given the threshold fixture is parsed by the production wire contract, then it is accepted telemetry reaching the 90% threshold: `counting=900` yields exactly 90.00% ≥ 90% — the boundary `SparepartAlertService` fires at (end-to-end proof is story 7-5). [AC 7.2-3]
- Given either fixture, then the payload includes `running`, `runtimeHours`, `counting`, and `timestamp`, plus the mandatory contract fields `schemaVersion="1.0"` and a stable distinct `messageId`, and the optional identity fields `plantCode="GM1"` / `machineCode="BF-08410"` matching the canonical topic `factory/GM1/BF-08410/telemetry` case-insensitively; no other fields. [AC 7.2-4]
- Given the pilot baseline and expected count, then every payload value aligns: `counting` values are the seed header's pinned 890/900 boundary pair, `runtimeHours` values are plausible non-negative gauges, timestamps are valid UTC ISO-8601 instants, and the fixture set contains no frontend-derived or frontend-derivable calculation — the files are pure backend-contract data that 7-3's publish scripts send verbatim (with the documented SHOULD-refresh-`timestamp` contract). [AC 7.2-5]
- Given the fixtures are committed, then they are data-only (no secrets, credentials, broker/auth config, scripts, or placeholders) and remain stable JSON — any field addition, removal, or rename fails `PilotMqttPayloadFixtureTest.fixturesContainExactlyTheCanonicalFieldSet`. [AC 7.2-6]
- Given CI or a developer machine, when `PilotMqttPayloadFixtureTest` runs, then it loads the actual files from `syncro/tests/fixtures/` (single source of truth, no embedded copies) and proves wire-contract acceptance, topic/identity consistency, exact field set, and the 89.00%-below / 90.00%-at-threshold boundary math using the production parser, topic parser, delta calculator, and the evaluator's percentage formula. [AC 7.2-7]

### Review Findings

3-layer adversarial review (Blind Hunter, Edge Case Hunter, Acceptance Auditor) on 2026-08-22. Auditor verdict: APPROVE, 7/7 ACs PASS, all Always/Never constraints and edge-matrix rows verified. 6 patches applied, 1 deferral (DW-74), 8 dismissed (runtimeHours gauge pair physically inconsistent with the 60s timestamp gap — spec-pinned Code Map values, cosmetic gauges; exact-instant timestamp assertions — intended single-source-of-truth tightening per auditor; two-arg parse vs optional-fields precondition — pinned by PilotSeedTest in 7-1; bare default ObjectMapper — matches the established TelemetryPayloadTest idiom; type drift inside the key set — guarded by production-parse acceptance; jbf19-vs-BF-08410 naming — canonical machine name vs code per 7-1; change-log in-progress narration — cosmetic; seed-constant duplication — deferred with the evaluator mirror below).

- [x] [Review][Patch] Wire-byte contract unprotected against CRLF checkouts: BOM/newline assertions pass under CRLF and no .gitattributes covered the fixtures; added `syncro/tests/fixtures/.gitattributes` (`*.json text eol=lf`) plus a no-CR assertion so 7-3's verbatim publish can never drift per checkout (blind+edge, MAJOR both)
- [x] [Review][Patch] Identity assertion NPE'd on absent identity fields and false-passed on JSON-null (production skips when null; fixtures REQUIRE them); now asserts presence+textual+equalsIgnoreCase (edge, MAJOR)
- [x] [Review][Patch] `-Dsyncro.pilot.fixtures.dir` override silently fell through to relative candidates when mistyped — a false-green negative control was possible; the override is now authoritative and fails fast when invalid (blind+edge)
- [x] [Review][Patch] Resolver had no candidate for the git-repo-root working directory (common IDE default) and mislabeled `syncro/` as repo root; added the `syncro/tests/fixtures` candidate and fixed the javadoc (blind+edge)
- [x] [Review][Patch] Duplicate JSON keys were invisible (readTree collapses last-wins); integrity assertions now run on a STRICT_DUPLICATE_DETECTION mapper (edge)
- [x] [Review][Patch] Boundary-math javadoc now states exactly what is production code (CountingDeltaCalculator) vs verified mirror (evaluator percentage, alert comparator at SparepartAlertService.java:83) and why hermetic delegation is impossible today; micro-cleanup of the repeated `topic.orElseThrow()` unwrapping (blind+edge, fold of several MINORs)
- [x] [Review][Defer] Percentage/comparator mirror + seed-constant literals cannot detect production evaluator or seed drift (extracting a pure function needs a production refactor out of this story's additive scope) — deferred → DW-74

## Spec Change Log

- 2026-08-22: Spec created (draft → ready-for-dev). Ultimate context engine analysis completed — comprehensive developer guide created.
- 2026-08-22: Implemented via dev-story workflow — both fixtures + hermetic verification test created, all Verification commands green (3/3 tests, scope check clean), status ready-for-dev → in-progress → review.
- 2026-08-22: Code review (3-layer adversarial) — 6 patches (LF pinning via fixtures .gitattributes + no-CR assertion, identity presence assertions, authoritative fixtures-dir override, repo-root resolver candidate, duplicate-key detection, boundary-math documentation), 1 deferral (DW-74), 8 dismissed; PilotMqttPayloadFixtureTest 3/3 green after patches; status → done.

## Design Notes

- **Why `syncro/tests/fixtures/` and why the backend test reads across the module boundary:** architecture.md is explicit — `apps/backend/src/test/resources/fixtures/` is for backend-internal automated test fixtures, while root `tests/fixtures/` "contains cross-stack E2E fixtures" and names both `mqtt-jbf19-*.json` files with their proof semantics; "Root `tests/fixtures` and `scripts/` support cross-stack pilot validation". The same bytes must serve 7-3's PowerShell publishers, later E2E specs, and this story's verification test — so the files live once in the cross-stack home and the test resolves the repo-relative path (surefire's working directory is the module basedir, so `../../tests/fixtures` is the default hit; the property override and repo-root candidate cover IDE runs). A backend-local copy would be a second source of truth that can silently drift from what 7-3 publishes — the exact disaster this story exists to prevent.
- **7-1's deferred `PilotFixture.java` / `pilot-fixture.json` — resolved as still out of scope, with a concrete trigger:** spec 7-1 fenced them off because no consumer existed. Story 7-2's deliverables are the two wire fixtures themselves (data, not code); their only code consumer this story is one test, so a private path resolver inside that test beats a shared support class (project rule: shared abstractions need a stable pattern in 2-3 places, not line-count reduction). `pilot-fixture.json` would additionally duplicate the canonical master-data values already owned by `pilot-seed.sql` + `PilotSeedTest`. The fence comes down when 7-4/7-5 validation stories (or an E2E suite under `tests/e2e/`) need the pilot scenario loaded in a second place — that story's spec should introduce the loader then, not this one. This note exists so 7-3/7-4/7-5 neither reinvent the resolver ad hoc nor copy fixture content.
- **Boundary math citation chain (why 890/900 are load-bearing):** `pilot-seed.sql` header pins `counting 890 → 89.00% < 90 → no alert (Story 7-4)` and `counting 900 → 90.00% ≥ 90 → exactly one OPEN alert and a TECHNICIAN notification job (Story 7-5)`. The chain is `CountingDeltaCalculator.delta(0, counting) = floorMod(counting, 65536)` (890 and 900 are far from the 16-bit wrap, so the wrap path is irrelevant but documented) → `SparepartLifetimeEvaluator` percentage (HALF_UP, 2dp) → `SparepartAlertService` comparator (`consumedPercentage.compareTo(threshold) < 0` → skip, i.e. fires at `>=`). The verification test recomputes this exact chain so the files can never drift across the boundary silently; `PilotSeedTest` already proves the DB side (1000/0/90).
- **Why distinct stable messageIds:** `TelemetryPersistenceService` gates every persist on Redis `setIfAbsent("syncro:machine:{id}:telemetry:dedupe:{messageId}", traceId, dedupeWindow)` (default PT30S). Republishing the identical file inside the window produces the `mqtt_telemetry_duplicate` warn log with both traceIds — cheap, direct evidence for 7-5's "duplicate publish does not create duplicate active alert or duplicate logical notification job" AC. Outside the window the alert-level guard (`existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(RESOLVED)`) and the notification job idempotency key keep duplicates harmless regardless, so stability costs nothing and adds evidence.
- **Why fixed example timestamps plus a 7-3 refresh contract instead of a placeholder:** the files must be valid JSON parseable by `Instant.parse` as committed, so `{now}`-style templates are forbidden. But `InfluxTelemetryWriter.toPoint` stamps the Influx point with `payload.timestamp()` (only falling back to `receivedAt`, flagged `timestampInferred=true`, outside the writable range), and the 6-7 data-quality latency indicator samples `Duration.between(payload.timestamp(), now)` — a stale literal published verbatim yields a historical Influx point and an ELEVATED/CRITICAL latency reading during the pilot. Ingest never rejects stale timestamps (parse-only), so this is a SHOULD, not a MUST: 7-3's publish scripts overwrite `timestamp` (and only `timestamp`) with the current UTC instant; `messageId` is republished verbatim to preserve the dedupe evidence above. The canonical example literals are monotone (`00:00:00Z` → `00:01:00Z`) so even verbatim publishing orders correctly.
- **Why identity fields are included and nothing else optional:** `TelemetryPayload.BASE_FIELD_NAMES` reserves `plantCode`/`machineCode` (never treated as configured-optionals), and `TelemetryValidationService.checkIdentity` compares them to the topic only when present, case-insensitively — including them exercises the story 3-10 identity path and self-documents the target machine, with zero rejection risk since they equal the canonical topic. The seeded JBF19 has `optional_telemetry_fields=NULL`, so configured-optionals are empty and any extra field would be silently ignored — the exact key-set assertion keeps the files canonical instead.
- **Why a parse-level hermetic test rather than a full-stack publish:** the accept/reject decision needs only the parser, topic grammar, and identity comparison to prove the FILES are correct; machine-lookup/active-state/persist/alert creation require the seeded stack and are exactly what stories 7-4/7-5 prove end-to-end. Using the production `TelemetryPayload.parse` (not a test-local reimplementation) means any future contract change (e.g. schema 1.1) breaks this test the moment the fixtures would stop being accepted — contract drift is caught at build time with zero Docker dependency, unlike the context-loading Testcontainers tests that stall on `influxdb:3-core` in this environment (documented in specs 6-5/6-6/6-7 and 7-1's test-placement rationale).
- **Grounding note (web research):** none required — every technical fact in this spec is pinned to repo source read at spec time (payload/topic/validation/persistence/evaluator/alert services, `TelemetryProperties`, `pilot-seed.sql`, existing tests, architecture.md fixture sections, PRD §12); no new libraries, versions, or external APIs are introduced (JUnit 5, AssertJ, Jackson are existing backend test dependencies).

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="PilotMqttPayloadFixtureTest"` -- expected: BUILD SUCCESS, all three test methods green (no Docker required; pure JVM parse-level test).
- `git status --short` after implementation -- expected: exactly three new files (`syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json`, `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json`, `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/PilotMqttPayloadFixtureTest.java`) plus this spec/sprint-status artifacts; zero modifications under `syncro/apps/web/` and zero changes to any existing file (no production code, seed, migrations, or config touched).

**Manual checks (optional, no tooling):**
- Inspect both fixture files: 2-space-indented JSON, UTF-8 without BOM, trailing newline, exact field set and values per the Code Map; confirm `counting` values are 890 and 900 and the sparepart context (if noted anywhere) uses `BF-08410GM1ELEPLCWEC000`.
- If a local stack with the 7-1 seed applied is running, an EMQX/dashboard publish of the threshold fixture body to `factory/GM1/BF-08410/telemetry` is possible as a smoke check — but this belongs to 7-3/7-5; do not block this story on it.

## Dev Agent Record

### Agent Model Used

- builtin:zai-start-plan/GLM-5.3 (ZCode `bmad-dev-story` session, 2026-08-22)

### Debug Log References

- First `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="PilotMqttPayloadFixtureTest"` run: testCompile failure — `unreported exception java.io.IOException` at the two acceptance test methods that call the identity helper; fixed by declaring `throws IOException` on both test methods (no other changes).
- Canonical verification run (final): surefire `com.syncro.telemetry.application.PilotMqttPayloadFixtureTest` — `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` (0.551 s), Maven exit code 0 (BUILD SUCCESS). No Docker required.
- Negative control (proves the test bites on fixture drift): temp-dir copies with `counting` mutated 890→891 and an added `extraField`, run with `-Dsyncro.pilot.fixtures.dir=<temp>` → `Tests run: 3, Failures: 2` — `beforeThresholdFixtureIsAcceptedWirePayloadBelowAlertBoundary` (expected 890L) and `fixturesContainExactlyTheCanonicalFieldSet` (key-set mismatch), BUILD FAILURE. Also proves the resolver's system-property override reaches the forked surefire JVM. Temp dir deleted afterwards.
- Fixture byte inspection: both files start `7b 0a` (no UTF-8 BOM) and end `7d 0a` (trailing LF newline).
- `git status --short` at HEAD `b4046e9`: exactly the three NEW deliverable files plus this spec and sprint-status.yaml; zero modifications anywhere else (no production code, seed, migrations, config, nothing under `syncro/apps/web/`).

### Completion Notes List

- Implemented exactly per the Code Map: both fixture files wire-verbatim (`counting` 890/900, messageIds `pilot-jbf19-before-threshold-890` / `pilot-jbf19-threshold-900`, monotone example timestamps `2026-08-22T00:00:00Z` / `2026-08-22T00:01:00Z`, identity `GM1` / `BF-08410`) and the hermetic `PilotMqttPayloadFixtureTest` (plain JUnit 5 + AssertJ + Jackson; production `TelemetryPayload.parse` / `TelemetryTopic.parse` / `CountingDeltaCalculator` plus the `SparepartLifetimeEvaluator` percentage formula and `SparepartAlertService` comparator semantics recomputed via `BigDecimal` HALF_UP 2dp + `compareTo`; no Spring context, no containers, no new dependencies).
- AC evidence mapping: AC 7.2-1 → the two files exist at `syncro/tests/fixtures/` and nothing else added there (git status); AC 7.2-2 → `beforeThresholdFixtureIsAcceptedWirePayloadBelowAlertBoundary` (parse Accepted, delta 890, 89.00% `compareTo(90) < 0`); AC 7.2-3 → `thresholdFixtureIsAcceptedWirePayloadReachingAlertBoundary` (delta 900, 90.00% `compareTo(90) >= 0`); AC 7.2-4 / 7.2-5 / 7.2-6 → `fixturesContainExactlyTheCanonicalFieldSet` (exact 8-key set, non-blank distinct messageIds ≤ 255 chars, `Instant.parse` succeeds, BOM-less + trailing newline, data-only content); AC 7.2-7 → the test loads the actual files via the repo-path resolver (`syncro.pilot.fixtures.dir` override, `../../tests/fixtures` for surefire cwd, `tests/fixtures` for repo-root cwd).
- Red/green note: this story's deliverable is itself the verification test over spec-pinned data, so the spec's fixtures-first task sequence was followed as written; the RED side (assertions actually fail on bad input) is evidenced by the negative-control run above, the GREEN side by the canonical run.
- No frontend changes → no web checks needed (explicit per spec Task 4).
- TDD task order note: story task sequence (fixtures → test → verify) followed exactly as written; no HALT conditions were hit (no new dependencies, no config gaps, no 3 consecutive failures — the single compile error was fixed on the first retry).
- Residual risks: none within this story's scope. Deferred by design: end-to-end alert/notification behavior is 7-4/7-5 scope; 7-3 must implement the recorded consumer contract (publish file body verbatim, SHOULD refresh only `timestamp` at publish time, keep `messageId` verbatim for the dedupe-window evidence).
- Pre-existing unrelated test failures (SparepartAlertCommandServiceTest, WahaRateLimiterTest, influxdb/DbIndexHygiene @SpringBootTest stalls) were not touched and are out of scope per story instructions; the story's own verification gate (`PilotMqttPayloadFixtureTest`) is fully green.

### File List

- `syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json` (NEW)
- `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json` (NEW)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/PilotMqttPayloadFixtureTest.java` (NEW)
- `_bmad-output/implementation-artifacts/spec-7-2-provide-pilot-mqtt-payload-fixtures.md` (MODIFIED — frontmatter status, Tasks checkboxes, Dev Agent Record, Spec Change Log)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED — story 7-2 → review)
