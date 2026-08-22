# Syncro Pilot Validation

This document consolidates the Phase 1 pilot validation proof produced by Epic 7 (stories 7-1 through 7-6). It lets an independent operator re-run the pilot from scratch against the local development stack and verify Phase 1 readiness end-to-end: canonical seed data, telemetry accepted below the 90% threshold, exactly one OPEN sparepart alert created at the threshold, a TECHNICIAN WAHA notification job, and acknowledgement stopping further escalation.

All commands, log markers, and SQL below were verified live against the committed tooling during stories 7-4 through 7-6 and are transcribed from those Dev Agent Records. The document is written so an operator can reproduce the pilot without reading the individual story records.

## 1. Purpose & Scope

- **Purpose.** Prove Phase 1 readiness for a single pilot machine (`BF-08410` / `JBF19` in plant `GM1`): telemetry is accepted, stored, and visible; the sparepart lifetime crosses a 90% threshold; a threshold alert is created with a correlated evidence chain; and acknowledgement stops escalation.
- **Scope.** The pilot is a local, single-machine, functional validation. It does **not** run the plant-scale load test (500 machines / 1 msg/s / 15 min) — that is success-metric SM-002/SM-003 scope (see [Success Metrics](#9-success-metrics-sm-001-through-sm-005)).
- **Out of scope.** No source code is changed to run the pilot. The seed, fixtures, and scripts are already committed. Screenshots are optional and live in `docs/screenshots/pilot/` (see [Screenshots](#11-screenshots)).

## 2. Canonical Pilot Scenario

The exact canonical dataset (fixed by PRD §12 "Pilot Validation Scenario" and enforced by `pilot-seed.sql` + `PilotSeedTest`):

| Entity | Value |
|---|---|
| Plant | `GM1` (name "Plant GM1") |
| Machine group | `Forming` (under GM1) |
| Machine | code `BF-08410`, name `JBF19`, brand Juki, status `ACTIVE`, installed 2026-05-27 |
| Sparepart | name `Electric · PLC · Wecon · LX5`, code `BF-08410GM1ELEPLCWEC000` (backend-exact label/code) |
| Installation | expected production count `1000`, baseline counter `0`, threshold `90%`, function name `Primary` |
| MQTT topic | `factory/GM1/BF-08410/telemetry` |
| Pilot users | `technician.gm1@syncro.dev`, `staff.gm1@syncro.dev`, `leader.gm1@syncro.dev` (VIEWER role, `syncro-pilot-dev` password, assigned to GM1) |
| Responsibilities | TECHNICIAN / STAFF / LEADER — one each on JBF19 |
| Recipient WhatsApp numbers | `6281234567801 / 02 / 03` — **PLACEHOLDERS** (see [WAHA caveat](#6-expected-results)) |

Counter math (pinned in the seed header; `SparepartLifetimeEvaluator`, HALF_UP 2 decimals):

```
consumed    = floorMod(counting - baseline, 65536) = floorMod(counting - 0, 65536)
consumedPct = consumed * 100 / expected_production_count

counting 890 -> 89.00%  < 90% -> telemetry accepted, NO alert
counting 900 -> 90.00% >= 90% -> exactly one OPEN alert + TECHNICIAN notification job
```

## 3. Prerequisites

1. **Docker** available (Docker Desktop on Windows), plus **PowerShell 5.1+** (the scripts are 5.1-compatible and run on PowerShell 7 too).
2. **`syncro/.env` copied from `syncro/.env.example`** with real values (not placeholders). All pilot scripts and `docker compose` read connection values from this file. The backend must run with `syncro/.env` — see step 4.
3. **Infra stack up** (from the repo root):
   ```powershell
   docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml up -d
   ```
   Expected services: `postgres`, `redis`, `influxdb`, `emqx`, `pgadmin`, `waha`.
4. **Backend running with `syncro/.env`** and healthy at `http://localhost:8080/api/v1/health` (expect `{"service":"syncro-backend","status":"UP",...}`). The backend subscribes to `factory/+/+/telemetry` at QoS 1 at startup (`mqtt_subscription_request topic=factory/+/+/telemetry qos=1`). This MUST happen **before** any publish: the backend uses `cleanSession(true)`, so a publish while it is down is accepted by the broker (`no_matching_subscribers`) but never delivered — no alert/job is created.
   - `syncro/scripts/start-backend.ps1` loads `.env.example` (placeholder values); for a live pilot, start the backend with the real `syncro/.env` environment (e.g. a launcher that loads `syncro/.env` then runs `mvnw -f syncro/apps/backend/pom.xml spring-boot:run`).
   - Or apply migrations explicitly first: `mvn -f syncro/apps/backend/pom.xml flyway:migrate`.
5. **Frontend dev server** (port 3001, since 3000 is WAHA's port):
   ```powershell
   npm --prefix syncro/apps/web run dev -- -p 3001
   ```
   (Alternatively `syncro/scripts/start-web.ps1` builds and serves a production build on 3001.) Verify the app loads and log in (see section 7).

## 4. Seed Instructions

Apply the canonical seed (idempotent, guarded `INSERT ... WHERE NOT EXISTS`; re-running inserts zero rows):

```powershell
powershell -NoProfile -File syncro/scripts/seed-pilot.ps1
```

The script preflights Flyway (refuses to seed an unmigrated database), pipes `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` into the in-container `psql` with `PGCLIENTENCODING=UTF8` + `ON_ERROR_STOP=1` (atomic `BEGIN/COMMIT`), then prints a canonical-row summary. Expected:

- `PASS: Flyway migrations applied: <n>`
- All `INSERT 0 0` (idempotent)
- Summary rows matching, **including the tripwire**:
  `Sparepart (UTF-8 label tripwire)` → `BF-08410GM1ELEPLCWEC000 | Electric · PLC · Wecon · LX5`
- `Seed apply complete. Re-run safe: ...` and exit 0.

The seed is **not** a Flyway migration and is not auto-applied. It is a deliberate, manually applied local-dev artifact.

## 5. Publish Instructions

Two fixture payloads (committed at `syncro/tests/fixtures/`) drive the two boundary states. Both are published verbatim to `factory/GM1/BF-08410/telemetry` at QoS 1 (retain false) via the EMQX Management API (no MQTT client needed on the host); only the `timestamp` field is refreshed to the current UTC instant — `messageId` and everything else stay byte-identical.

**Before-threshold (counting=890 → 89.00%, no alert):**
```powershell
powershell -NoProfile -File syncro/scripts/publish-jbf19-before-threshold.ps1
```
Fixture: `mqtt-jbf19-before-threshold-payload.json` — `messageId pilot-jbf19-before-threshold-890`, `counting 890`, `runtimeHours 12.5`.

**Threshold (counting=900 → 90.00%, one alert):**
```powershell
powershell -NoProfile -File syncro/scripts/publish-jbf19-threshold.ps1
```
Fixture: `mqtt-jbf19-threshold-payload.json` — `messageId pilot-jbf19-threshold-900`, `counting 900`, `runtimeHours 13.0`.

Both scripts print `PASS: login ok` then `PASS: publish accepted by broker`, followed by the verbatim `messageId`, `counting`, refreshed `timestamp`, and `topic`. `-NoTimestampRefresh` publishes the file fully verbatim (stale timestamp) — for exact-duplicate evidence runs only.

**Duplicate-publish behavior** (documented by the scripts): republishing the same fixture within the Redis dedupe window (default `PT30S`, key `syncro:machine:{machineId}:telemetry:dedupe:{messageId}`) makes the backend log `mqtt_telemetry_duplicate` and skip persisting — intentional duplicate evidence, not a second alert/job. After the window, the alert-level non-RESOLVED dedup guard and the notification-job idempotency key (`{alertId}::TECHNICIAN`, unique on `(alert_id, escalation_level)`) keep a republish harmless.

## 6. Expected Results

**Before-threshold publish (counting=890).** Telemetry is accepted; **no** sparepart alert is created.

- `mqtt_telemetry_accepted traceId=... topic=factory/GM1/BF-08410/telemetry`
- `machine_counter_states.counting = 890`, `telemetry_quarantine` empty
- `verify-pilot.ps1 -ExpectCounting 890` ALERT section: `[PASS] no alert - correct before-threshold state (counting=890 -> 89.00% < 90%)`

**Threshold publish (counting=900).** One `OPEN` alert with `consumed_percentage_snapshot = 90.00` and a `TECHNICIAN` notification job.

- `mqtt_telemetry_accepted traceId=...` then `[traceId=...] Alert created for installation <installationId> threshold 90% consumed 90.00%`
- Exactly one non-RESOLVED `sparepart_alerts` row: `OPEN | 90.00`
- One `notification_jobs` row: `TECHNICIAN` with `recipient_phone = 6281234567801`, `trace_id` matching the alert
- The same `traceId` correlates telemetry → alert → notification job → audit (see [TraceId chain](#8-technical-proof-via-logs-and-database))
- `verify-pilot.ps1 -ExpectCounting 900` ALERT section: `[PASS] exactly one non-RESOLVED alert: status=OPEN consumed_percentage_snapshot=90.00 trace_id=...`

**Acknowledge before the escalation window.** Escalation interval default is 15 minutes from the TECHNICIAN job's `sentAt`.

- Acknowledge via `POST /api/v1/alerts/{alertId}/acknowledge` → `204` → alert `ACKNOWLEDGED`, `status_reason` set
- TECHNICIAN job → `CANCELLED`, `next_attempt_at` NULL
- Audit `UPDATE` row with `escalationCancelledCount` (≥ 1)
- After **>15 minutes** past the TECHNICIAN `sentAt`: `notification_jobs` for the alert still has exactly one row (TECHNICIAN CANCELLED) and zero `STAFF`/`LEADER`/`SPV`/`MANAGER` rows (double barrier: job `status=SENT` filter + alert non-OPEN guard in `EscalationService`)

**WAHA reality check (placeholder numbers).** The seed's recipient numbers (`6281234567801/02/03`) are placeholders. The expected live-WAHA outcome for a threshold alert is `PENDING` / `ROUTING_FAILED` / `SENT` **with attempt evidence** in `notification_jobs`/attempt history. A send to an unregistered placeholder number returns HTTP 500 `no LID found` from the GOWS engine (deferred-work DW-56). This is expected seed-caveat behavior — it is **not** evidence that a real WhatsApp message was delivered. Replace the placeholder numbers with real numbers in `pilot-seed.sql` (and re-seed) before a live WAHA delivery pilot.

## 7. Operator Proof via UI

Log in to the web app (port 3001): `technician.gm1@syncro.dev` / `syncro-pilot-dev` (VIEWER, GM1-assigned — the "responsible user" path) or a SUPER_ADMIN. All routes below are real app routes.

| Route | What to look for |
|---|---|
| `/dashboard/telemetry` | BF-08410 / JBF19 card with latest telemetry: production count (890 → 900), running state, runtime hours, **Online** freshness badge (data received within the last 5 minutes — the Redis latest hash has a 5-minute TTL, so check within 5 min of the publish). |
| `/dashboard/master-data/machines/BF-08410` | Machine Hub: machine header (JBF19, ACTIVE), Telemetry tab with the same latest values, and a "Sparepart Alert State" section linking the alert for `Electric · PLC · Wecon · LX5`. |
| `/dashboard/alerts` | Alert list row: status **Open**, BF-08410 / JBF19, `Electric · PLC · Wecon · LX5` Primary, Threshold 90%, Consumed 90.0%. |
| `/dashboard/alerts/{alertId}` | Alert detail: status badge (Open → Acknowledged), **"Why this alert fired"**: consumed percentage **90.00%** reached configured threshold of **90%**; Lifetime Evidence (baseline 0, current counter 900, expected 1,000, consumed 900, consumed % 90.00%, threshold 90%); **Escalation Timeline** (TECHNICIAN `sent`/`stopped by acknowledgement`); Notification History; Audit Evidence (`SYSTEM CREATE ALERT:...@90%`); Alert Metadata **Trace ID**. |
| `/dashboard/system-health` | System health dashboard: backend/actuator status, telemetry freshness, ingest/notification worker status, stale machines, quarantine log, data-quality/latency indicators. |

Before the threshold publish, the Operations Overview should show "Open Alerts 0"; after it, exactly the one OPEN alert. After acknowledge, the alert detail badge flips to **Acknowledged** ("Escalation is paused") and the action panel shows only **Resolve** (no Acknowledge button).

Mobile (390×844): the acknowledge action is reachable from the alert detail header button on mobile too (no confirm dialog — one click + success toast; page-spec sticky bottom bar is a documented deviation).

## 8. Technical Proof via Logs and Database

All technical evidence is inspectable through the backend console, the database (pgAdmin or `psql`), and `verify-pilot.ps1`. **pgAdmin is a local/dev evidence tool only — see [section 10](#10-pgadmin-scope-disclaimer).**

### Log markers (grep the backend console)

Grep-able substrings the operator should look for:

| Marker | Level | Meaning |
|---|---|---|
| `mqtt_subscription_request topic=factory/+/+/telemetry qos=1` | INFO | Backend subscribed before any publish (startup) |
| `mqtt_telemetry_accepted traceId={id} topic=factory/GM1/BF-08410/telemetry` | INFO | One line per accepted publish; the traceId is the correlation key |
| `mqtt_telemetry_persisted ... countingDelta={n}` | INFO | Telemetry persisted (counter state + Influx + Redis latest hash) |
| `[traceId={id}] Alert created for installation {installationId} threshold 90% consumed 90.00%` | INFO | Threshold crossed → one alert created |
| `mqtt_telemetry_duplicate traceId={id} machineCode=BF-08410 messageId=pilot-jbf19-threshold-900 winnerTraceId={id}` | WARN | Republish inside the 30s dedupe window |
| `[WAHA][traceId={id}] send attempt phone=*** status=201` | INFO | WAHA accepted the send (not delivery to a real device) |

Grep command (PowerShell):
```powershell
Select-String -Path <backend-log> -Pattern 'mqtt_telemetry_accepted','Alert created for installation','mqtt_telemetry_duplicate'
```

### SQL queries (executable in pgAdmin / psql)

All queries are for the canonical machine (`plant GM1`, `machine BF-08410`).

1. **Counter state** (proves accepted, persisted telemetry):
   ```sql
   SELECT counting, updated_at FROM machine_counter_states mcs
   JOIN machines m ON m.id = mcs.machine_id
   JOIN plants p ON p.id = m.plant_id
   WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410';
   ```
   → `890` (after before-threshold) / `900` (after threshold), with a fresh `updated_at`.

2. **Alerts** (zero for before-threshold; exactly one OPEN/ACKNOWLEDGED 90.00 for threshold):
   ```sql
   SELECT id, status, consumed_percentage_snapshot, threshold_percentage, trace_id
   FROM sparepart_alerts sa
   JOIN machines m ON m.id = sa.machine_id
   JOIN plants p ON p.id = m.plant_id
   WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' AND sa.status <> 'RESOLVED';
   ```
   → before-threshold: no rows; threshold: `{id} | OPEN | 90.00 | 90 | {traceId}`.

3. **Notification jobs** (TECHNICIAN job for the alert; CANCELLED after acknowledge):
   ```sql
   SELECT id, escalation_level, status, idempotency_key, trace_id, recipient_phone, error_detail
   FROM notification_jobs
   WHERE alert_id = '<alertId>';
   ```
   → `{jobId} | TECHNICIAN | SENT|PENDING|ROUTING_FAILED | {alertId}::TECHNICIAN | {traceId} | 6281234567801 | {error_detail}`; after acknowledge `status = CANCELLED`, `next_attempt_at` NULL.

4. **Audit log** (alert CREATE carries the traceId; acknowledge carries the actor):
   ```sql
   SELECT actor_name, action, entity_type, entity_id, previous_value, new_value, created_at
   FROM audit_log
   WHERE entity_id = '<alertId>' AND entity_type = 'ALERT'
   ORDER BY created_at;
   ```
   → `SYSTEM | CREATE | ALERT | {alertId} | ... {"consumedPercentage":"90.00","traceId":"{id}",...}`, then `SYSTEM | UPDATE | ... {"transition":"OPEN→ACKNOWLEDGED","actorId":"{user}","status":"ACKNOWLEDGED","reason":"...","escalationCancelledCount":1}`.

5. **Quarantine diagnostic** (must stay empty):
   ```sql
   SELECT count(*) FROM telemetry_quarantine;
   SELECT rejection_reason, rejection_field, topic FROM telemetry_quarantine ORDER BY received_at DESC LIMIT 1;
   ```

6. **Post-window escalation proof** (>15 min after TECHNICIAN `sentAt`): re-run query 3 — still exactly one row (TECHNICIAN CANCELLED); zero `STAFF`/`LEADER` rows.

### TraceId correlation chain

One accepted message produces one traceId that appears in all four places — the strongest proof that the evidence describes a single end-to-end flow:

```
mqtt_telemetry_accepted traceId=...            (backend log)
  -> sparepart_alerts.trace_id                 (query 2)
  -> notification_jobs.trace_id                (query 3)
  -> audit_log.new_value["traceId"]            (query 4, alert CREATE row)
```

Example from the 7-5 live run: traceId `ebfeec74-d989-4190-a772-2a1517f6fef3` appeared in the log line, the alert row, the job row, and the CREATE audit JSON.

**Known acknowledge-audit gap (transcribed from 7-6):** the acknowledge audit row uses `AuditLogWriter.recordSystem` — `actor_name` shows `SYSTEM` and the real actor is only present as `new_value.actorId`. The acknowledge row carries **no traceId**; the correlation point is the `sparepart_alerts.trace_id` column.

### verify-pilot.ps1 verdicts

```powershell
powershell -NoProfile -File syncro/scripts/verify-pilot.ps1 -ExpectCounting 900
```

Six sections, each printing `[PASS]`/`[FAIL]`/`[INFO]`; the script exits non-zero only on FAIL:

1. **PREFLIGHT** — postgres `SELECT 1` and redis `PING` hard-fail; EMQX and backend health warn-only.
2. **SEED** — canonical 7-1 row counts (plant/group/machine/sparepart/installation/users/responsibilities).
3. **TELEMETRY** — `machine_counter_states` (with `-ExpectCounting`, FAILs unless exactly that value), Redis latest hash (`counting`, `countingDelta`, `receivedAt`, `traceId`; 5-min TTL, absent hash = INFO), `telemetry_quarantine` empty.
4. **ALERT** — state-keyed: `890` expects zero non-RESOLVED alerts (`no alert - correct before-threshold state`); `900` expects exactly one non-RESOLVED alert with `consumed_percentage_snapshot=90.00` + `trace_id`.
5. **NOTIFICATION** — `notification_jobs` rows for the alert (level/status/phone/trace/error); placeholder numbers print the seed-caveat INFO.
6. **ACKNOWLEDGEMENT RESULT** — alert status + per-level job timeline; prints `observation: STAFF job SENT while alert is ACKNOWLEDGED` if a regression is detected.

Then **EVIDENCE POINTERS** print the Machine Hub / telemetry / alerts / system-health / backend-health URLs and an optional InfluxDB query:
```powershell
docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml exec -T influxdb sh -c 'influxdb3 query --token "$INFLUXDB3_ADMIN_TOKEN" --database syncro "SELECT * FROM telemetry ORDER BY time DESC LIMIT 5"'
```

## 9. Success Metrics SM-001 through SM-005

Quoted from the PRD, with how the pilot does or does not demonstrate each:

| Metric | PRD definition | Pilot evidence |
|---|---|---|
| **SM-001** | Telemetry is accepted, stored, and visible for at least one pilot active machine. | **Demonstrated.** `mqtt_telemetry_accepted`/`mqtt_telemetry_persisted` markers, `machine_counter_states`, Redis latest hash, Influx points, and the telemetry dashboard / Machine Hub UI all show BF-08410 data. |
| **SM-002** | A plant-scale load test with 500 simulated active machines publishing one payload per second for 15 minutes completes without backend process crash. | **NOT run by the pilot.** This is a separate scale validation the pilot does not exercise (the pilot publishes a handful of messages for one machine). |
| **SM-003** | During the plant-scale load test, latest telemetry remains visible for active machines while historical telemetry writes may lag within defined operational tolerance. | **NOT run by the pilot.** Scale-validation scope; requires the SM-002 load test to observe the latest-vs-history behavior under load. |
| **SM-004** | A sparepart lifetime alert is created when production count reaches the configured threshold. | **Demonstrated.** counting=900 → 90.00% ≥ 90% → exactly one OPEN alert with `consumed_percentage_snapshot = 90.00`, visible in the alert list/detail UI and `sparepart_alerts`. |
| **SM-005** | A WhatsApp notification is sent through WAHA for the threshold alert. | **Demonstrated up to the queue + attempt evidence.** A TECHNICIAN `notification_jobs` row is created and the worker attempts the WAHA send (attempt history + `send attempt ... status=201`). With the seed's placeholder numbers, the expected live-WAHA outcome is `PENDING`/`ROUTING_FAILED`/`SENT` with attempt evidence, not a verified real-device delivery (see section 6). |

## 10. pgAdmin Scope Disclaimer

**pgAdmin is a local/dev evidence tool only.** It is not a runtime dependency, a user-facing feature, a production requirement, or a replacement for the application admin UI. The application's own admin screens (Machine Hub, telemetry dashboard, alerts, system health) are the product surfaces; pgAdmin is used here purely to inspect the pilot's persisted evidence rows. (Echoes `syncro/docs/local-development.md`.)

## 11. Screenshots

Optional operator screenshots belong in the committed `docs/screenshots/pilot/` directory (relative to `syncro/docs/pilot-validation.md`). When capturing screenshots, name them by proof target (e.g. `telemetry-online.png`, `alert-open-detail.png`, `alert-acknowledged.png`, `system-health.png`) and reference them here. Never commit transient captures from `.playwright-mcp/` or temporary directories.

Screenshots present in this repository:

*(none yet — directory placeholder committed with `.gitkeep`)*

---

*This document is the durable consolidation of the Epic 7 pilot validation evidence. It supplements — but does not replace — the per-story Dev Agent Records in `_bmad-output/implementation-artifacts/`.*
