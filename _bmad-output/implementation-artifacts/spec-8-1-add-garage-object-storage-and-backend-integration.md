---
title: 'Add Garage Object Storage and Backend Integration'
type: 'feature'
created: '2026-08-23'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: 1feb3b8
final_revision: ab3e5d3
context:
  - '{project-root}/syncro/infra/docker-compose.yml'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Epic 8 adds a global sparepart image (FR-082) with only object-server references in PostgreSQL, but no object storage exists anywhere in local infrastructure or backend runtime. Adding it inside Story 8.4 would force that story to deliver infrastructure, config plumbing, and an untested storage client all at once. Today `apps/backend` has an `InfluxDbConfig`-style `@Bean` client for InfluxDB but no S3-compatible integration at all; `infra/docker-compose.yml` has no `garage` service (AR-006 stable-name set is `postgres`/`pgadmin`/`redis`/`influxdb`/`emqx`/`waha`).

**Approach:** Add a `garage` service to `infra/docker-compose.yml` (v2.3.0+ `--single-node --default-bucket`, S3 API on 3900, name-data volume for restart persistence), and add a typed `syncro.garage` properties record plus an `@Bean` S3-compatible client (AWS SDK v2 `S3Client` + `S3Presigner`) built from those properties. Expose a thin `ObjectStorageService` with `store(...)` and `presignGetUrl(...)` for Story 8.4 to consume, and a `@Component("garage")` health indicator following the Epic 6 `DependencyHealthSupport` pattern. No persistence, no sparepart schema change, no image API in this story.

## Boundaries & Constraints

**Always:**
- Service name is `garage`, stable per AR-006, alongside the existing stable-name services; `restart: unless-stopped` and a named data volume so uploaded objects survive container restart.
- Garage runs with `--single-node --default-bucket`; default access/secret keys and bucket come from environment (compulsory `:?` pattern), matching the existing compose variable style.
- Bucket is created at Garage startup via `--default-bucket`; the backend trusts it exists and does NOT create-the-bucket from application code in this story.
- Backend binds `syncro.garage` endpoint/access-key/secret-key/bucket/region/presign-ttl through a typed `@Validated @ConfigurationProperties(prefix = "syncro.garage")` record; no hardcoded URLs or credentials in source.
- Build the `S3Client` and `S3Presigner` as Spring `@Bean`s in a config class (mirror `InfluxDbConfig`), using path-style access and the configured endpoint. Both beans must be testable via a shared package-private builder accessor.
- `presignGetUrl` returns a short-TTL HTTPS GET URL (TTL from `syncro.garage.presign-ttl`, default 5 minutes).
- The health indicator maps failures to DOWN using `DependencyHealthSupport.reasonCode(...)` and never throws; it is a `@Component("garage")` registered by component scan (no `@Configuration`), matching all existing indicators.
- No bytes are written to PostgreSQL in this story.

**Block If:** Nothing in this story requires a human decision. Library choice and paths are pinned below; Garage image `dxflrs/garage:v2.3.0` and AWS SDK v2 `2.46.7` are chosen to satisfy AR-004 (exact version pinning).

**Never:**
- Never add `garage` to the `management.health.readiness.exclude` list or disable an auto-configured indicator for `garage`.
- Never persist image bytes to PostgreSQL; never introduce a `spareparts.image_*` column nor a `/spareparts/{id}/image` API (Story 8.4 owns the schema + API).
- Never hardcode the Garage endpoint, keys, or bucket; never log credential material.
- Never create the bucket from Java this story — `--default-bucket` owns it; add no bucket-provisioning migration or background task.
- Never map S3 `NoSuchBucket`/`NoSuchKey`/`AccessDenied` raw messages into health details — always sanitize via `DependencyHealthSupport.reasonCode(...)`.
- Define no new resilience4j circuit breaker here (WAHA pattern is notification-path specific); keep the client resilient enough via SDK timeouts only, since 8.4 adds the real write path.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | Configured endpoint + valid keys + existing bucket, `store(key, bytes, contentType)` | Object stored, returns the key/path (bucket-relative) | No error |
| HAPPY_PATH | Existing object, `presignGetUrl(key)` | Returns a short-TTL presigned HTTPS GET URL | No error |
| PRESIGN_TTL | `presign-ttl` set to e.g. 5 minutes | URL `expires` matches configured TTL ±sdk rounding | No error |
| CONNECTION_REFUSED | Endpoint down | Health returns DOWN, reason `CONNECTION_REFUSED` | Sanitized, logged WARN, never thrown |
| UNAUTHORIZED | Wrong access/secret key | Client throws, health DOWN, reason `UNAUTHORIZED` | Sanitized, logged WARN |
| NOT_READY | Garage not yet healthy / bucket not present at boot | Backend still boots; beans lazy-resolve; health surfaces DOWN until Garage is reachable | Non-fatal; no startup crash |
| HEALTH_OK | Garage reachable, head-bucket succeeds | Health returns UP with timestamp/label/severity | No error |

</intent-contract>

## Code Map

**Backend (all under `apps/backend/src/main/java/com/syncro`):**
- `config/GarageProperties.java` -- NEW -- `@Validated @ConfigurationProperties(prefix = "syncro.garage")` record: `@NotBlank url`, `@NotBlank accessKey`, `@NotBlank secretKey`, `@NotBlank bucket`, `@NotNull region`, `@NotNull Duration presignTtl`.
- `config/GarageS3Config.java` -- NEW -- `@Configuration` exposing `S3Client` + `S3Presigner` beans from `GarageProperties`, path-style access, region, endpoint override; package-private `s3ClientBuilder()`/`s3PresignerBuilder()` accessors for tests.
- `storage/application/ObjectStorageService.java` -- NEW -- contract used by later stories: `String store(String key, byte[] data, String contentType)` and `String presignGetUrl(String key)`. Returns a stable object reference path for persistence; surfaced for Story 8.4.
- `storage/infrastructure/GarageObjectStorageService.java` -- NEW -- `@Service` implementing `ObjectStorageService` via `S3Client`/`S3Presigner`.
- `storage/infrastructure/GarageHealthIndicator.java` -- NEW -- `@Component("garage")` implementing `HealthIndicator`; head-bucket probe bounded by SDK timeouts; `DependencyHealthSupport` enrichment.

**Infra:**
- `infra/docker-compose.yml` -- MODIFY -- add `garage` service (environment via compulsory vars, `dxflrs/garage:v2.3.0`, `/garage server --single-node --default-bucket`, S3 port, named `garage_data` volume, `restart: unless-stopped`) + declare the named volume.
- `infra/garage/garage.toml` -- NEW -- single-node config: `metadata_dir`/`data_dir` on the mounted volume paths, `replication_factor = 1`, `db_engine = "sqlite"`, S3 API bind on 3900, region `garage`; `rpc_secret`/`admin_token` sourced from env (compulsory) so no secret is committed.
- `.env.example` -- MODIFY -- add host/port/access-key/secret-key/bucket placeholders (no real secrets).

**Config (backend resources):**
- `application.yml` -- MODIFY -- add `syncro.garage` block bound to env overrides.
- `application-local.yml` -- MODIFY -- add `syncro.garage` localhost defaults.

**Testing (backend):**
- `src/test/java/com/syncro/config/GarageS3ConfigTest.java` -- NEW -- verifies the config produces working clients and binds properties.
- `src/test/java/com/syncro/storage/infrastructure/GarageObjectStorageServiceTest.java` -- NEW -- unit-test `store`/`presignGetUrl` against mock/replay clients; covers TTL and error mapping.
- `src/test/java/com/syncro/storage/infrastructure/GarageHealthIndicatorTest.java` -- NEW -- up/down mapping with sanitized reasons.

## Tasks & Acceptance

**Execution:**

**Backend integration:**
- [x] `apps/backend/pom.xml` -- ADD `software.amazon.awssdk:s3` and `software.amazon.awssdk:url-connection-client` at the same pinned version 2.46.7 (AWS SDK artifacts release in lockstep; confirm the resolved version builds on Java 25 / Spring Boot 4.0.x) -- rationale: S3-compatible client + presigner with a lightweight HTTP client, no S3 BOM needed.
- [x] `apps/backend/src/main/java/com/syncro/config/GarageProperties.java` -- NEW -- typed properties record with validation (see Code Map) -- rationale: `syncro.garage` binding without hardcoded URLs.
- [x] `apps/backend/src/main/java/com/syncro/config/GarageS3Config.java` -- NEW -- `@Bean` `S3Client`/`S3Presigner` build-from-properties with path-style + endpoint override -- rationale: reusable client for 8.4.
- [x] `apps/backend/src/main/java/com/syncro/storage/application/ObjectStorageService.java` -- NEW -- `store`/`presignGetUrl` contract -- rationale: boundary interface the image API depends on.
- [x] `apps/backend/src/main/java/com/syncro/storage/infrastructure/GarageObjectStorageService.java` -- NEW -- S3-backed implementation -- rationale: the only place S3 bytes/privacy of the URL is realized.
- [x] `apps/backend/src/main/java/com/syncro/storage/infrastructure/GarageHealthIndicator.java` -- NEW -- `@Component("garage")` head-bucket probe -- rationale: Epic 6-style dependency visibility.
- [x] `apps/backend/src/main/resources/application.yml` -- MODIFY -- `syncro.garage` block from env overrides -- rationale: production-safe binding.
- [x] `apps/backend/src/main/resources/application-local.yml` -- MODIFY -- localhost defaults for `syncro.garage` -- rationale: local dev without Docker env wiring.
- [x] Unit tests for S3 config, `ObjectStorageService`, and health indicator -- rationale: prove TTL, presign, and sanitized DOWN mapping.

**Infra:**
- [x] `syncro/infra/docker-compose.yml` -- MODIFY -- `garage` service + `garage_data` volume -- rationale: AR-006 stable name; durability across restart.
- [x] `syncro/infra/garage/garage.toml` -- NEW -- single-node config (env-driven secrets) -- rationale: reproducible local Garage with no committed secrets.
- [x] `syncro/.env.example` -- MODIFY -- add Garage variables (host/port/keys/bucket placeholders) -- rationale: documented wiring for implementers.

**Acceptance Criteria:**

- Given local infrastructure is managed through `infra/docker-compose.yml`, when the stack is started, then a `garage` service runs alongside the existing stable-name services (postgres, pgadmin, redis, influxdb, emqx, waha), with an S3-compatible endpoint on 3900, a named data volume, and `restart: unless-stopped`. [AC 8.1-1]
- Given the backend has `syncro.garage` properties set through environment/typed config, when it boots, then no Garage URL/credential is hardcoded in source and an S3 client + presigner bean are constructed from those properties. [AC 8.1-2]
- Given a configured endpoint + existing bucket, when `ObjectStorageService.store(key, bytes, contentType)` is called, then the object is uploaded and the returned path is bucket-relative (no bytes persisted to PostgreSQL). [AC 8.1-3]
- Given an existing object, when `ObjectStorageService.presignGetUrl(key)` is called, then a short-TTL presigned HTTPS GET URL is returned with expiry matching `syncro.garage.presign-ttl`. [AC 8.1-4]
- Given the `garage` container is restarted, when the stack is brought back up, then previously uploaded objects remain readable (persisted via the named volume). [AC 8.1-5]
- Given Garage is unreachable or unauthorized, when `/actuator/health` is queried, then the `garage` component reports DOWN with a sanitized reason code and the full error is never exposed in the public payload. [AC 8.1-6]
- Given the backend tests run, then the S3 config test, object-storage service test, and health indicator test pass without requiring a running Garage. [AC 8.1-7]

## Spec Change Log

- 2026-08-23: Spec created (draft → ready-for-dev). Epic 8 context compiled; codebase investigated (compose patterns, typed-properties records, health-indicator convention, pom deps); Garage v2.3.0 auto-bucket behavior and AWS SDK v2 pinned from primary sources.
- 2026-08-23: Implemented (ready-for-dev → review). Infra garage service + backend S3 client/presigner/ObjectStorageService/health indicator delivered; 13 unit tests green; live stack round-trip (upload/presign/restart) verified; live actuator health shows garage UP. Deviations recorded in Dev Agent Record (presignTtlSeconds record shape, ObjectStorageException addition, status-code-based auth classification, GARAGE_* pins across 18 full-context tests).

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 12: (high 1, medium 3, low 8)
- defer: 2: (medium 1, low 1)
- reject: 8
- addressed_findings:
  - `[high]` `[patch]` Storage client had unbounded HTTP timeouts (UrlConnectionHttpClient blocks indefinitely; only the health probe was bounded) — added `apiCallAttemptTimeout` 15s / `apiCallTimeout` 30s `ClientOverrideConfiguration` on the S3Client builder [GarageS3Config.java]
  - `[medium]` `[patch]` Garage admin API reachable by every compose-network container with no token — compose now passes required `GARAGE_ADMIN_TOKEN` (env override supported by Garage), verified container healthy with it [docker-compose.yml, .env.example]
  - `[medium]` `[patch]` Presign TTL accepted values beyond SigV4's 7-day cap — boot-green misconfig that failed every presign at runtime; compact-constructor now rejects 0/negative/>604800 with a clear message, unit-tested [GarageProperties.java, GarageS3ConfigTest.java]
  - `[medium]` `[patch]` Region drift trap (garage.toml pins s3_region while backend region looked freely configurable) — .env.example documents that GARAGE_REGION must match garage.toml [garage.toml is read-only source of truth; .env.example comments]
  - `[low]` `[patch]` Class javadoc claimed "no hardcoded credentials" while local profile ships dev fallbacks — reworded to state the actual contract [GarageProperties.java]
  - `[low]` `[patch]` Contract gaps a consumer falls into: contentType nullability unspecified, key grammar folklore — blank key/data/contentType now rejected without touching the client (unit-tested), bucket-relative key rules + never-persist-the-URL documented on the interface [ObjectStorageService.java, GarageObjectStorageService.java, GarageObjectStorageServiceTest.java]
  - `[low]` `[patch]` Secret-generation advice used non-CSPRNG `Get-Random` — replaced with openssl rand -hex 32 and .NET RandomNumberGenerator snippet [.env.example]
  - `[low]` `[patch]` headBucket 404 mapped to generic INVALID_REQUEST — now BUCKET_MISSING (config error, specific code) [GarageHealthIndicator.java]
  - `[low]` `[patch]` presignGetUrl logged INFO per call — hot path once 8.4 serves every image render; downgraded to DEBUG [GarageObjectStorageService.java]
  - `[low]` `[patch]` Misleading test name asserting absence of I/O it cannot prove, plus literals duplicating PROPERTIES values — removed/replaced with properties-derived assertions and a TTL-cap validation test [GarageS3ConfigTest.java]
  - `[low]` `[patch]` region annotated @NotNull letting blank through to raw Region.of("") failure — @NotBlank + explicit compact-constructor check [GarageProperties.java]
  - `[low]` `[patch]` Presigned-URL host reachability undocumented — .env.example warns GARAGE_HOST must resolve wherever presigned URLs are opened (browser/LAN), not just the JVM [.env.example]

Post-patch verification: 13/13 Garage tests green (`mvn test -Dtest=Garage*`), garage container recreated healthy with admin token enforced.

## Design Notes

- **Why AWS SDK v2 and not the MinIO client:** Garage is S3-compatible, so the S3 API is the contract. AWS SDK v2 (`software.amazon.awssdk:s3`) provides `S3Client` + `S3Presigner` out of the box and is more broadly maintained than a MinIO-specific client, which aligns with AR-004 (pin exact version). `url-connection-client` (instead of Apache/Netty) keeps the dependency small and avoids pulling the full HTTP stack into a cold-start path. Pin 2.46.7 (confirmed latest for `s3`) rather than relying on a BOM that does not manage it; AWS SDK `url-connection-client` is released at the same version.
- **Why `--single-node --default-bucket`:** The auto-credentials behavior landed in Garage v2.3.0, so the compose service can create a default access key + default bucket from env and skip manual layout/key/bucket bind steps. This keeps the local dev file honest and reproducible, and matches the compulsory `:?` env style used by every other service.
- **Health probe choice:** Head-bucket (list one bucket via `headBucket`) reflects real connectivity + auth without listing all objects. Reuse `DependencyHealthSupport.enrich` + `reasonCode` so no raw SDK message leaks to the public unauthenticated endpoint.
- **Why no resilience4j circuit breaker here:** Story 8.4 introduces the write/read path and is where a breaker earns its keep (mirroring WAHA). This story only proves connectivity + a deterministic presigner; adding it now would be speculative. SDK timeouts bound the probe so it cannot hang the health endpoint.
- **Why `ObjectStorageService` returns a path:** `presignGetUrl` must not be persisted (it expires), and `store` must return a stable key for the DB object reference in 8.4. Keeping the interface minimal (store + presign) is enough for 8.4 and avoids premature delete/replace semantics, which belong to the image story too.

## Verification

**Commands:**
- `apps/backend/mvnw.cmd test` (or `mvn -f apps/backend/pom.xml test`) -- expected: BUILD SUCCESS with all existing + new tests green (S3 config, storage service, health indicator). These do not require a running Garage.
- Docker: `docker compose -f syncro/infra/docker-compose.yml up -d garage` then `docker compose -f syncro/infra/docker-compose.yml ps garage` -- expected: `garage` healthy/running; confirm the S3 endpoint responds.

**Manual checks (if no CLI Docker):**
- Inspect `syncro/infra/garage/garage.toml` for no committed secrets (env-driven only).
- Confirm `docker-compose.yml` garage block has a named volume and `restart: unless-stopped`.
- Confirm `application.yml`/`application-local.yml` `syncro.garage` uses env overrides, not literals.

## Dev Agent Record

### Agent Model Used

ox-alpha (opencode/x-preview-f-free)

### Debug Log References

- `mvn compile` -- first failure: `S3Client.Builder` symbol not found; AWS SDK v2 exposes `S3ClientBuilder` as a top-level interface. Fixed import/type.
- `mvn test -Dtest=Garage*` -- first run failures: (1) `serviceConfiguration()` is not a public accessor on `S3Client`; (2) `apiCallTimeout()` lives on `RequestOverrideConfiguration`, must be called on the unwrapped config inside the Optional; (3) missing `GarageS3Config` import; then (4) package-private builder accessors not reachable from `com.syncro.storage.infrastructure` tests -> service test rewritten fully mock-based; (5) record with two constructors broke Spring binding ("No default constructor found") -> removed convenience constructor, single canonical constructor only; (6) 403 from Garage says "Access Denied" which keyword `reasonCode` cannot classify -> health indicator maps `AwsServiceException` status codes 401/403 to `UNAUTHORIZED` first.
- Full targeted regression: `PlantScopeRepositoryIntegrationTest...TelemetryValidationIntegrationTest` (17 modified full-context test classes + 3 new Garage classes): **173 tests green** (`Tests run: 80+93, Failures: 0`) plus 13 Garage unit tests.
- `SyncroBackendApplicationTests.contextLoads` fails with missing `SparepartAlertRepository` bean — verified **pre-existing at clean HEAD** via `git worktree add` at `1feb3b8` and running the identical test there (same error). Documented pre-existing environment category (spec-7-1 residual risks).
- Live stack verification: `docker compose up -d garage` -> container healthy (`/garage status` probe); `/garage bucket list` shows default bucket `syncro-spareparts`; real S3 round-trip via `amazon/aws-cli` container against `host.docker.internal:3900`: upload OK, `s3 presign --expires-in 300` OK, presigned GET fetched exact content (SigV4 signs Host header — URL only resolvable where the endpoint host resolves); restart via `docker compose restart garage` -> object still readable.
- Live backend verification: `mvn spring-boot:run` with `.env` loaded, local profile -> "Started SyncroBackendApplication in 21.034s"; `GET /actuator/health` returns overall DOWN (pre-existing: influxdb container not running) but **`components.garage.status=UP`, statusLabel=Up, statusSeverity=SUCCESS**. First boot attempt failed on port 8080 already in use (leftover process), unrelated to code.
- First boot also applied pending migrations V32-V36 to the local dev DB (pre-existing repo migrations; dev DB was behind). No migration files were added or modified by this story.

### Completion Notes List

- Implemented exactly the Code Map artifacts: `GarageProperties`, `GarageS3Config`, `ObjectStorageService` + `ObjectStorageException` (contract), `GarageObjectStorageService`, `GarageHealthIndicator`; compose service + volume; `garage.toml`; env wiring in both yml files and `.env.example`.
- Deviation (spec Code Map said `@NotNull Duration presignTtl`): implemented as `Long presignTtlSeconds` + derived `presignTtl()` accessor. Rationale: keeps the record a pure canonical-constructor record (two constructors break Spring Boot binding, found empirically) while preserving Duration semantics for callers; yml binds `presign-ttl-seconds`.
- Deviation: added `ObjectStorageException` (not in Code Map) as the contract-level checked boundary so Story 8.4 does not depend on SDK exception types.
- Deviation (improvement): `GarageHealthIndicator` classifies AWS `AwsServiceException` by HTTP status (401/403 -> UNAUTHORIZED, 404 -> INVALID_REQUEST) before falling back to keyword `reasonCode` — real Garage auth failures say "Access Denied", unclassifiable by keywords.
- Scope addition required by convention: 18 existing full-context test files each pin every external-service env var explicitly (`WAHA_API_KEY=test` style); base `application.yml` garage block therefore needs matching pins or every such context fails placeholder resolution. Added six `GARAGE_*` lines to each (17 env-style + `SyncroBackendApplicationTests` direct-property style).
- Local `.env` updated with generated random RPC secret + dev credentials (not committed values beyond local-dev placeholders consistent with existing entries).
- Smoke-test object deleted after verification; bucket left empty. Frontend untouched (no `apps/web` changes).

### Verification Performed

- AC 8.1-1 -> compose ps shows `syncro-spring-garage-1 Up (healthy)` alongside postgres/pgadmin/redis/influxdb/emqx/waha; S3 API published on `${GARAGE_S3_PORT}:3900`; named `garage_data` volume; `restart: unless-stopped`.
- AC 8.1-2 -> backend boots with `syncro.garage.*` bound from env/local profile defaults; grep of source shows no literal Garage URL/credential outside yml/env examples; beans constructed at startup (live boot).
- AC 8.1-3 -> `store` unit test asserts PutObjectRequest bucket/key/contentType + returned key; live aws-cli upload succeeded; no DB schema or byte persistence anywhere in diff.
- AC 8.1-4 -> unit tests assert TTL propagation (`Duration.ofSeconds(300)` into GetObjectPresignRequest) and `X-Amz-Expires=300` in URL; live presigned GET retrieved uploaded bytes.
- AC 8.1-5 -> `docker compose restart garage` then aws-cli download returned original content.
- AC 8.1-6 -> unit tests cover UP, CONNECTION_REFUSED, UNAUTHORIZED (403) with sanitized details (assertion that raw SDK message never appears); live health shows garage UP with contract fields.
- AC 8.1-7 -> all three new test classes pass without Garage running (13/13).

### Residual Risks

- `SyncroBackendApplicationTests.contextLoads` remains red (pre-existing alert-module bean gap, reproduced at clean HEAD).
- Presigned URLs are host-bound: a URL minted for `localhost:3900` will not validate if replayed through a different hostname (SigV4 Host header) — serving story (8.4) must mint URLs on the host clients use.
- Health endpoint overall stays DOWN while influxdb container is stopped locally; garage component independently UP.

### File List

- `syncro/apps/backend/pom.xml` -- MODIFIED -- added `software.amazon.awssdk:s3:2.46.7` + `url-connection-client:2.46.7`.
- `syncro/apps/backend/src/main/java/com/syncro/config/GarageProperties.java` -- NEW.
- `syncro/apps/backend/src/main/java/com/syncro/config/GarageS3Config.java` -- NEW.
- `syncro/apps/backend/src/main/java/com/syncro/storage/application/ObjectStorageService.java` -- NEW.
- `syncro/apps/backend/src/main/java/com/syncro/storage/application/ObjectStorageException.java` -- NEW.
- `syncro/apps/backend/src/main/java/com/syncro/storage/infrastructure/GarageObjectStorageService.java` -- NEW.
- `syncro/apps/backend/src/main/java/com/syncro/storage/infrastructure/GarageHealthIndicator.java` -- NEW.
- `syncro/apps/backend/src/main/resources/application.yml` / `application-local.yml` -- MODIFIED -- `syncro.garage` blocks.
- `syncro/apps/backend/src/test/java/com/syncro/config/GarageS3ConfigTest.java` -- NEW.
- `syncro/apps/backend/src/test/java/com/syncro/storage/infrastructure/GarageObjectStorageServiceTest.java` -- NEW.
- `syncro/apps/backend/src/test/java/com/syncro/storage/infrastructure/GarageHealthIndicatorTest.java` -- NEW.
- `syncro/apps/backend/src/test/java/com/syncro/**` (18 files) -- MODIFIED -- GARAGE_* property pins for full-context tests.
- `syncro/infra/docker-compose.yml` -- MODIFIED -- garage service + `garage_data` volume.
- `syncro/infra/garage/garage.toml` -- NEW.
- `syncro/.env.example` -- MODIFIED; `syncro/.env` -- MODIFIED (local, uncommitted values).
