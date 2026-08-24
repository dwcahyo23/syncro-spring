---
title: 'Manage Estimated Price Entries with Currency and Kurs'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: fcfd2c7
final_revision: 171b663
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** Procurement cost planning (FR-080/FR-081) has no price data: a sparepart carries no estimated price history, so replacement budgeting cannot account for currency changes or exchange-rate (kurs) movements, and there is no auditable trail of who entered which price when.

**Approach:** One Flyway migration creates an append-only `sparepart_price_entries` table (decimal amount, ISO-4217 currency defaulting to IDR, optional kurs-to-IDR snapshot, backend-derived normalized IDR amount, actor, timestamp) plus widens the audit entity-type CHECK with `SPAREPART_PRICE_ENTRY`. Two endpoints under `/api/v1`: `POST|GET /spareparts/{sparepartId}/price-entries` — append-only (no update/delete exists). Create gate order mirrors story 8-2: app-role (`requireMutationRole`) THEN `JobScopeService.requireLevelOrAbove(user, LEADER)` THEN sparepart-load + plant-access masking (404). Backend computes `idrAmount = amount × kursToIdr` (kurs forced 1 for IDR, scale 2 HALF_UP); every append writes an immutable audit row. Frontend regenerates Orval clients and adds `CurrencyPriceInput` + `PriceHistoryTable` shared components integrated into the sparepart edit dialog with a copy/reuse flow.

## Boundaries & Constraints

**Always:**
- Append-only contract: exactly one POST (create) and one GET (list) endpoint; service exposes only create/list; no update/delete mutator exists anywhere in the chain.
- Gate order on create: `requireMutationRole` (SUPER_ADMIN or MANAGE, else 403 `FORBIDDEN`) → `jobScopes.requireLevelOrAbove(user, "LEADER")` (else 403 `JOB_SCOPE_REQUIRED`, message naming LEADER-or-above) → load sparepart, non-SUPER_ADMIN must pass sparepart→machine→plant membership check (reuse patchProcurement semantics; out-of-plant masked 404 `SPAREPART_NOT_FOUND`). No mutation, no audit row on any denial.
- Money math is backend-owned: `idr_amount = amount × kursToIdr` with `BigDecimal`, scale 2 HALF_UP; never accepted from request body; never computed client-side.
- Currency rules: `[A-Z]{3}` ISO-4217 uppercase; omitted/null defaults to `IDR`; when currency is IDR, submitted `kursToIdr` is ignored and stored as `1`; when currency is non-IDR, `kursToIdr > 0` is mandatory (missing → 400 `VALIDATION_ERROR`, fieldError on kursToIdr).
- `entered_by` = actor user id; `entered_at` = `Instant.now(clock)` server-side UTC; both immutable after insert.
- Audit per successful append via `AuditLogWriter.record(user, …)`: `AuditAction.CREATE`, new `AuditEntityType.SPAREPART_PRICE_ENTRY`, entityId = entry id, entityLabel = sparepart code, plantId = sparepart's machine plant, previousValue = null, newValue = snapshot map (sparepartCode, amount, currency, kursToIdr, idrAmount, enteredAt).
- GET list requires authentication + plant access (same masking) but NOT job scope; returns entries ordered `enteredAt DESC` (plain JSON array — history is small and bounded; no pagination).
- DB owns integrity: positive CHECKs on amount/kurs/idr_amount, currency-format CHECK, FKs to `spareparts(id)` and `auth_users(id)`, index `(sparepart_id, entered_at DESC)`.
- Frontend follows existing idioms: controlled-props shared components in `components/syncro/`, plain controlled state (no RHF/Zod), static "Requires job scope LEADER or above." hint, backend denial surfaced verbatim, generated files touched only via `npm run generate:snapshot && npm run generate:api`.

**Block If:** Nothing requires human input. Pinned decisions: kurs forced to 1 for IDR (single source of truth for IDR math, removes null-vs-1 ambiguity in history display); distinct `SPAREPART_PRICE_ENTRY` audit entity type (V31 ALERT precedent); currency selector ships a hardcoded common-currency subset client-side while backend accepts any valid `[A-Z]{3}`.

**Never:**
- Never add PUT/PATCH/DELETE for price entries; never mutate or delete existing rows (append-only is the contract, enforced by absence of code paths).
- Never trust client-supplied `idrAmount`; never persist lowercase or non-ISO currency strings.
- Never allow VIEWER role or MANAGE-below-LEADER to create; never treat MANAGE app role alone as LEADER scope; SUPER_ADMIN bypasses job scope (documented, matches 8-2).
- Never hand-edit generated Orval files; never add React Hook Form/Zod; never introduce a currency-conversion service or external rates API (kurs is a manually entered snapshot).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH_IDR | LEADER+ MANAGE POSTs `{amount:1500000}` | 201; currency=`IDR`, kursToIdr=`1`, idrAmount=`1500000.00`; audit CREATE row; appears first in GET | No error |
| NON_IDR_WITH_KURS | POST `{amount:1000, currency:"USD", kursToIdr:15500}` | 201; idrAmount=`15500000.00` persisted | No error |
| NON_IDR_NO_KURS | POST `{amount:1000, currency:"USD"}` (kursToIdr null) | No save, no audit | 400 `VALIDATION_ERROR`, fieldErrors.kursToIdr |
| IDR_WITH_KURS_SUBMITTED | POST `{amount:1500000, currency:"IDR", kursToIdr:99}` | Stored kursToIdr=`1` (submitted ignored) | No error |
| BAD_CURRENCY | currency `"usd"` / `"US"` / `"USDX"` | No save | 400 `VALIDATION_ERROR` |
| NON_POSITIVE_VALUES | amount 0/-5, or kursToIdr 0/-1 | No save | 400 `VALIDATION_ERROR` |
| BELOW_LEADER | MANAGE user with no ≥LEADER responsibility row | No mutation, no audit | 403 `JOB_SCOPE_REQUIRED` naming LEADER+ |
| VIEWER_ROLE | Any POST | No mutation | 403 `FORBIDDEN` (app-role gate first) |
| SUPER_ADMIN_NO_ROWS | SUPER_ADMIN without responsibility rows | Allowed (documented bypass) | No error |
| WRONG_PLANT | MANAGE+LEADER on sparepart outside assigned plants | Masked as unknown | 404 `SPAREPART_NOT_FOUND` |
| UNKNOWN_SPAREPART | POST/GET nonexistent sparepartId | Standard | 404 `SPAREPART_NOT_FOUND` |
| EMPTY_HISTORY | GET on sparepart with zero entries | 200 `[]` | No error |
| HISTORY_ORDER | Multiple entries exist | GET returns newest-first by enteredAt DESC | No error |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main`):**
- `resources/db/migration/V38__create_sparepart_price_entries.sql` -- NEW -- table (`id`, `sparepart_id`, `amount NUMERIC(18,2)`, `currency CHAR(3)` DEFAULT `'IDR'`, `kurs_to_idr NUMERIC(18,6)`, `idr_amount NUMERIC(18,2)`, `entered_by UUID`, `entered_at TIMESTAMPTZ`, `version BIGINT`) + `ck_…_amount_positive`/`ck_…_kurs_positive`/`ck_…_idr_amount_positive`/`ck_…_currency_format` CHECKs + FKs to spareparts/auth_users + `idx_sparepart_price_entries_sparepart_entered_at`; second statement drops/re-adds `ck_audit_log_entity_type` adding `'SPAREPART_PRICE_ENTRY'` (exact V31 idiom).
- `java/com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- add `SPAREPART_PRICE_ENTRY`.
- `sparepart/infrastructure/SparepartPriceEntryEntity.java` -- NEW -- child-rows pattern cloned from `MachineSparepartInstallationEntity`: assigned UUID id, `@ManyToOne(LAZY, optional=false)` → `SparepartEntity`, `@ManyToOne(LAZY, optional=false)` → `AuthUserEntity` (entered_by; sparepart→machine entity-import precedent covers sparepart→auth), `@Version`, protected no-arg ctor, full-arg ctor, getters only (immutable — no update mutator).
- `sparepart/infrastructure/SparepartPriceEntryRepository.java` -- NEW -- `findAllBySparepartIdOrderByEnteredAtDesc(UUID)`.
- `sparepart/application/SparepartPriceEntryService.java` -- NEW -- deps mirror patchProcurement (`SparepartRepository`, `AuthUserPlantAssignmentRepository`, `AuditLogWriter`, `Clock`, `JobScopeService`); `create(user, sparepartId, PriceEntryCommand)`: gate order → find+plant-mask → normalize (default IDR, force kurs 1 for IDR, require kurs for non-IDR, compute idrAmount scale 2 HALF_UP) → save → audit; `list(user, sparepartId)` read-scoped; nested exception classes (Validation/NotFound/MutationForbidden) mirroring service-local style.
- `sparepart/api/SparepartPriceEntryDtos.java` -- NEW -- `SparepartPriceEntryRequest(@NotNull @Positive @Digits(integer=16, fraction=2) BigDecimal amount, @Pattern(regexp="[A-Z]{3}") String currency /*nullable*/, @Positive @Digits(integer=12, fraction=6) BigDecimal kursToIdr /*nullable*/)`; `SparepartPriceEntryView(id, sparepartId, amount, currency, kursToIdr, idrAmount, enteredBy, enteredByName, enteredAt)` (enteredByName = actor login identifier, mirroring audit_log.actor_name semantics).
- `sparepart/api/SparepartPriceEntryController.java` -- NEW -- `@RequestMapping("/api/v1/spareparts/{sparepartId}/price-entries")`; POST → 201 + Location; GET → 200 array; full `@Operation`/`@ApiResponses` with `content = @Content` on every error response (8-2 review lesson: wrong OpenAPI error schema breaks generated client types).
- `sparepart/api/SparepartPriceEntryExceptionHandler.java` -- NEW -- `@RestControllerAdvice(assignableTypes = …)`: 400 `VALIDATION_ERROR`, 403 `FORBIDDEN`/`JOB_SCOPE_REQUIRED`, 404 `SPAREPART_NOT_FOUND`; stable error shape via existing `error()` idiom.

**Backend tests:**
- `src/test/java/com/syncro/sparepart/application/SparepartPriceEntryServiceIntegrationTest.java` -- NEW -- Testcontainers PostgreSQL; reuse `persistedUser`/`assign`(plant)/`assignJobScope`(LEADER)/`latestAuditEntryFor` helper patterns from `SparepartServiceIntegrationTest`; cover every matrix row incl. audit assertions and no-update-path regression.
- `src/test/java/com/syncro/sparepart/api/SparepartPriceEntryControllerTest.java` -- NEW -- `@WebMvcTest` + `@Import(SecurityConfig, …Handler, JwtAuthenticationFilter, TimeConfig, TestJsonConfig)`; assert status codes + `$.code` per matrix.

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE via `npm run generate:snapshot` (backend running) + `npm run generate:api` -- typed `useCreateSparepartPriceEntries`/`useListSparepartPriceEntries` hooks exist; force-add orphaned new model files if gitignored (known repo fragility).
- `components/syncro/currency-price-input.tsx` -- NEW -- controlled component (props: `value: {amount, currency, kursToIdr}`, `onChange`, `errors?: {amount?, currency?, kursToIdr?}`, `readOnly`): amount `<Input>` + currency shadcn `<Select>` over hardcoded subset const (`IDR, USD, EUR, SGD, MYR, JPY`) + kurs `<Input>` rendered iff currency ≠ IDR (LeadTimeInput flex layout idiom).
- `components/syncro/price-history-table.tsx` -- NEW -- shadcn `<Table>`: columns Amount (original currency, Intl.NumberFormat), Kurs, IDR Value, Entered By, Entered At (inline `Intl.DateTimeFormat` helper per house style), Reuse action button per row calling `onReuse(entry)`; empty-state text.
- `features/master-data/spareparts/sparepart-management.tsx` -- MODIFY -- edit dialog gains Price History section (edit mode only, below Procurement): `CurrencyPriceInput` + append submit (mutation hook, invalidates list query key) + `PriceHistoryTable`; reuse pre-fills draft state from row values (unchanged-price flow); static LEADER+ hint; denial/error toasts surface backend `code`/`message` verbatim via existing `errorResponse()` helper; loading/empty states for history fetch.
- Tests: `components/syncro/currency-price-input.test.tsx` + `components/syncro/price-history-table.test.tsx` -- NEW; `features/master-data/spareparts/sparepart-management.test.tsx` -- EXTEND using module-level mutable mock + `importOriginal` clone pattern.

## Tasks & Acceptance

**Execution:**
- [x] `V38__create_sparepart_price_entries.sql` + `AuditEntityType` widening -- schema owns integrity; audit enum matches CHECK.
- [x] `SparepartPriceEntryEntity` + repository -- persistence layer, append-only shape.
- [x] `SparepartPriceEntryService` create/list with pinned gate order, normalization, IDR math, audit -- the use case.
- [x] DTOs + controller + exception handler -- API surface with correct OpenAPI error schemas.
- [x] Backend tests (integration + WebMvc) per matrix -- prove every row incl. denials and audit.
- [x] Orval regeneration (boot backend, run both scripts; force-add orphaned generated models) -- typed hooks.
- [x] `CurrencyPriceInput` + `PriceHistoryTable` + dialog integration + frontend tests -- UI delivery with copy/reuse flow.
- [x] Verify: Maven suite green; `npm run test:unit` + Biome green; live-stack curl evidence (happy/denial/non-IDR-no-kurs) + psql audit inspection.

**Acceptance Criteria:**

- Given a sparepart exists, when a LEADER+-scoped MANAGE user creates a price entry, then the entry persists amount, currency defaulting to IDR, entered-by actor and entered-at timestamp, and renders first in the history list. [AC 8.3-1]
- Given a non-IDR currency, when kursToIdr is absent, then save fails with 400 `VALIDATION_ERROR`; when present, then backend-persisted `idrAmount` equals `amount × kursToIdr` at scale 2 and survives round-trip in list output. [AC 8.3-2]
- Given currency IDR, when any kursToIdr is submitted, then stored kurs is 1 and idrAmount equals amount. [AC 8.3-3]
- Given a user below LEADER job scope, when creating an entry, then server responds 403 `JOB_SCOPE_REQUIRED` explaining LEADER-or-above, with no mutation and no audit row; reads remain available to plant-scoped users without job scope. [AC 8.3-4]
- Given any successful append, when inspected, then the immutable audit log contains actor, action CREATE, entity type `SPAREPART_PRICE_ENTRY`, sparepart label, plant, and full new-value snapshot. [AC 8.3-5]
- Given history exists, when the dialog is opened, then PriceHistoryTable shows original currency, kurs, IDR value, actor, and timestamp per row, and the reuse action copies that row's values into the new-entry form producing a separate appended entry (original untouched). [AC 8.3-6]

## Spec Change Log

- 2026-08-24: Spec created (draft → ready-for-dev). Epic 8 context loaded (valid cache); continuity from spec-8-2 (done); codebase investigated via subagents (sparepart module incl. installation child-row pattern, audit infra V16/V31, JobScopeService, DTO/controller/exception idioms, migrations through V37, frontend dialog/components/Orval/test conventions). Decisions pinned: kurs forced 1 for IDR; distinct SPAREPART_PRICE_ENTRY audit entity type with CHECK widening; append-only POST|GET only; plain-array history response; hardcoded client currency subset vs permissive backend [A-Z]{3}.
- 2026-08-24: Implemented (ready-for-dev → review). V38 + enum widening; child-entity/repo/service per Code Map; POST|GET endpoints with @Content on error responses; 25 new backend tests (15 SVC + 10 API); Orval regenerated (hooks useCreateSparepartPriceEntries/useListSparepartPriceEntries verified); CurrencyPriceInput + PriceHistoryTable + edit-dialog integration + 11 new frontend tests; targeted backend suite 42/42 green, full Sparepart* suite green except 2 PRE-EXISTING errors in SparepartLifetimeEvaluatorTest (unmodified file, fails standalone at baseline); frontend 220 tests green, tsc exit 0. One pre-existing 8-2 frontend test repaired at baseline ("surfaces job-scope denial" failed because denial was toast-only and dialog auto-closed — fixed to persist inline formError and keep dialog open; assertion strengthened). Live-stack evidence recorded: admin login → IDR happy 201 (kurs forced 1), USD-without-kurs 400 VALIDATION_ERROR fieldErrors.kursToIdr, USD+kurs 201 idrAmount=15500000.00, list newest-first; psql shows table DDL per spec + audit_log CREATE rows (entity_type SPAREPART_PRICE_ENTRY, previous NULL, full snapshot, plant set).

## Review Triage Log

### 2026-08-24 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 0
- defer: 1: (low 1)
- reject: 0
- addressed_findings:
  - none

## Design Notes

- **Why a standalone child table/entity (no `@OneToMany`):** the codebase has zero JPA collection mappings; every parent-child relation (installations, responsibilities, notification attempts) is its own entity + repository + service + controller with `@ManyToOne` back-reference. Price entries follow that exact shape, keeping append-only natural (nothing can cascade-delete history except sparepart deletion, which FK-restores via restrict).
- **Why kurs is forced to 1 for IDR instead of null:** `idrAmount` math and history rendering get a single invariant (`idr = amount × kurs` always holds); avoids null-check branches and ambiguous "was kurs forgotten?" display questions.
- **Why a distinct audit entity type:** V31 added `ALERT` to the same CHECK when alerts needed their own trail; procurement price history deserves the same discoverability, and `entity_type` index keeps filtering cheap.
- **Continuity from 8-2:** reuses `JobScopeService` (port-in-auth already built), plant-masking semantics, `errorResponse()` frontend helper, and the PUT-dialog patterns; residual risk note there ("revisit resource scoping when 8.3 adds price entries") stands resolved-as-coarse: job-scope breadth stays global per pinned contract.
- **Plain-array GET response** (not paged wrapper): sub-resource histories are small; paging machinery adds contract surface with no operator need today.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=SparepartPriceEntry*,JobScope*,AuditLogWiringIntegrationTest"` -- expected: BUILD SUCCESS (uniqueness-free path, denials, audit proven on real PostgreSQL via Testcontainers; V38 applies cleanly from empty and prior state).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: price-entry hooks generated; unit tests green.
- `npx biome check src/components/syncro/currency-price-input.tsx src/components/syncro/price-history-table.tsx src/features/master-data/spareparts/sparepart-management.tsx` -- expected: no new diagnostics beyond known pre-existing ones.

**Manual checks:**
- Boot stack; curl POST happy path (IDR + USD-with-kurs), below-leader denial, USD-without-kurs 400 with JWT; verify 201/403/400 bodies match matrix.
- psql: `\d sparepart_price_entries` shows columns/constraints/index; after POST, `audit_log` row has `entity_type='SPAREPART_PRICE_ENTRY'` with previous NULL and full new-value JSON.

## Dev Agent Record

### Agent Model Used

ox-alpha (opencode/x-preview-f-free)

### Debug Log References

- Backend targeted suite first run green (42/42). Full `Sparepart*` suite surfaced 2 errors in `SparepartLifetimeEvaluatorTest` (Mockito UnnecessaryStubbingException) — proven pre-existing at baseline (file untouched by story; fails standalone; zero overlap with price-entry code).
- Frontend baseline check revealed one pre-existing 8-2 test failing at HEAD ("surfaces job-scope denial"): denial was toast-only and dialog auto-closed, violating the no-toast-only-feedback rule. Fixed by persisting composed message inline via setFormError and keeping the dialog open; assertion strengthened to match the persisted message.
- Orval regen also touched unrelated generated model files (pageableObject.ts, entity-type enums) — normal regeneration output.

### Completion Notes List

- Implemented exactly per Code Map: V38 migration (table + 4 CHECKs + 2 FKs + composite index + audit entity-type widening); AuditEntityType.SPAREPART_PRICE_ENTRY; append-only entity (getters only) + repository; service with pinned gate order (role → job scope → plant-masked load), normalization (IDR default + kurs forced 1, non-IDR requires positive kurs), idrAmount scale-2 HALF_UP, audit CREATE rows; DTOs/controller/exception-handler with @Content on all error responses.
- Live verification via local stack (admin@syncro.dev JWT): IDR happy 201 (kursToIdr=1, idrAmount=1500000.00, enteredByName echoed), USD without kurs 400 VALIDATION_ERROR fieldErrors.kursToIdr, USD+kurs 201 idrAmount=15500000.00, GET list newest-first.
- psql evidence: `\d sparepart_price_entries` matches spec DDL exactly (constraints/FKs RESTRICT/index); audit_log rows show CREATE / SPAREPART_PRICE_ENTRY / label=sparepart code / plant set / previous NULL / full new-value JSON.
- The two live-created entries remain on pilot sparepart BF-08410GM1ELEPLCWEC000 as demo data (append-only — no delete path by design).

### Verification Performed

- AC 8.3-1 → 8.3-SVC-001 (+ SVC-013 ordering, API-001) + live IDR happy 201 echo.
- AC 8.3-2 → 8.3-SVC-002/003 + API-002/003 + live 400 VALIDATION_ERROR fieldErrors.kursToIdr and live USD+kurs idrAmount=15500000.00 round-trip.
- AC 8.3-3 → 8.3-SVC-004/015 + live IDR kursToIdr=1 regardless of submitted value.
- AC 8.3-4 → 8.3-SVC-007/008/012 + API-004/005 (JOB_SCOPE_REQUIRED naming LEADER+, no mutation/audit; reads scope-free on real PostgreSQL). Live denial not re-run: SUPER_ADMIN bypasses job scope by contract; integration tests prove denial on real DB.
- AC 8.3-5 → 8.3-SVC-014 + live psql audit_log inspection (previous NULL, full snapshot, actor_name).
- AC 8.3-6 → price-history-table tests ×4 + feature tests (rows render, Reuse copies into form producing separate append, appends once).
- Commands: `mvn test -Dtest="SparepartPriceEntry*,JobScope*,AuditLogWiringIntegrationTest"` → 42/42 green (Flyway "now at version v38"); `mvn test -Dtest="Sparepart*"` → green except documented pre-existing errors; `npm run test:unit` → 22 files / 220 tests passed; `tsc --noEmit` exit 0; biome clean on new files.

### Residual Risks

- Below-leader denial verified by Testcontainers integration tests rather than live curl (SUPER_ADMIN-only credentials available locally); behavior identical code path.
- Pre-existing SparepartLifetimeEvaluatorTest strict-stub errors remain red on main — out of story scope, flagged for deferred work if not already tracked.
- Job-scope breadth stays global ("any LEADER+ row anywhere") per pinned 8-2 contract; resource-scoped ABAC remains future work.

### File List

- syncro/apps/backend/src/main/resources/db/migration/V38__create_sparepart_price_entries.sql - NEW.
- syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java - MODIFIED.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartPriceEntryEntity.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartPriceEntryRepository.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartPriceEntryService.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartPriceEntryDtos.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartPriceEntryController.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartPriceEntryExceptionHandler.java - NEW.
- Backend tests: SparepartPriceEntryServiceIntegrationTest NEW (15), SparepartPriceEntryControllerTest NEW (10).
- Frontend: openapi.json + src/lib/api/generated/** REGENERATED; components/syncro/currency-price-input.tsx + price-history-table.tsx NEW (+2 test files); features/master-data/spareparts/sparepart-management.tsx MODIFIED (+test file EXTENDED incl. one repaired pre-existing 8-2 case).

## Auto Run Result

Status: done

### Summary

Story 8.3 delivered append-only estimated price entries per sparepart: V38 migration (`sparepart_price_entries` with amount NUMERIC(18,2), CHAR(3) currency default IDR, kurs_to_idr NUMERIC(18,6), backend-derived idr_amount NUMERIC(18,2), entered_by/entered_at/version, positive+currency-format CHECKs, RESTRICT FKs, composite index) plus audit entity-type CHECK widening with `SPAREPART_PRICE_ENTRY`. Backend: append-only entity/repository/service (`POST|GET /api/v1/spareparts/{sparepartId}/price-entries`) with the pinned gate order (app-role → LEADER-or-above job scope → plant-masked 404), kurs forced to 1 for IDR, mandatory positive kurs for non-IDR, idrAmount = amount × kurs scale 2 HALF_UP, and immutable CREATE audit rows. Frontend: regenerated Orval client, `CurrencyPriceInput` + `PriceHistoryTable` shared components, and edit-dialog Price History section with copy/reuse flow.

### Files changed

- V38 migration; AuditEntityType + SPAREPART_PRICE_ENTRY; SparepartPriceEntryEntity/Repository/Service/Dtos/Controller/ExceptionHandler (backend).
- Backend tests: SparepartPriceEntryServiceIntegrationTest (15 SVC cases) + SparepartPriceEntryControllerTest (10 API cases).
- Frontend: regenerated generated client (37 paths, useCreate/useListSparepartPriceEntries hooks); currency-price-input.tsx + price-history-table.tsx (+2 test files); sparepart-management.tsx edit-dialog integration (+test file extended, incl. one repaired pre-existing 8-2 denial test).

### Review findings breakdown

0 intent_gap, 0 bad_spec, 0 patch, 1 defer (low), 0 reject. Deferred: pre-existing `SparepartLifetimeEvaluatorTest` 2 Mockito strict-stub errors at baseline (unrelated to 8-3) → appended to deferred-work.md.

### Follow-up review recommendation

false — final review pass made no review-driven code changes (0 patches, no loopback); only a low-severity pre-existing issue was deferred.

### Verification performed

Backend: `mvn test -Dtest="SparepartPriceEntry*,JobScope*,AuditLogWiringIntegrationTest"` → 42/42 green (Testcontainers real PostgreSQL, Flyway now at v38). `mvn test -Dtest="Sparepart*"` → green except 2 documented pre-existing SparepartLifetimeEvaluatorTest errors. Frontend: `npm run test:unit` → 22 files / 220 tests passed; `tsc --noEmit` exit 0; biome clean on new files. Live: admin@syncro.dev JWT → IDR happy 201 (kurs forced 1), USD-without-kurs 400 VALIDATION_ERROR fieldErrors.kursToIdr, USD+kurs 201 idrAmount=15500000.00, list newest-first; psql confirmed table DDL per spec + audit_log CREATE rows (entity_type SPAREPART_PRICE_ENTRY, previous NULL, full snapshot, plant set).

### Residual risks

Below-leader denial proven via Testcontainers rather than live curl (SUPER_ADMIN-only local creds); same code path. Pre-existing SparepartLifetimeEvaluatorTest red deferred. Job-scope breadth remains global per pinned 8-2 contract; resource-scoped ABAC is future work.
