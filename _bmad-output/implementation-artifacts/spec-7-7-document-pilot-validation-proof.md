---
title: 'Document Pilot Validation Proof'
type: 'documentation'
baseline_commit: 4254406
created: '2026-08-22'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
---

<intent-contract>

## Intent

**Problem:** Stories 7-1 through 7-6 produced and executed the pilot validation flow end-to-end on the live local stack (canonical seed → before-threshold telemetry accepted → threshold telemetry creates one OPEN alert → TECHNICIAN WAHA notification job → acknowledgement stops escalation), each capturing a correlated evidence chain (backend log markers, SQL rows, API responses, UI states, audit rows). None of that proof is consolidated into a single operator/technical document. Story 7-7 must write `syncro/docs/pilot-validation.md` so a project stakeholder can re-run the pilot from scratch and independently verify Phase 1 readiness, and so the proof survives individual Dev Agent Records and transient logs.

**Approach:** Documentation story — **no source-code changes under `syncro/apps/`, `syncro/infra/`, `syncro/scripts/`, or `syncro/tests/`**. The only production-repo deliverables are:
- NEW `syncro/docs/pilot-validation.md` — the consolidated pilot validation document (prerequisites, seed instructions, publish instructions, expected results, operator proof section, technical proof section, success-metrics explanation SM-001 through SM-005, pgAdmin-scope disclaimer, screenshot reference).
- NEW `syncro/docs/screenshots/pilot/` directory (with a `.gitkeep`) so the document's screenshot reference points at a real, committed path — the directory itself is the placeholder for optional operator screenshots.

The document MUST be written from the actual evidence the pilot flow produces today (the 7-1..7-6 Dev Agent Records are the source of truth for what markers/SQL/UI states exist). It MUST NOT describe features that do not exist, MUST distinguish pgAdmin as a local/dev evidence tool (not a product feature), and MUST explain success metrics SM-001 through SM-005 honestly — including which are demonstrated by the pilot and which (SM-002, SM-003) require the plant-scale load test the pilot does not run.

## Boundaries & Constraints

**Always:**
- Verify the current state of the pilot tooling before writing each documented command so every instruction actually works against the committed scripts. The authoritative script contracts live in:
  - `syncro/scripts/seed-pilot.ps1` — idempotent seed apply + canonical-row summary (tripline label `Electric · PLC · Wecon · LX5`).
  - `syncro/scripts/publish-jbf19-before-threshold.ps1` — publishes fixture `mqtt-jbf19-before-threshold-payload.json` (counting=890, messageId `pilot-jbf19-before-threshold-890`) to `factory/GM1/BF-08410/telemetry` with refreshed UTC timestamp.
  - `syncro/scripts/publish-jbf19-threshold.ps1` — publishes fixture `mqtt-jbf19-threshold-payload.json` (counting=900, messageId `pilot-jbf19-threshold-900`) to the same topic.
  - `syncro/scripts/verify-pilot.ps1` — six-section evidence verdicts (PREFLIGHT / SEED / TELEMETRY / ALERT / NOTIFICATION / ACKNOWLEDGEMENT RESULT) plus EVIDENCE POINTERS; `-ExpectCounting 900` hard-asserts the persisted counter.
  - `syncro/scripts/start-backend.ps1`, `syncro/scripts/start-web.ps1` — local run helpers referenced for the documented "start apps" instructions.
- The documented prerequisites MUST cover, at minimum: Docker available; `syncro/.env` copied from `syncro/.env.example` (real values, not placeholders); the infra stack started with `docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml up -d`; backend run with the `syncro/.env` environment (health 200 at `http://localhost:8080/api/v1/health`); frontend dev server (`npm --prefix syncro/apps/web run dev`) or production build; and the backend subscribed to the MQTT topic BEFORE any threshold publish (`cleanSession(true)` means a publish while the backend is down is never delivered).
- The document MUST include the canonical pilot scenario data verbatim: plant `GM1`, machine group `Forming`, machine `BF-08410` / `JBF19`, sparepart `Electric PLC Wecon LX5`, installation expected production count `1000`, baseline counter `0`, threshold `90%`, and pilot users `technician.gm1@syncro.dev`, `staff.gm1@syncro.dev`, `leader.gm1@syncro.dev` with `TECHNICIAN`/`STAFF`/`LEADER` responsibilities on JBF19.
- The "expected results" section MUST document the real boundary behavior:
  - counting=890 → 89.00% < 90% → telemetry accepted, NO alert (`verify-pilot.ps1 -ExpectCounting 890` ALERT section passes with zero non-RESOLVED alerts).
  - counting=900 → `consumed = floorMod(900-0, 65536) = 900`; `consumedPct = 900*100/1000 = 90.00%`; ≥90 → exactly one `OPEN` alert with `consumed_percentage_snapshot = 90.00` + a `TECHNICIAN` notification job; traceId correlates telemetry → alert → job → audit.
  - Acknowledge before the escalation window → alert `ACKNOWLEDGED`, TECHNICIAN job `CANCELLED`, audit row with `escalationCancelledCount`, zero STAFF/LEADER jobs after >15 min.
  - WAHA reality check: pilot recipient phone numbers are PLACEHOLDERS (`6281234567801/02/03`), so the expected live-WAHA outcome is `PENDING`/`ROUTING_FAILED`/`SENT` with attempt evidence; a send to an unregistered placeholder number returns HTTP 500 `no LID found` from the GOWS engine (DW-56) — the document must present this as expected seed-caveat behavior, NOT a false "delivered" claim.
- The operator proof section MUST cover the exact UI evidence produced by the pilot: JBF19 latest telemetry on the telemetry dashboard / Machine Hub, the 90% lifetime progress, the OPEN alert list + alert detail (why it fired), the escalation timeline, the acknowledge action, and the system health dashboard. Reference the real routes: `/dashboard/telemetry`, `/dashboard/master-data/machines/BF-08410`, `/dashboard/alerts`, `/dashboard/alerts/{id}`, `/dashboard/system-health`.
- The technical proof section MUST cover the pgAdmin/log/job-table/audit evidence produced by the pilot: the accepted-telemetry log markers, the `Alert created for installation` marker, the SQL rows (machine_counter_states, sparepart_alerts, notification_jobs, audit_log, telemetry_quarantine-empty), the traceId correlation chain, and the verify-pilot.ps1 verdicts. Give the exact SQL and exact grep-able log substrings the operator should look for.
- The document MUST state, exactly once and prominently, that pgAdmin is a local/dev evidence tool only — not a runtime dependency, product feature, production requirement, or replacement for the application admin UI (echoing `syncro/docs/local-development.md`).
- The success-metrics section MUST explain SM-001 through SM-005 (quoted from the PRD) and map each to how the pilot does or does not demonstrate it:
  - SM-001 (telemetry accepted, stored, visible for one pilot machine) — demonstrated by the pilot.
  - SM-002 (plant-scale load test, 500 machines, 1 msg/s, 15 min, no crash) — NOT run by the pilot; documented as the separate scale validation it is.
  - SM-003 (latest telemetry remains visible during load while history lags) — NOT run by the pilot; documented as scale-validation scope.
  - SM-004 (alert created at threshold) — demonstrated by the pilot.
  - SM-005 (WhatsApp notification sent through WAHA) — demonstrated up to the queue + attempt evidence; note the placeholder-number caveat for real delivery.
- The document MUST reference `docs/screenshots/pilot/` as the optional screenshot location (with instructions to add screenshots there, not commit transient `.playwright-mcp/` captures).
- `git status --short` before finalizing MUST show only: `syncro/docs/pilot-validation.md`, `syncro/docs/screenshots/pilot/.gitkeep` (if created), this story file, and `_bmad-output/implementation-artifacts/sprint-status.yaml`.
- Run the smallest relevant checks after writing: `git status --short` for scope; optionally a markdown lint / rendering pass on the doc.

**Block If:**
- Any documented command does not match the committed script's actual parameters/behavior. If a script changed since 7-6, re-read it and document the CURRENT contract — never document an imagined one.
- You are tempted to claim delivery of a real WhatsApp message when the seed uses placeholder numbers. The doc must stay honest about PENDING/ROUTING_FAILED/attempt evidence.
- Creating `docs/pilot-validation.md` requires modifying any source file under `syncro/apps/` — that would be scope violation; stop and report.

**Never:**
- Never modify ANY file under `syncro/apps/`, `syncro/infra/`, `syncro/scripts/`, `syncro/tests/`, `syncro/.env`, or any applied Flyway migration. This story's repo changes are the doc file (and the empty screenshots directory) only.
- Never re-run the live pilot to "regenerate" evidence as part of this story unless a documented instruction is genuinely unverifiable from the committed artifacts — the 7-1..7-6 Dev Agent Records already contain the evidence the doc must transcribe.
- Never invent log markers, SQL column names, API endpoints, UI routes, or alert fields that are not present in the committed code/scripts or the 7-1..7-6 records. When unsure, read the actual script/code.
- Never commit transient screenshots from `.playwright-mcp/`, temp log files, or `C:\Users\Dell\AppData\Local\Temp\opencode\*`.
- Never claim SM-002/SM-003 are proven by the pilot.
- Never add planning/report artifacts beyond the story file unless explicitly asked.

## I/O & Edge-Case Matrix

| Scenario | State | Expected Documented Behavior |
|----------|-------|------------------------------|
| Fresh operator run (primary path) | Docker up, `.env` real values, backend + web running | Document: seed → verify pre-publish baseline (0 alerts) → publish before-threshold (890) → verify (0 alerts) → publish threshold (900) → verify (1 OPEN alert 90.00 + TECHNICIAN job) → acknowledge → verify CANCELLED + escalation stopped → health dashboard UI proof |
| Backend not running at threshold publish | `cleanSession(true)` | Document: broker accepts (`no_matching_subscribers`), message never delivered, no alert/job created — re-publish only after health 200 |
| Placeholder WAHA recipient numbers | `6281234567801/02/03` in seed | Document: PENDING/ROUTING_FAILED/SENT with attempt evidence is the expected live outcome; GOWS returns 500 `no LID found` for unregistered placeholders (DW-56); not a delivery claim |
| Post-window proof | >15 min after TECHNICIAN `sentAt` | Document: only TECHNICIAN CANCELLED row exists; zero STAFF/LEADER/SPV/MANAGER jobs (double barrier: job `status=SENT` filter + alert non-OPEN guard) |
| Screenshots | `docs/screenshots/pilot/` exists empty | Document: reference the folder for optional operator screenshots; never commit `.playwright-mcp/` or temp captures |
| SM-002 / SM-003 | plant-scale load test not run | Document: explicitly scope these as load-test metrics requiring a separate scale validation, not the pilot |
| pgAdmin | local/dev only | Document: single prominent disclaimer that pgAdmin is an evidence tool, not a product feature |

## Code Map

**NEW files (this story):**

- `syncro/docs/pilot-validation.md` — NEW — the consolidated pilot validation document. Sections required: (1) Purpose & scope; (2) Canonical pilot scenario (GM1/Forming/BF-08410/JBF19/Electric PLC Wecon LX5, installation 1000/0/90, users + responsibilities); (3) Prerequisites; (4) Seed instructions; (5) Publish instructions; (6) Expected results (before-threshold / threshold / acknowledge boundary behavior + WAHA placeholder caveat); (7) Operator proof via UI (JBF19 latest telemetry, 90% lifetime, OPEN alert + detail, escalation timeline, acknowledge action, health dashboard); (8) Technical proof via pgAdmin/logs/job table (exact markers + SQL); (9) Success metrics SM-001 through SM-005; (10) pgAdmin scope disclaimer; (11) Screenshots reference `docs/screenshots/pilot/`.
- `syncro/docs/screenshots/pilot/.gitkeep` — NEW — empty placeholder so the committed directory exists.

**READ (authoritative contracts to transcribe into the doc):**

- `syncro/scripts/seed-pilot.ps1` -- READ -- canonical-row summary incl. tripwire `Electric · PLC · Wecon · LX5`; env/compose/seed defaults; idempotency note.
- `syncro/scripts/publish-jbf19-before-threshold.ps1` -- READ -- topic `factory/GM1/BF-08410/telemetry`, QoS 1, timestamp refresh, messageId `pilot-jbf19-before-threshold-890`.
- `syncro/scripts/publish-jbf19-threshold.ps1` -- READ -- same topic, messageId `pilot-jbf19-threshold-900`, `-NoTimestampRefresh` duplicate-run switch, `no_matching_subscribers` semantics.
- `syncro/scripts/verify-pilot.ps1` -- READ -- section semantics (PREFLIGHT hard-fail on postgres; SEED canonical counts; TELEMETRY counter/Redis hash/quarantine; ALERT state-keyed 890→0 alerts / 900→exactly one 90.00; NOTIFICATION job rows; ACKNOWLEDGEMENT RESULT regression marker `observation: STAFF job SENT while alert is ACKNOWLEDGED`); `-ExpectCounting`; EVIDENCE POINTERS (routes + pgAdmin disclaimer + Influx query).
- `syncro/scripts/start-backend.ps1`, `syncro/scripts/start-web.ps1` -- READ -- documented local run helpers.
- `syncro/tests/fixtures/mqtt-jbf19-before-threshold-payload.json` -- READ -- exact fields (schemaVersion 1.0, messageId, timestamp, running, runtimeHours 12.5, counting 890, plantCode GM1, machineCode BF-08410).
- `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json` -- READ -- exact fields (counting 900, runtimeHours 13.0, messageId `pilot-jbf19-threshold-900`).
- `syncro/docs/local-development.md` -- READ -- the established docs conventions and the pgAdmin disclaimer to echo.
- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- READ -- canonical seed header (threshold math invariant `floorMod(900-0,65536)=900` → 90.00%), placeholder phone caveat.

**REFERENCE (evidence to transcribe from the Dev Agent Records — do NOT re-run the pilot):**

- `_bmad-output/implementation-artifacts/spec-7-6-validate-acknowledgement-stops-escalation.md` -- READ -- acknowledge-flow evidence: markers, post-acknowledge SQL, audit `new_value` shape (`{actorId, transition, status, reason, escalationCancelledCount}`), post-window proof, mobile acknowledge path, `recordSystem` actor_name=SYSTEM gap, timeline `sentAt` nuance.
- `_bmad-output/implementation-artifacts/spec-7-5-validate-threshold-alert-and-waha-notification-job.md` -- READ -- threshold-flow evidence: exact INFO markers (`mqtt_telemetry_accepted`, `mqtt_telemetry_persisted`, `Alert created for installation`, `mqtt_telemetry_duplicate`), alert dedup key, idempotency key `{alertId}::TECHNICIAN`, SQL query shapes (1)-(4), 30s dedupe window.
- `_bmad-output/implementation-artifacts/spec-7-4-validate-telemetry-before-threshold-does-not-create-alert.md` -- READ -- before-threshold evidence + preflight pattern.
- `_bmad-output/implementation-artifacts/spec-7-3-create-pilot-scripts-for-seed-publish-and-verify.md` -- READ -- script contract rationale, PS 5.1 quoting pitfalls.
- `_bmad-output/implementation-artifacts/spec-7-2-provide-pilot-mqtt-payload-fixtures.md` -- READ -- fixture contract.
- `_bmad-output/implementation-artifacts/spec-7-1-create-canonical-pilot-seed-data.md` -- READ -- canonical seed data + lifecycle-state constraint.

## Tasks & Acceptance

**Execution:**

- [x] Read the committed pilot tooling contracts (scripts + fixtures + seed) and confirm every documented command matches current reality. [AC 7.7-1..AC 7.7-6]
- [x] Create `syncro/docs/screenshots/pilot/.gitkeep` so the screenshot reference points at a real committed path. [AC 7.7-4]
- [x] Write `syncro/docs/pilot-validation.md` with: prerequisites; seed instructions; publish instructions; expected results; operator proof section (UI evidence); technical proof section (pgAdmin/log/job-table/audit evidence); success-metrics explanation SM-001 through SM-005; pgAdmin scope disclaimer; screenshot reference. [AC 7.7-1..AC 7.7-6]
- [x] Verify the document does not modify any file under `syncro/apps/`, `syncro/infra/`, `syncro/scripts/`, `syncro/tests/`. [scope]
- [x] `git status --short` shows ONLY: `syncro/docs/pilot-validation.md`, `syncro/docs/screenshots/pilot/.gitkeep`, this story file, `sprint-status.yaml`. [scope]
- [x] Fill the Dev Agent Record with an AC → evidence mapping (each AC to the document section + the source artifacts transcribed). [all ACs]

**Acceptance Criteria:**

- `docs/pilot-validation.md` exists at `syncro/docs/pilot-validation.md` and includes prerequisites, seed instructions, publish instructions, and expected results. [AC 7.7-1]
- The operator proof section covers the UI evidence: JBF19 latest telemetry, 90% lifetime, open alert, escalation timeline, acknowledge action, health dashboard — each with the real route and what to look for. [AC 7.7-2]
- The technical proof section covers the pgAdmin/log/job-table/audit evidence: exact grep-able log markers, exact SQL queries, the traceId correlation chain, and the verify-pilot.ps1 verdicts. [AC 7.7-3]
- The document references `docs/screenshots/pilot/` as the optional screenshot location and the directory exists (with `.gitkeep`). [AC 7.7-4]
- The document clearly and prominently distinguishes pgAdmin as a local/dev evidence tool, not a product feature. [AC 7.7-5]
- The document explains success metrics SM-001 through SM-005, mapping which are demonstrated by the pilot and which (SM-002, SM-003) require the separate plant-scale load test. [AC 7.7-6]

## Design Notes

- **Consolidate, don't re-run.** The 7-1..7-6 Dev Agent Records contain the full, verified evidence chain (publish timestamps, traceIds, SQL output shapes, log markers, UI states, audit rows). 7-7 transcribes that into a durable document an independent operator can reproduce. Do not re-execute the live pilot just to "refresh" evidence; the committed records are authoritative.
- **Every documented command must be real.** The doc is only as trustworthy as the commands it tells an operator to run. Verify each against the committed script (parameter names, default paths, switches like `-ExpectCounting`, `-NoTimestampRefresh`) and the fixtures before writing.
- **Expected-results honesty (placeholder numbers).** The pilot seed's recipient numbers are placeholders, so the doc must say the live-WAHA outcome is `PENDING`/`ROUTING_FAILED`/`SENT` with attempt evidence — and that the GOWS engine returns HTTP 500 `no LID found` for unregistered placeholders (DW-56). Presenting a placeholder send as a delivered WhatsApp message would be a lying-about-completion failure.
- **TraceId correlation chain is the heart of the technical proof.** The document should walk one traceId end-to-end: `mqtt_telemetry_accepted traceId=...` → `sparepart_alerts.trace_id` → `notification_jobs.trace_id` → `audit_log.new_value.traceId` (alert CREATE). Note the acknowledge audit uses `recordSystem` (actor_name = `SYSTEM`, real actor in `new_value.actorId`) and carries no traceId — the `sparepart_alerts.trace_id` column is the correlation point (7-6 finding). The doc should transcribe this exactly as it behaves, including the gap.
- **SM-002/SM-003 scoping.** The pilot proves SM-001, SM-004, SM-005 (up to queue/attempt evidence). SM-002 and SM-003 are plant-scale load-test metrics (500 machines, 1 msg/s, 15 min) that the pilot does not run. The doc must say so explicitly — marking them "covered by pilot" would be a false completion claim.
- **pgAdmin disclaimer.** Mirror the exact wording/positioning already established in `syncro/docs/local-development.md`: pgAdmin is local/dev evidence tool only, not a runtime dependency, user-facing feature, production requirement, or replacement for application admin UI.
- **Screenshot policy.** `docs/screenshots/pilot/` is the committed, documented location for optional operator screenshots. Transient captures from `.playwright-mcp/` or the temp dir must never be committed.

## Verification

- `git status --short` — expected: `syncro/docs/pilot-validation.md`, `syncro/docs/screenshots/pilot/.gitkeep`, `_bmad-output/implementation-artifacts/spec-7-7-document-pilot-validation-proof.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml` only.
- `git diff --stat` — expected: only the new doc + `.gitkeep` under `syncro/docs/`, the story file, and the sprint-status change (plus this file's own uncommitted state).
- Manual: read `syncro/docs/pilot-validation.md` top to bottom; confirm every command string matches the committed scripts/fixtures it cites; confirm no section claims SM-002/SM-003 are pilot-proven; confirm the pgAdmin disclaimer is present; confirm `docs/screenshots/pilot/` is referenced.
- No backend/frontend build or test run is required (no source changes). If a markdown linter is available it may be run on the new doc.

## Dev Agent Record

### Agent Model Used

b-ai/deepseek-v4-flash via opencode CLI, running the `bmad-dev-story` workflow for story 7-7.

### Debug Log References

None — documentation story; the pilot was NOT re-run (7-1..7-6 Dev Agent Records are the transcribed evidence source, per the story's "consolidate, don't re-run" constraint).

### Completion Notes List

**Deliverables produced:**

- `syncro/docs/pilot-validation.md` — the consolidated pilot validation document with all 11 required sections.
- `syncro/docs/screenshots/pilot/.gitkeep` — committed placeholder so the document's screenshot reference points at a real path.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `7-7-document-pilot-validation-proof: backlog` → `review` (7-7 was never moved to in-progress; the committed diff is `backlog → review`).

**Approved scope deviation (user-approved via question tool):** the repo-root `.gitignore` blanket rule `screenshots/` (line 238) was ignoring the required `syncro/docs/screenshots/pilot/.gitkeep` deliverable (`git check-ignore` confirmed the path was ignored). User approved adding the scoped negation `!syncro/docs/screenshots/` + `!syncro/docs/screenshots/pilot/` to the root `.gitignore` so the pilot screenshot directory is committable. The negation is scoped to the docs path only — the blanket rule still applies everywhere else. This adds `.gitignore` as a 5th file in `git status` (the story's AC 7.7-4 requires the directory to exist as a committed path, which is impossible under the blanket rule).

**Command-contract verification (no re-run, read-only):** every documented command was cross-checked against the committed artifacts before writing:
- `seed-pilot.ps1` (default `syncro/.env` / `syncro/infra/docker-compose.yml` / `db/seed/pilot-seed.sql`; Flyway preflight; UTF-8 tripwire `Electric · PLC · Wecon · LX5`; idempotent `INSERT 0 0`) — doc §4.
- `publish-jbf19-before-threshold.ps1` / `publish-jbf19-threshold.ps1` (topic `factory/GM1/BF-08410/telemetry`, QoS 1, timestamp refresh, `-NoTimestampRefresh` switch, messageIds `pilot-jbf19-before-threshold-890` / `pilot-jbf19-threshold-900`, `no_matching_subscribers` semantics) — doc §5.
- `verify-pilot.ps1` (six sections PREFLIGHT/SEED/TELEMETRY/ALERT/NOTIFICATION/ACKNOWLEDGEMENT RESULT + EVIDENCE POINTERS; `-ExpectCounting`; quarantine FAIL path; `observation: STAFF job SENT while alert is ACKNOWLEDGED` regression marker; placeholder-number INFO caveat; Influx optional query) — doc §8.
- `start-backend.ps1` (loads `.env.example` — documented in §3 with the guidance to run with `syncro/.env` instead) / `start-web.ps1` (production build on port 3001) — doc §3.
- Fixtures `mqtt-jbf19-before-threshold-payload.json` (schemaVersion 1.0, messageId, timestamp, running, runtimeHours 12.5, counting 890, plantCode GM1, machineCode BF-08410) and `mqtt-jbf19-threshold-payload.json` (counting 900, runtimeHours 13.0, messageId `pilot-jbf19-threshold-900`) — doc §2/§5.
- `pilot-seed.sql` header: canonical values (GM1/Forming/BF-08410/JBF19/`Electric · PLC · Wecon · LX5`/`BF-08410GM1ELEPLCWEC000`, installation 1000/0/90, pilot users, placeholder phones 6281234567801/02/03) and threshold math (`floorMod(900-0,65536)=900` → 90.00%) — doc §2/§6.
- `local-development.md` — the pgAdmin local/dev-only disclaimer wording was echoed verbatim in doc §10.

**AC → evidence mapping (doc section ← source artifacts transcribed):**

| AC | Document section | Source artifacts transcribed |
|----|------------------|------------------------------|
| 7.7-1: doc exists with prerequisites / seed / publish / expected results | §3 Prerequisites, §4 Seed, §5 Publish, §6 Expected results | `seed-pilot.ps1`, publish scripts, `verify-pilot.ps1`, fixtures, `pilot-seed.sql` header, 7-1..7-6 records (log markers, dedupe window PT30S, idempotency key `{alertId}::TECHNICIAN`, 15-min escalation window, DW-56 `no LID found`) |
| 7.7-2: operator proof — JBF19 telemetry, 90% lifetime, open alert, escalation timeline, acknowledge, health dashboard | §7 Operator Proof via UI | 7-4/7-5/7-6 UI observations (telemetry dashboard ONLINE badge, Machine Hub + Sparepart Alert State, alert list/detail incl. "Why this alert fired" 90.00% vs 90%, escalation timeline + Stopped by acknowledgement, mobile acknowledge) + real routes `/dashboard/telemetry`, `/dashboard/master-data/machines/BF-08410`, `/dashboard/alerts`, `/dashboard/alerts/{id}`, `/dashboard/system-health` (route existence verified via `glob` on `apps/web/src/app/**/page.tsx` and `middleware.ts`) |
| 7.7-3: technical proof — markers, SQL, traceId chain, verify verdicts | §8 Technical Proof via Logs and Database | 7-4/7-5/7-6 markers (`mqtt_telemetry_accepted`, `mqtt_telemetry_persisted`, `Alert created for installation`, `mqtt_telemetry_duplicate`, `[WAHA] send attempt ... status=201`), SQL queries (1)-(6) with exact output shapes, traceId four-point chain + acknowledge `recordSystem`/no-traceId gap, verify-pilot six-section verdicts + EVIDENCE POINTERS |
| 7.7-4: references `docs/screenshots/pilot/`, dir exists | §11 Screenshots + `syncro/docs/screenshots/pilot/.gitkeep` | `.gitkeep` created; doc references the folder and instructs to add screenshots there, never commit `.playwright-mcp/`; (gitignore negation added — user-approved) |
| 7.7-5: pgAdmin = local/dev evidence tool only | §10 pgAdmin Scope Disclaimer | `local-development.md` line 60 wording echoed; also reflected in `verify-pilot.ps1` EVIDENCE POINTERS line |
| 7.7-6: explains SM-001..SM-005 with pilot mapping | §9 Success Metrics | PRD lines 271-275 SM definitions; SM-001/SM-004 demonstrated, SM-005 up to queue+attempt evidence with placeholder caveat, SM-002/SM-003 explicitly scoped to the separate plant-scale load test |

**Scope verification:** `git status --short` shows exactly the expected files (plus the approved `.gitignore` negation): `M .gitignore`, `M _bmad-output/implementation-artifacts/sprint-status.yaml`, `?? _bmad-output/implementation-artifacts/spec-7-7-document-pilot-validation-proof.md`, `?? syncro/docs/pilot-validation.md`, `?? syncro/docs/screenshots/` (pilot/.gitkeep). No files under `syncro/apps/`, `syncro/infra/`, `syncro/scripts/`, `syncro/tests/` were touched. The pilot was NOT re-run.

### File List

- `syncro/docs/pilot-validation.md` (NEW — the consolidated pilot validation document; sections §1-§11)
- `syncro/docs/screenshots/pilot/.gitkeep` (NEW — committed placeholder for optional operator screenshots)
- `_bmad-output/implementation-artifacts/spec-7-7-document-pilot-validation-proof.md` (EDIT — tasks checked, Dev Agent Record filled, status → review)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (EDIT — 7-7: backlog → review)
- `.gitignore` (EDIT — user-approved scoped negation `!syncro/docs/screenshots/` + `!syncro/docs/screenshots/pilot/` so AC 7.7-4's committed directory is not ignored by the blanket `screenshots/` rule)

### Review Findings

- [x] [Review][Patch] §4 seed expected output misstates fresh-run result — the doc's own scenario is a from-scratch apply, which prints `INSERT 0 1` (guarded `WHERE NOT EXISTS` inserts fire); `INSERT 0 0` only on re-runs. Clarify "first run = INSERT 0 1, idempotent re-run = INSERT 0 0". [syncro/docs/pilot-validation.md:68]
- [x] [Review][Patch] §8 verify-pilot.ps1 commands omit `-WebUrl http://localhost:3001` — the script defaults to port 3000 (WAHA), so EVIDENCE POINTERS print the wrong app's URLs. [syncro/docs/pilot-validation.md:224]
- [x] [Review][Patch] §6/§8 claim the TECHNICIAN job → `CANCELLED` unconditionally, but `cancelActiveForAlert` matches only PENDING/SENT/RATE_LIMITED; ROUTING_FAILED/EXHAUSTED are terminal and not cancelled (contradicts the doc's own `ROUTING_FAILED` expected outcome). Qualify the claim. [syncro/docs/pilot-validation.md:114]
- [x] [Review][Patch] §8 SQL query 3 asserts `next_attempt_at` NULL after acknowledge but never selects that column — add `next_attempt_at` to the SELECT. [syncro/docs/pilot-validation.md:183]
- [x] [Review][Patch] §8 PREFLIGHT overstates redis as a hard-fail — an unreachable redis is warn-only (script continues); only an unexpected PONG value fails. Correct the description. [syncro/docs/pilot-validation.md:229]
- [x] [Review][Patch] §6/§9 expected live-WAHA outcome omits `EXHAUSTED` and misstates `ROUTING_FAILED` (routing always succeeds for the seeded whatsapp number; the realistic placeholder path is PENDING → HTTP 500 `no LID found` → retries → EXHAUSTED after maxAttempts=3). [syncro/docs/pilot-validation.md:118]
- [x] [Review][Patch] Spec Dev Agent Record misstates the sprint-status transition as "in-progress → review"; the committed diff is `backlog → review` (7-7 was never set to in-progress). [spec-7-7:159,190]
- [x] [Review][Patch] No hygiene/cleanup step for a leftover-state operator — the "Open Alerts 0" / "exactly one OPEN" claims break on a DB with 7-5/7-6 leftovers (seed never touches runtime-owned alert tables). Add a clean-slate prerequisite. [syncro/docs/pilot-validation.md:106]
- [x] [Review][Patch] Post-window acknowledge branch undocumented — a late acknowledge (>15 min) produces STAFF/LEADER `SENT` rows and the verify `observation: STAFF job SENT` regression marker, contradicting the "exactly one CANCELLED row" proof. Add a note on the 15-min boundary. [syncro/docs/pilot-validation.md:116]
- [x] [Review][Patch] §3.4 launcher example uses `mvnw` (sh) on a Windows-operator document — should be `mvnw.cmd`; also `flyway:migrate` needs the env vars loaded. [syncro/docs/pilot-validation.md:49]
- [x] [Review][Patch] §8 quarantine FAIL has no documented remediation — add a line pointing at `rejection_reason`/`rejection_field`. [syncro/docs/pilot-validation.md:202]
- [x] [Review][Patch] §8 grep command presupposes a backend log file the doc never tells the operator to create — add a capture/redirect note. [syncro/docs/pilot-validation.md:155]
- [x] [Review][Patch] §6 acknowledge API call omits JWT auth — a curl-following operator gets 401; note the Bearer token requirement (UI path in §7 is the preferred route). [syncro/docs/pilot-validation.md:113]
- [x] [Review][Defer] WAHA disclaimer contradicts the transcribed 7-6 evidence (`message WAS delivered to WhatsApp before cancellation` for a placeholder number) — spec-mandated disclaimer vs evidence tension; flagged for spec-level resolution, no doc action. [spec-7-7:130]
- [x] [Review][Defer] `.gitignore` negation re-includes the whole `syncro/docs/screenshots/` subtree (the `pilot/` negation is redundant) — user-approved change; tightening optional. [.gitignore:238-240]
