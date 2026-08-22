---
title: 'Deferred-work bundle: EMQX MQTT auth, optimistic locking (installation + alert), Influx optional-field type coercion'
type: 'feature'
created: '2026-08-22'
status: 'done'
baseline_revision: 'e9a39b586190115db564cb8c65f4454861a23f5b'
final_revision: '66bb11d3b9b0b4de54a7aa54e3b5a1e8a19b9c8f'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: ['multiple-goals', 'oversized']
---

<intent-contract>

## Intent

**Problem:** Four deferred-work ledger items marked "Bundle it now" remain open: EMQX MQTT password auth is configured but the local backend default credential diverges from the broker bootstrap (DW-1); `MachineSparepartInstallationEntity` and `SparepartAlertEntity` have no optimistic locking so concurrent edits silently overwrite (DW-9, DW-40); `InfluxTelemetryWriter.addOptionalField` stores integral and floating JSON numbers as different Influx field types, so an optional field that alternates representation (e.g. `vibration:2` then `vibration:2.4`) triggers an InfluxDB field-type conflict that silently drops points (DW-28).

**Approach:** (DW-1) Align the `local` profile MQTT password default with `auth-bootstrap.csv` and prove auth enforcement with an EMQX Testcontainer test. (DW-9/DW-40) Add `@Version` columns via two Flyway migrations and map `ObjectOptimisticLockingFailureException` to 409 with stable codes; the alert-detail page's acknowledge `isPending` guard already exists and is verified, not re-implemented. (DW-28) Always coerce numeric optional fields to `Double` in `InfluxTelemetryWriter` and add unit + real-Influx tests proving int/float samples for one field name both persist.

## Boundaries & Constraints

**Always:**
- Use the `NotificationJobEntity` `@Version` pattern (`@Version @Column(name="version", nullable=false) private long version;`); do not set `version` in entity constructors.
- Migrations are forward-only: two new files `V32` (installations) and `V33` (alerts); do not edit applied migrations.
- Stable error codes: `INSTALLATION_CONCURRENT_MODIFICATION` (409) and `ALERT_CONCURRENT_MODIFICATION` (409) following existing `*_DATA_INTEGRITY_VIOLATION` conventions; error shape stays the existing `ErrorResponse(code, message, fieldErrors, timestamp, traceId)`.
- Follow the documented Influx contract change: numeric optional fields become Double. The InfluxDB v3 client method is `setField` (the DW-28 decision text says `addField` — use the actual API `point.setField(name, node.doubleValue())`).
- Keep `MqttProperties` binding unchanged; only change the `local` profile default in `application-local.yml`.

**Block If:**
- EMQX Testcontainer cannot be pulled/started in this environment → HALT with blocking condition `emqx testcontainer unavailable` (do not weaken the test to a mock).
- Any of the two new migrations conflicts with an existing/uncommitted V32/V33 elsewhere → HALT with blocking condition `migration number conflict`.

**Never:**
- Do not hardcode `EMQX_AUTH__MNESIA__PASSWORD_HASH` env in compose (built-in DB auth is already enforced via emqx.conf; introducing a second auth path risks breaking existing ACL seeding).
- Do not touch `application.yml` (non-profile) MQTT section — env-driven binding stays.
- Do not add Lombok/MapStruct; do not add a new testing framework; do not introduce EMQX auth to tests that only assert wiring.
- Do not alter `InfluxTelemetryWriter` boolean/text branches — only the numeric branch changes.
- Do not change frontend alert mutation logic beyond verifying the existing `isPending` guard; no new frontend files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DW-9/40 stale-version update | Two concurrent PUTs/acknowledge on same entity (second sees version from before first commit) | Second request returns 409, code `INSTALLATION_CONCURRENT_MODIFICATION` / `ALERT_CONCURRENT_MODIFICATION` | No retry; message names the conflict |
| DW-9 first save | New installation, version column default 0 | Persist succeeds; version becomes 0 | No error |
| DW-28 int-then-float same field | machine configured with `vibration`; sample1 `vibration:2`, sample2 `vibration:2.4` | Both Influx points persist; `vibration` field is Double in both | No field-type conflict, no dropped point |
| DW-28 float-then-int same field | sample1 `vibration:2.4`, sample2 `vibration:2` | Both persist as Double | No field-type conflict |
| DW-1 wrong MQTT password | Paho connect with valid user, wrong password | Connect fails; EMQX rejects with auth reason code | No retry; client stays disconnected |
| DW-1 valid credentials | Paho connect with `syncro_backend` + bootstrap CSV password | Connect succeeds | No error |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationEntity.java` -- add `@Version long version`
- `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java` -- add `@Version long version`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriter.java` -- numeric optional fields → Double (lines 49-59 `addOptionalField`)
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationExceptionHandler.java` -- 409 mapping for concurrent modification
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertExceptionHandler.java` -- 409 mapping for concurrent modification
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java` -- translate `ObjectOptimisticLockingFailureException` in `save()`/`delete()` (lines 105-170)
- `syncro/apps/backend/src/main/resources/db/migration/V32__add_installation_version.sql` -- new migration
- `syncro/apps/backend/src/main/resources/db/migration/V33__add_alert_version.sql` -- new migration
- `syncro/apps/backend/src/main/resources/application-local.yml` -- MQTT password default (line 31)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriterTest.java` -- update line-protocol assertion + new coercion test (lines 83-101)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceIntegrationTest.java` -- real-Influx int/float same-field test (uses existing containers)
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/api/MachineSparepartInstallationControllerTest.java` -- 409 concurrent-modification contract test
- `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java` -- optimistic-lock propagation test
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttAuthEnforcementIntegrationTest.java` -- NEW EMQX Testcontainer test
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx` -- verify `isPending` guard only (lines 40, 243-250)

## Tasks & Acceptance

**Execution:**
- [x] `MachineSparepartInstallationEntity.java` -- add `@Version @Column(name="version", nullable=false) private long version;` -- DW-9 optimistic locking, pattern from `NotificationJobEntity`
- [x] `SparepartAlertEntity.java` -- add `@Version @Column(name="version", nullable=false) private long version;` -- DW-40 optimistic locking
- [x] `V32__add_installation_version.sql` -- `ALTER TABLE machine_sparepart_installations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;` -- backfill existing rows at 0
- [x] `V33__add_alert_version.sql` -- `ALTER TABLE sparepart_alerts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;` -- backfill existing rows at 0
- [x] `MachineSparepartInstallationService.java` -- catch `ObjectOptimisticLockingFailureException` in `save()` and `delete()`, rethrow `InstallationConcurrentModificationException` -- translate JPA lock failure to domain exception; do not let it surface raw
- [x] `MachineSparepartInstallationExceptionHandler.java` -- map `InstallationConcurrentModificationException` to 409 CONFLICT code `INSTALLATION_CONCURRENT_MODIFICATION` -- stable machine-readable conflict code
- [x] `SparepartAlertExceptionHandler.java` -- map `ObjectOptimisticLockingFailureException` to 409 CONFLICT code `ALERT_CONCURRENT_MODIFICATION` -- acknowledge/resolve concurrent race
- [x] `InfluxTelemetryWriter.java` -- `addOptionalField`: replace integral/floating branches with `if (node.isNumber()) { point.setField(name, node.doubleValue()); }` -- DW-28 type-stability
- [x] `application-local.yml` -- set MQTT password default to `Syncro@Mqtt#2026!Dev` (matches `auth-bootstrap.csv`) -- local stack connects with auth enforced
- [x] `InfluxTelemetryWriterTest.java` -- update `optionalFieldsAreIncludedWithCorrectTypes` to expect `rpm=1200` (no `i`) and add int/float same-field-name line-protocol assertions -- lock the new storage contract
- [x] `TelemetryPersistenceIntegrationTest.java` -- add test seeding a machine with `optionalTelemetryFields`, persisting int then float for the same field, asserting both points queryable -- real Influx proves no field-type conflict
- [x] `MqttAuthEnforcementIntegrationTest.java` (NEW) -- Testcontainer `emqx/emqx:6.2.2` mounting repo `emqx.conf` + `auth-bootstrap.csv`; assert valid creds connect and wrong password is rejected -- DW-1 auth enforcement evidence
- [x] `MachineSparepartInstallationControllerTest.java` -- add test: service throws concurrent-modification exception → 409 + code -- API contract lock
- [x] `SparepartAlertCommandServiceTest.java` -- add test: `alertRepository.save` throwing `ObjectOptimisticLockingFailureException` propagates (unwrapped) -- service layer propagation

**Acceptance Criteria:**
- Given two concurrent updates to the same installation, when the second PUT flushes, then the API returns 409 with code `INSTALLATION_CONCURRENT_MODIFICATION` and the stable error shape.
- Given two concurrent acknowledge calls on the same alert, when the second save commits, then the API returns 409 with code `ALERT_CONCURRENT_MODIFICATION`.
- Given existing installations/alerts rows, when migrations V32/V33 apply from an empty DB and from the current schema, then both succeed and existing rows have `version=0`.
- Given a machine with optional field `vibration`, when an int sample and a float sample for `vibration` are persisted, then both InfluxDB points exist and `vibration` reads as a numeric (Double) value in both.
- Given `InfluxTelemetryWriter.toPoint` with an integral optional value, when serialized to line protocol, then the field has no `i` suffix (Double), and a float value serializes identically in type.
- Given a running EMQX with the repo auth config, when a Paho client connects with `syncro_backend` + the bootstrap password, then the connection succeeds; when the password is wrong, then the connection is rejected.
- Given the alert-detail page with an in-flight acknowledge mutation, when the user clicks Acknowledge, then the button is `disabled` (existing `isPending` guard — verify, do not rewrite).

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-22 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 6: (medium 1, low 5)
- defer: 5: (medium 1, low 4)
- reject: 11: (low 11)
- addressed_findings:
  - `[medium]` `[patch]` Frontend 409 with `ALERT_CONCURRENT_MODIFICATION` was misrouted to the invalid-state-transition toast; added `isConcurrentModification` code-branch (acknowledge/resolve/resolveOverride) that shows a reload-and-retry message and refetches.
  - `[low]` `[patch]` InfluxTelemetryWriterTest weak assertion `rpm=1200` (substring of `1200i`) tightened to `contains("rpm=1200.0")` + `doesNotContain("rpm=1200i")`.
  - `[low]` `[patch]` MqttAuthEnforcementIntegrationTest wrong-password assertion used generic `MqttException`; tightened to `MqttSecurityException` (specific auth rejection).
  - `[low]` `[patch]` EMQX test hardcoded a third copy of the MQTT password; now derived from `auth-bootstrap.csv` via `backendPasswordFromCsv()` (tie to CSV, prevents silent credential divergence).
  - `[low]` `[patch]` Static-init ordering bug in the EMQX test introduced by the credential-derivation fix (`AUTH_CSV` used before declaration); reordered fields.
  - `[low]` `[patch]` PilotSeedTest brace-style regression (`void migrationsThenSeedProduceCanonicalRows() {    Map...` on one line); reformatted.

## Design Notes

- The DW-28 decision text says `point.addField(...)` but the codebase's InfluxDB v3 client (`influxdb3-java:1.10.0`) exposes `setField` on `Point` — use `setField`. Downstream Flux reads are none in the repo (reads go through Redis latest state); the only Influx read is the test SQL in `TelemetryPersistenceIntegrationTest` which is type-agnostic (`instanceof Number`), so no query change is required.
- `ObjectOptimisticLockingFailureException` is the Spring wrapper JPA throws on `@Version` conflict at flush; it is not a `DataIntegrityViolationException`, so it must be caught explicitly in the installation service (which already catches the latter) and mapped in the alert exception handler (service uses repository `save` inside `@Transactional`, so the exception surfaces at flush inside the tx and propagates).
- Migration numbers confirmed: `V31` is the current max; `V32`/`V33` are free.
- `application-local.yml` default MQTT password currently `syncro_mqtt_dev` does not match `auth-bootstrap.csv` (`Syncro@Mqtt#2026!Dev`) or `.env`. Changing the local default to the CSV value makes a plain `local` profile boot connect with auth enforced; the `.env`/compose values are unchanged.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="InfluxTelemetryWriterTest,TelemetryPersistenceIntegrationTest"` -- expected: all pass (line protocol + real-Influx same-field test)
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MqttAuthEnforcementIntegrationTest"` -- expected: valid-cred connect succeeds, wrong-cred connect rejected (needs Docker; `docker version` confirmed 29.4.0)
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineSparepartInstallationControllerTest,SparepartAlertCommandServiceTest"` -- expected: 409 contract + propagation tests pass
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="*Flyway*"` or a migration test -- expected: V32/V33 apply from empty DB (verify migration evidence)
- `npx biome check` in `syncro/apps/web` -- expected: no new frontend files, only verification; alert-detail `isPending` guard already present

**Manual checks (if no CLI):**
- Inspect `alert-detail-page-content.tsx:243` for `disabled={isAcknowledging}` — confirm the DW-40 frontend guard already exists.
- Inspect `V32`/`V33` migration files for correct table/column names and `DEFAULT 0`.

## Auto Run Result

**Status:** done

**Summary:** Bundled four open deferred-work items (DW-1, DW-9, DW-28, DW-40): EMQX MQTT auth is proven enforced via a real EMQX 6.2.2 Testcontainer test and the local MQTT password default now matches `auth-bootstrap.csv`; optimistic locking (`@Version`) added to `MachineSparepartInstallationEntity` (V32) and `SparepartAlertEntity` (V33) with 409 `*_CONCURRENT_MODIFICATION` error codes; `InfluxTelemetryWriter` coerces all numeric optional fields to Double with unit + real-Influx int/float same-field tests.

**Files changed:**
- `syncro/apps/backend/src/main/resources/db/migration/V32__add_installation_version.sql` — version column for machine_sparepart_installations
- `syncro/apps/backend/src/main/resources/db/migration/V33__add_alert_version.sql` — version column for sparepart_alerts
- `MachineSparepartInstallationEntity.java` / `SparepartAlertEntity.java` — `@Version` fields
- `MachineSparepartInstallationService.java` — OOLFE → `InstallationConcurrentModificationException`
- `MachineSparepartInstallationExceptionHandler.java` — 409 `INSTALLATION_CONCURRENT_MODIFICATION`
- `SparepartAlertExceptionHandler.java` — 409 `ALERT_CONCURRENT_MODIFICATION`
- `InfluxTelemetryWriter.java` — numeric optional fields always Double
- `application-local.yml` — MQTT password default aligned with auth-bootstrap.csv
- `MqttAuthEnforcementIntegrationTest.java` (new) — EMQX auth-enforcement proof
- `InfluxTelemetryWriterTest.java`, `TelemetryPersistenceIntegrationTest.java`, `PilotSeedTest.java`, `MachineSparepartInstallationControllerTest.java`, `SparepartAlertCommandServiceTest.java`, `TelemetryPersistenceServiceTest.java` — tests
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx` — concurrent-modification 409 toast branch
- `pom.xml` — surefire JVM flags for JDK 25 (Arrow/Netty in influxdb3-java)
- `_bmad-output/implementation-artifacts/deferred-work.md` — added DW-114..117 deferrals

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 6 patches applied (frontend 409 code branch, writer test assertion tightened, EMQX test MqttSecurityException + credential derived from CSV, static-init order fix, PilotSeedTest formatting), 4 deferrals added (DW-114..117), 11 rejected as noise.

**Follow-up review recommendation:** false — patches were localized, low-consequence, and verified green.

**Verification performed:**
- `mvn test` targeted suites all PASS: `MqttAuthEnforcementIntegrationTest` (2/2, real EMQX), `InfluxTelemetryWriterTest` (6/6), `PilotSeedTest` (6/6, V32/V33 from empty DB), `MachineSparepartInstallationControllerTest` (26/26), `SparepartAlertCommandServiceTest` (24/24), `TelemetryPersistenceIntegrationTest` (9/9, real Influx), plus `TelemetryPersistenceServiceTest`, `DbIndexHygieneMigrationTest`, `MachineSparepartInstallationServiceIntegrationTest`, `SparepartAlertServiceTest`, `SparepartAlertQueryServiceTest`, `AuditLogWiringIntegrationTest`, `TelemetryValidationIntegrationTest`, `TelemetryValidationServiceTest`, and clean `mvn test-compile`.
- Biome on the changed frontend file reports 2 pre-existing errors + 1 warning (import order, class sorting) present at baseline — verified via `git stash`; the new code introduces no new diagnostics.

**Residual risks:**
- DW-114 (existing integer-typed Influx optional fields conflict on first Double write) requires a bucket rewrite/migration decision for non-dev environments.
- DW-115 (Double coercion loses precision above 2^53) — acceptable for 16-bit counting and typical sensor values; tracked.
- DW-116 (EMQX test heredoc entrypoint bypasses compose ACL seeding; config contents on command line) — tracked.
- Pre-existing test failures `SparepartLifetimeEvaluatorTest` (2) and `WahaRateLimiterTest` (8) are unrelated to this bundle (confirmed identical at baseline via `git stash`).

**Commits:** `5689ece` (feature) + `6c14a83` (final_revision). Working tree clean, branch `main`.
