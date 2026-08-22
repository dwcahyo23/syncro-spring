---
title: 'Validate Telemetry Before Threshold Does Not Create Alert'
type: 'validation'
created: '2026-08-22'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: 2906765
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** Epic 7's remaining stories (7-4 → 7-7) are runtime proof stories: the tooling is complete (7-1 canonical seed, 7-2 wire fixtures, 7-3 four PowerShell scripts, all committed and live-tested), but Phase 1's threshold logic has not yet been *proven* with a recorded, correlated evidence chain. Story 7-4 is the first proof: below-threshold JBF19 telemetry must be **accepted, persisted, and visible in the UI while creating NO alert** — the negative-space invariant that makes the 7-5 threshold proof meaningful. The 7-3 dev run already incidentally demonstrated `counting=890 → no alert` (its verify output line `no alert - correct before-threshold state (counting=890 -> 89.00% < 90%)`), but that evidence lives in the 7-3 story file, was captured backend-console-only (no log-line extraction, no traceId correlation, no UI proof), and 7-4's ACs explicitly demand the full chain: accepted telemetry log with traceId, technical proof via logs/pgAdmin/documented query, and UI proof from Machine Hub or the telemetry dashboard.

**Approach:** Pure validation story — **zero source-code changes**. Execute the pilot before-threshold flow end-to-end on the live local stack using exactly the 7-3 tooling, then record a correlated evidence chain in this story's Dev Agent Record: (1) preflight — infra stack up, pilot seed idempotently applied (`seed-pilot.ps1`), backend running with real `syncro/.env` values and console output captured to a file (the 7-3 temp-launcher pattern; `start-backend.ps1` loads `.env.example` placeholders and is NOT sufficient), health 200, web app running and login working; (2) `publish-jbf19-before-threshold.ps1` (timestamp refreshed, `messageId` `pilot-jbf19-before-threshold-890` verbatim, topic `factory/GM1/BF-08410/telemetry`); (3) extract the `mqtt_telemetry_accepted traceId=... topic=...` INFO line from the captured backend console; (4) `verify-pilot.ps1 -ExpectCounting 890` proving TELEMETRY PASS / ALERT PASS (no alert) / quarantine empty; (5) traceId correlation: log line traceId == Redis latest-hash `traceId` field; (6) documented SQL (pgAdmin-executable) for `machine_counter_states.counting=890` and zero `sparepart_alerts` rows; (7) UI proof within the PT5M Redis freshness window — telemetry dashboard `/dashboard/telemetry` BF-08410 card with latest telemetry and `ONLINE` freshness badge, and/or Machine Hub `/dashboard/master-data/machines/BF-08410`; (8) AC → evidence mapping with exact commands, timestamps, and observed values. The only committed artifacts are this story file and `sprint-status.yaml`.

## Boundaries & Constraints

**Always:**
- Execute the flow in this order, each step gated on the previous: (a) `docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml` stack up (postgres, redis, influxdb, emqx minimum); (b) `powershell -NoProfile -File syncro/scripts/seed-pilot.ps1` — idempotent, prints canonical-row summary incl. the `Electric · PLC · Wecon · LX5` tripwire, exit 0; (c) backend started with `syncro/.env` environment (temp launcher using the `start-backend.ps1` env-parsing idiom + `mvnw spring-boot:run`, console output redirected/tee'd to a log file so the ingest log lines are extractable afterwards), wait for `GET http://localhost:8080/api/v1/health` → 200 (~20s observed in 7-3); (d) web app started (either `npm --prefix syncro/apps/web run dev` on port 3000 or `syncro/scripts/start-web.ps1` production build on port 3001 — state which was used) and login verified; (e) publish; (f) verify; (g) UI proof.
- Backend MUST be running and subscribed BEFORE the publish: the MQTT subscription uses `cleanSession(true)` (3-1 config), so a message published while the backend is down is never delivered — no delayed state, and no evidence. Confirm subscription readiness by health 200 plus (if uncertain) the EMQX dashboard subscriptions view or simply the accepted-log line appearing after publish.
- The backend MUST run with real `syncro/.env` values, not `.env.example` placeholders: `start-backend.ps1` loads `.env.example` (CHANGE_ME placeholders → MQTT auth fails). Use the 7-3 dev-record pattern verbatim: load `syncro/.env` with the start-backend regex idiom into the process environment, then run `mvnw -f syncro/apps/backend/pom.xml spring-boot:run`, capturing stdout/stderr to a file (e.g. `*> backend-pilot.log` or `Start-Process -RedirectStandardOutput`), because the acceptance-evidence log line is INFO-level console output.
- Exact acceptance log marker (source: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java:62`): `mqtt_telemetry_accepted traceId=<uuid> topic=factory/GM1/BF-08410/telemetry` at INFO. The `traceId` in this line MUST be recorded and is the correlation key for the whole evidence chain.
- Redis latest-hash correlation (source: `TelemetryPersistenceService.persist`): hash key `syncro:machine:{machineId}:latest` contains fields `counting=890`, `countingDelta=0`, `receivedAt`, `traceId` — the hash `traceId` MUST equal the accepted-log-line `traceId` (same message, one ingest). Capture via `docker compose ... exec -T redis redis-cli --raw HGETALL syncro:machine:{machineId}:latest` (verify-pilot already prints this in its TELEMETRY section — recording its output is sufficient).
- Threshold math invariant (documented in the seed header, `pilot-seed.sql` lines 80-86): `consumed = floorMod(890 - 0, 65536) = 890`; `consumedPct = 890 × 100 / 1000 = 89.00%` (HALF_UP, 2 decimals, per `SparepartLifetimeEvaluator`); `89.00 < 90` → evaluator does not fire → zero alert rows. The negative-space proof is: `SELECT count(*) FROM sparepart_alerts` restricted to the JBF19 installation returns 0 (and verify-pilot's ALERT section prints `no alert - correct before-threshold state (counting=890 -> 89.00% < 90%)`).
- `verify-pilot.ps1` MUST be run with `-ExpectCounting 890` (the 7-3 review-hardened anti-false-green parameter): it FAILs if the counter row is missing or ≠ 890. Expected verdicts: PREFLIGHT PASS (EMQX/backend warn-only acceptable if just-restarted), SEED PASS, TELEMETRY PASS (counter 890, Redis hash fields, `telemetry_quarantine empty`), ALERT PASS (no alert), NOTIFICATION PASS (baseline), ACK INFO (baseline), `RESULT: PASS`, exit 0.
- Documented technical-proof queries (record verbatim in the Dev Agent Record so 7-7 can lift them; pgAdmin is the local/dev inspection tool, explicitly NOT a product feature): (1) `SELECT counting, updated_at FROM machine_counter_states mcs JOIN machines m ON m.id = mcs.machine_id JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410';` → `890 | <publish-time>`; (2) `SELECT count(*) FROM sparepart_alerts sa JOIN machines m ON m.id = sa.machine_id JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410';` → `0` (schema-verified: `sparepart_alerts.machine_id` exists and is indexed — V19; `machine_counter_states` columns — V20). Do not invent alternate column names; these are the physical names.
- UI proof MUST happen within 5 minutes of the publish: the Redis latest hash has a PT5M TTL and the telemetry dashboard's freshness badge shows `ONLINE` only for data within 5 minutes (`TelemetryDashboardPage`: "ONLINE means data within 5 minutes; STALE..."). The clean proof is the `ONLINE` badge + latest-telemetry values on the BF-08410 card. After the window the badge degrades to STALE — that is correct product behavior but weaker evidence; if the window is missed, re-publish (harmless — see matrix) and re-check.
- UI access: log in as local admin `admin@syncro.dev` / `syncro-admin-dev` (local-profile `LocalAdminBootstrap` default, `application-local.yml`) or as the seeded pilot viewer `technician.gm1@syncro.dev` / `syncro-pilot-dev` (seed §7, VIEWER + GM1 plant assignment — proves the viewer-level visibility path). Record which user, which route, what was observed (machine code `BF-08410`, name `JBF19`, latest telemetry values incl. counting 890 if displayed, freshness badge state), and the wall-clock time relative to the publish.
- Record EVERYTHING in the Dev Agent Record as an AC → evidence mapping (`AC1 -> verified by <command + observed output>`), with the publish timestamp, the traceId, and both verify-pilot runs' key verdict lines — per project rule "Completion requires evidence, not assertion."
- Dedupe/idempotency awareness: the fixture `messageId` is stable (`pilot-jbf19-before-threshold-890`). Redis ingest dedupe window is PT30S (`syncro:machine:{machineId}:telemetry:dedupe:{messageId}`) — if the last publish of the same messageId was <30s ago, the republish logs `mqtt_telemetry_duplicate` and is NOT new evidence; wait >30s (or note the duplicate line as such). The local DB currently already carries `counting=890` from the 7-3 live run — a fresh publish refreshes `updated_at`, the Redis hash, and produces a NEW traceId; the evidence recorded must come from the fresh publish, not the 7-3 leftovers.

**Block If:**
- The pilot publish lands in `telemetry_quarantine` (verify TELEMETRY section FAILs) — a quarantined publish means the contract validation rejected the fixture (wrong topic, mangled bytes, stale schema...); root-cause before proceeding, do not "prove" acceptance around it.
- The accepted-log line cannot be found in the backend console capture after a verify-PASS run — the evidence chain requires the log line; if console capture failed, re-publish and recapture (>30s gap).
- Backend cannot reach 200 health with real `.env` values, EMQX login fails, or the UI cannot log in — stop and report the blocker; do not substitute mocked/assumed evidence.
- An alert row EXISTS after the before-threshold publish — that contradicts the threshold invariant (evaluator bug or drifted seed); block and investigate rather than recording a failure as pass.

**Never:**
- Never modify ANY source file under `syncro/` — no production code, no seed, no migrations, no fixtures, no pilot scripts, no infra config. This story's only file changes are this story file and `sprint-status.yaml` (plus transient local log files, which are NOT committed — add nothing to the repo root; keep temp logs outside the repo or delete them after extracting the log line, matching the 7-3 temp-launcher cleanup).
- Never publish the THRESHOLD fixture (`mqtt-jbf19-threshold-payload.json`, counting=900) with the backend running — that creates the OPEN alert + TECHNICIAN notification job that story 7-5 must prove from a known pre-alert state; it would contaminate this story's zero-alert invariant and 7-5's starting state.
- Never create `docs/pilot-validation.md` or `docs/screenshots/pilot/` (story 7-7 owns both; UI proof here is recorded as text evidence — route, user, observed values, time — per the project rule that browser verification, not screenshots, is the evidence).
- Never use the swapped 3-13 topic `factory/BF-08410/GM1/telemetry` (backend parses plant=BF-08410/machine=GM1 → `unknown_plant` quarantine); the canonical topic is `factory/GM1/BF-08410/telemetry` (hardcoded as the publish script default — do not override `-Topic`).
- Never reset, wipe, or `down -v` the database/Redis/volumes to "clean up" state; the seed is idempotent and the existing counter state is a valid baseline.
- Never claim an AC without its recorded evidence; never mark the story done with verify-pilot FAIL lines or a missing UI observation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh full run (primary path) | Stack up, seed applied, backend + web running | Publish 200; log line `mqtt_telemetry_accepted traceId=T topic=factory/GM1/BF-08410/telemetry`; verify `-ExpectCounting 890` all PASS exit 0; Redis hash traceId=T; 0 alert rows; UI ONLINE card | — |
| Re-run over 7-3 leftover state | Counter already 890 (updated 2026-08-21 20:57) | Fresh publish (>30s after any same-messageId publish) → new traceId, refreshed `updated_at`/Redis hash; evidence taken from the fresh run | Evidence must cite the NEW traceId, not 7-3's |
| Republish inside PT30S dedupe window | Same messageId within 30s | Backend logs `mqtt_telemetry_duplicate`, persist skipped — NOT new evidence | Wait >30s and republish; note the duplicate line if observed |
| Backend down at publish | Backend not yet healthy | Broker accepts (`no_matching_subscribers`), message never delivered (cleanSession) | Blocker: start backend first, re-publish after health 200 |
| Backend started with `.env.example` | Placeholder MQTT credentials | MQTT auth/connect fails; no subscription | Use `syncro/.env` temp-launcher pattern (7-3 dev record) |
| Console capture missed | Backend foreground without redirect | Log line unavailable post-hoc | Re-publish (>30s) with capture running; log line is mandatory evidence |
| Quarantine polluted by older probes | Pre-existing `telemetry_quarantine` rows from earlier experiments | verify TELEMETRY FAILs on count>0 printing latest reason/topic | Inspect `raw_payload`/reason; if rows predate this story's publish (compare received timestamps), document them as pre-existing and root-cause separately; if the pilot publish itself quarantined → BLOCK |
| UI check after PT5M window | Redis latest hash expired | Dashboard badge STALE / "No data received" | Correct product behavior, weak evidence → re-publish and check within 5 min |
| UI login as pilot viewer | technician.gm1@syncro.dev / syncro-pilot-dev | Dashboard + telemetry visible (VIEWER + GM1 assignment) | If viewer lacks a route, use admin@syncro.dev and record the role difference |
| Alert exists before publish (leftover from other testing) | sparepart_alerts non-empty | Violates the invariant's precondition | Block: investigate origin; do not proceed with a polluted alert table — 7-5 state must not pre-exist 7-4 |
| Web on port 3001 (start-web.ps1 prod build) | Production build instead of dev server | Same routes; note port in evidence | Either server acceptable — record which |

</intent-contract>

## Code Map

**No NEW or EDIT files in `syncro/`.** All artifacts are read-only execution targets:

- `syncro/scripts/seed-pilot.ps1` -- EXECUTE -- idempotent seed apply + canonical-row summary (Flyway preflight included).
- `syncro/scripts/publish-jbf19-before-threshold.ps1` -- EXECUTE -- publishes `syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json` body verbatim (only `timestamp` refreshed) to `factory/GM1/BF-08410/telemetry` via EMQX Management API from `syncro/.env`.
- `syncro/scripts/verify-pilot.ps1` -- EXECUTE with `-ExpectCounting 890` -- six-section evidence verdicts; record PREFLIGHT/SEED/TELEMETRY/ALERT lines.
- `syncro/scripts/start-backend.ps1` -- REFERENCE ONLY for the env-parsing idiom (it loads `.env.example`; build the temp launcher against `syncro/.env` per 7-3 dev record; delete temp launcher + log file after evidence extraction).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- READ -- line 62: the `mqtt_telemetry_accepted traceId={} topic={}` INFO marker (the log-evidence contract).
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- READ -- counter-state save + Redis latest-hash write (field names `counting`, `countingDelta`, `receivedAt`, `traceId`).
- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- READ -- header lines 80-86: the 890 → 89.00% < 90 no-alert invariant; §7 pilot users.
- `syncro/apps/web/src/features/telemetry/components/telemetry-grid.tsx` + `telemetry-dashboard-page.tsx` -- READ -- freshness badge semantics (ONLINE = within 5 minutes) and card fields.
- UI routes: `/dashboard/telemetry` (rewrite `/telemetry`), `/dashboard/master-data/machines/BF-08410` (Machine Hub); login `admin@syncro.dev`/`syncro-admin-dev` (`application-local.yml` local-admin) or `technician.gm1@syncro.dev`/`syncro-pilot-dev` (seed).
- `_bmad-output/implementation-artifacts/spec-7-3-create-pilot-scripts-for-seed-publish-and-verify.md` -- READ -- Dev Agent Record: temp-launcher pattern, PS 5.1 quoting pitfalls, live-run timings (health ~20s), EMQX response-shape reality.

## Tasks & Acceptance

**Execution:**

- [ ] Preflight: infra stack up; `seed-pilot.ps1` exit 0 (idempotent, tripwire label correct); backend started via temp launcher with `syncro/.env` + console captured to a log file; health 200; web app started; login verified (record user + port). [AC 7.4-1 preamble]
- [ ] Confirm zero-alert precondition: documented SQL returns 0 `sparepart_alerts` rows for the JBF19 installation BEFORE the publish (proves the negative space is not pre-polluted). [AC 7.4-3]
- [ ] Publish: `publish-jbf19-before-threshold.ps1`; record messageId verbatim confirmation, refreshed timestamp, broker acceptance. [AC 7.4-1]
- [ ] Log evidence: extract `mqtt_telemetry_accepted traceId=... topic=factory/GM1/BF-08410/telemetry` from the backend console capture; record the traceId. [AC 7.4-4]
- [ ] Technical proof: `verify-pilot.ps1 -ExpectCounting 890` → record TELEMETRY PASS (counter 890, Redis hash fields, quarantine empty) and ALERT PASS (no alert, 89.00% < 90 math line); confirm Redis hash `traceId` == log-line traceId; record both documented SQL queries + results. [AC 7.4-1, 7.4-3, 7.4-4, 7.4-5]
- [ ] UI proof (within 5 min of publish): telemetry dashboard BF-08410 card (latest telemetry + ONLINE badge) and/or Machine Hub telemetry context; record route, user, observed values, wall-clock time; note pgAdmin as local/dev evidence tool only. [AC 7.4-2, 7.4-6]
- [ ] Cleanup + record: delete temp launcher/log files (or move outside repo); write the AC → evidence mapping with exact commands and outputs into Dev Agent Record; `git status --short` must show ONLY this story file + `sprint-status.yaml`. [all ACs]

**Acceptance Criteria:**

- Given pilot seed data exists and the backend is subscribed to EMQX, when the before-threshold JBF19 payload is published, then telemetry is accepted — evidenced by the `mqtt_telemetry_accepted` log line, `machine_counter_states.counting=890` with fresh `updated_at`, the Redis latest hash (counting 890, countingDelta 0, receivedAt, traceId), and `verify-pilot.ps1 -ExpectCounting 890` RESULT: PASS exit 0. [AC 7.4-1]
- Latest JBF19 telemetry is visible in the UI: the telemetry dashboard (`/dashboard/telemetry`) shows the BF-08410/JBF19 machine card with latest telemetry values and an ONLINE freshness badge (checked within the 5-minute window), and/or Machine Hub (`/dashboard/master-data/machines/BF-08410`) shows the telemetry context. [AC 7.4-2]
- No threshold alert is created for the installed sparepart: zero `sparepart_alerts` rows for the installation both before and after the publish; verify-pilot ALERT section PASSes with the `counting=890 -> 89.00% < 90%` reasoning; the math is the seed-documented invariant (consumed 890 of 1000 expected, 89.00% < 90 threshold). [AC 7.4-3]
- Audit/log evidence records accepted telemetry with traceId: the INFO log line carries traceId, and that traceId equals the Redis latest-hash `traceId` field of the same ingest (record both values and assert equality). [AC 7.4-4]
- Technical proof is inspectable through logs, pgAdmin, or documented query: the two SQL queries are recorded verbatim with their results (counter 890; zero alerts), executable in pgAdmin (labeled local/dev evidence tool, not a product feature), and the captured log line is quoted in the Dev Agent Record. [AC 7.4-5]
- UI proof is captured from Machine Hub or the telemetry dashboard: route, login user, observed machine identity (BF-08410 / JBF19), observed telemetry/freshness state, and observation time relative to publish are recorded as text evidence. [AC 7.4-6]

## Spec Change Log

- 2026-08-22: Spec created (draft → ready-for-dev). Ultimate context engine analysis completed — comprehensive developer guide created.

## Design Notes

- **Why a zero-code story still needs this much context:** the failure modes of a validation story are evidence-shaped, not code-shaped — stale traceId from the 7-3 run presented as fresh, verify run without `-ExpectCounting` (false-green), UI checked after the PT5M TTL, backend started with `.env.example` placeholders, or the threshold fixture published accidentally and "un-proving" the invariant. The Boundaries exist to make each of those impossible to record as a pass.
- **TraceId as the correlation spine:** one accepted message produces exactly one traceId that appears in (a) the INFO log line, (b) the Redis latest hash, and (c) verify-pilot's TELEMETRY output. The AC-4 equality check between (a) and (b) is the strongest cheap proof that the recorded evidence describes ONE ingest event, not an assembly of leftovers from different runs.
- **Why the pre-publish zero-alert check:** 7-5's known-state requirement means the alert table must be empty when 7-5 starts; checking it before AND after the before-threshold publish proves 7-4 left the system in the exact state 7-5 needs (no pre-existing alert that would later masquerade as the 7-5 threshold outcome).
- **UI proof timing:** ONLINE freshness is data within 5 minutes (telemetry dashboard copy, `telemetry-dashboard-page.tsx` line 62). The durable authority for "telemetry accepted" is `machine_counter_states` (Redis hash is best-effort with TTL) — the UI check is scheduled right after verify so the badge is ONLINE; if timing slips, republishing is harmless and produces a fresh traceId (record the final one used).
- **Leftover-state awareness:** the local DB carries `counting=890` from the 7-3 live verification (its dev record, run 5). That is a valid baseline; this story's evidence must come from ITS OWN publish (fresh `updated_at`, fresh traceId), which the >PT30S dedupe-window rule makes trivially safe.

## Verification

**Commands (all live on the local stack, Windows PowerShell 5.1 `powershell.exe`):**
- `powershell -NoProfile -File syncro/scripts/seed-pilot.ps1` -- expected: preflight PASS, `INSERT 0 0` family, canonical summary incl. `Electric · PLC · Wecon · LX5`, exit 0.
- Temp launcher (env from `syncro/.env`, console captured) + `curl http://localhost:8080/api/v1/health` -- expected: 200.
- `powershell -NoProfile -File syncro/scripts/publish-jbf19-before-threshold.ps1` -- expected: login PASS, publish accepted (delivered/empty broker message), messageId `pilot-jbf19-before-threshold-890` verbatim, counting 890, refreshed timestamp.
- `powershell -NoProfile -File syncro/scripts/verify-pilot.ps1 -ExpectCounting 890` -- expected: SEED PASS, TELEMETRY PASS (counter 890 + Redis hash + quarantine empty), ALERT PASS (`no alert - correct before-threshold state (counting=890 -> 89.00% < 90%)`), NOTIFICATION PASS baseline, ACK INFO baseline, RESULT: PASS, exit 0.
- Backend console capture: `Select-String -Path <log> -Pattern 'mqtt_telemetry_accepted'` -- expected: one line per accepted publish with traceId and topic `factory/GM1/BF-08410/telemetry`.
- UI (browser): login → `/dashboard/telemetry` (and/or `/dashboard/master-data/machines/BF-08410`) -- expected: BF-08410/JBF19 with latest telemetry + ONLINE badge within 5 min of publish.
- `git status --short` -- expected: ONLY this story file + `_bmad-output/implementation-artifacts/sprint-status.yaml`.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
