---
title: 'Role Taxonomy Migration'
type: 'feature'
created: '2026-08-25'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
final_revision: c7e3874
baseline_revision: 0adfc92
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-3-opa-infrastructure.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Phase 1's three-value `application_role` model (SUPER_ADMIN/MANAGE/VIEWER) cannot express the PRD maintenance taxonomy; OPA policies and future epics need the real role names, and Epic 9.3's `subject.roles` currently carries placeholder strings.

**Approach:** One coherent mechanical swap to the PRD's ten roles — V45 migrates stored values (MANAGE→MANAGER_MAINTENANCE, VIEWER→AUDITOR) then widens the CHECK; the Java enum is replaced wholesale and every gate/test/frontend reference renames in the same change set (alias coexistence is explicitly rejected: post-migration a stale `!= MANAGE` check would silently always-pass). JWT parsing of legacy tokens fails closed to 401. Pilot seed rows adopt their identity-matched taxonomy roles.

## Boundaries & Constraints

**Always:**
- V45__role_taxonomy_migration.sql order is fixed: `UPDATE … SET application_role='MANAGER_MAINTENANCE' WHERE ='MANAGE'`; `UPDATE … ='AUDITOR' WHERE ='VIEWER'` (pilot technician/staff/leader rows move to TECHNICIAN / STAFF_MAINTENANCE / SECTION_LEADER respectively); then drop `ck_auth_users_application_role` and re-add it accepting EXACTLY the ten PRD roles (`SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE, TECHNICIAN, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER, AUDITOR`). No column/table changes; additive-in-spirit (values migrated, none dropped).
- `ApplicationRole` becomes exactly those ten constants. The twelve `… || ApplicationRole.MANAGE` mutation gates (Plant, MachineGroup, Machine, MachineResponsibility, Section, Team, ShiftConfig, Sparepart×4, Installation services) become `MANAGER_MAINTENANCE` with identical semantics; WahaTemplateService's former `VIEWER` deny-block becomes the uniform `SUPER_ADMIN || MANAGER_MAINTENANCE` allow-list (see Review Triage DW-131: the legacy deny-list was an artefact of the three-role world and would have silently granted template mutation to seven new identity roles); every SUPER_ADMIN-only path untouched.
- Mapping is documented once on the enum javadoc: `MANAGE→MANAGER_MAINTENANCE`, `VIEWER→AUDITOR`, plus the AD-15 caveat that MANAGER_MAINTENANCE does NOT imply global scope (plant assignments still bind via AD-2).
- `JwtTokenService.parse` wraps unknown-role `IllegalArgumentException` into the existing `InvalidTokenException` so pre-deploy tokens get a clean 401 (re-login) instead of a 500.
- `PolicyDecisionPoint` needs NO code change — `subject.roles[0]` automatically emits extended names; rego `SUPER_ADMIN` rule untouched; `allowed-actions` unchanged. PRODUCTION_LEADER scope stays plant-assignment-derived (no production-line binding table in this story).
- OpenAPI snapshot `AuthUserView.applicationRole` enum widened to the ten names, then orval regeneration (`generate:api`) refreshes `authUserViewApplicationRole.ts`; `.env.example`/compose untouched; degraded-allowlist/enforced-paths untouched.
- PilotSeedTest assertions follow the seed mapping (technician→TECHNICIAN, staff→STAFF_MAINTENANCE, leader→SECTION_LEADER).

**Block If:** Nothing requires human input. Pinned: big-bang rename over alias period (rationale above); VIEWER→AUDITOR is THE read-only mapping (only read-only role in the taxonomy); MAINTENANCE_LEADER/INVENTORY_MAINTENANCE/STOREKEEPER/PRODUCTION_LEADER exist in enum+CHECK but have NO behavioral gates yet (their powers arrive with Epics 10–14 endpoints); no new endpoints; audit_log entity_type/action enums untouched.

**Never:**
- Never keep `MANAGE`/`VIEWER` as enum constants, DB values, or accepted JWT claims after this story.
- Never change what any gate allows/denies — pure renames plus documented mapping (a MANAGE user before ≡ a MANAGER_MAINTENANCE user after).
- Never touch ResponsibilityLevel (its MANAGER/LEADER values are job levels, unrelated), JobScopeService semantics, OPA bundle rules, teams/sections business logic, or WAHA templates content.
- Never hand-edit generated client files; never renumber existing migrations.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| RENAME_MANAGE_ROW | Row with `MANAGE` pre-V45 | Post-migration value `MANAGER_MAINTENANCE`; login + all prior capabilities identical | No error |
| RENAME_VIEWER_ROW | Row with `VIEWER` | Becomes `AUDITOR`; read-only behavior preserved everywhere | No error |
| CHECK_WIDENS | INSERT each of the ten roles | All accepted | Legacy string insert now violates CHECK |
| PILOT_SEED | Pilot seed applied from scratch | Three users carry TECHNICIAN / STAFF_MAINTENANCE / SECTION_LEADER | No error |
| JWT_LEGACY | Token claim `"role":"MANAGE"` against new enum | Parse raises InvalidTokenException → 401 AUTHENTICATION_REQUIRED-style rejection (re-login), never 500 | Clean failure |
| GATE_PARITY | MANAGER_MAINTENANCE hits any former-MANAGE mutation | Allowed exactly as before; AUDITOR denied exactly where VIEWER was | FORBIDDEN shapes unchanged |
| PDP_ROLES | Any authenticated evaluate()/actions call | `subject.roles[0]` equals the extended enum name; scope arrays unchanged from derive | No error |
| FRONTEND_ENUM | Fresh orval client | `AuthUserViewApplicationRole` exposes ten keys without MANAGE/VIEWER; sidebar/RoleGuard/canMutate logic compiles and behaves identically under renamed roles | tsc green |

</intent-contract>

## Code Map

**Migration + domain:**
- `syncro/apps/backend/src/main/resources/db/migration/V45__role_taxonomy_migration.sql` -- NEW -- UPDATE×2 + CHECK drop/add (ten values).
- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- MODIFY -- three application_role values to identity-matched taxonomy roles.
- `com/syncro/auth/domain/ApplicationRole.java` -- REPLACE -- ten constants + mapping javadoc.
- `com/syncro/auth/application/JwtTokenService.java` -- MODIFY -- unknown-role → InvalidTokenException.

**Gate renames (pure `MANAGE`→`MANAGER_MAINTENANCE`, `VIEWER`→`AUDITOR`):**
- masterdata: `PlantService`(auto-grant L78 + gate), `MachineGroupService`; machine: `MachineService`, `MachineResponsibilityService`; org: `SectionService`, `TeamService`(+javadoc 9.4 note resolved); shiftconfig: `ShiftConfigService`; sparepart: `SparepartService`, `SparepartTaxonomyService`, `SparepartPriceEntryService`, `SparepartImageService`, `MachineSparepartInstallationService`; notification: `WahaTemplateService`.

**Tests (mechanical):** every `ApplicationRole.MANAGE|VIEWER` fixture across ~68 listed files (distilled inventory: TeamServiceIntegrationTest 35, SparepartServiceIntegrationTest 40, MachineServiceIntegrationTest 41, ShiftConfig* 47, MachineGroup*/PlantController 49, sparepart api/integration ~150 total hits) + `PilotSeedTest` ("VIEWER"→three mapped values) + DisplayNames asserting VIEWER/MANAGE wording.

**Frontend (`syncro/apps/web/src`):**
- `navigation/sidebar/sidebar-items.ts` (roles union + two entries), `components/syncro/nav-user.tsx` (roleLabels), `lib/auth/auth-session.ts`, `lib/auth/use-auth-user.ts` (APPLICATION_ROLES whitelist), `filter-sidebar-items.ts`.
- Route guards `app/(main)/dashboard/**/page.tsx` + `features/waha-templates/waha-template-page-content.tsx`: arrays swap names.
- Feature consumers: the ten `canMutate` sites (`"MANAGE"`→`"MANAGER_MAINTENANCE"`), responsibilities copy+isViewer, machine-hub shift-section, waha readOnly.
- `openapi.json` AuthUserView enum + regenerated `src/lib/api/generated/**` (orval).
- Test stubs: sections/teams/machine-groups/responsibilities/machine-hub/system-health *.test.tsx mock roles renamed; responsibilities literal-copy assertion updated.

## Tasks & Acceptance

**Execution:**
- [x] `V45__role_taxonomy_migration.sql` + pilot-seed.sql + PilotSeedTest -- value migration + ten-role CHECK.
- [x] `ApplicationRole` + `JwtTokenService` -- ten-role enum, mapping javadoc, fail-closed parse.
- [x] Twelve service gate renames + WahaTemplateService -- semantics-preserving swap (WAHA: uniform allow-list).
- [x] Backend test sweep -- compile-clean rename across fixtures/DisplayNames; PilotSeedTest remap.
- [x] `openapi.json` enum widen + `npm run generate:api` -- contract + client refreshed.
- [x] Frontend sweep -- sidebar/guards/consumers/tests renamed; `tsc` + `vitest` green.
- [x] Migration evidence test `db/RoleTaxonomyMigrationTest.java` -- rename conversions, ten-value accept, legacy reject, pilot rows.
- [x] Verify: targeted Maven batch green (incl. one Testcontainers class exercising V45 from prior state) + web checks.

**Acceptance Criteria:**
- Given pre-V45 rows carrying MANAGE/VIEWER, when V45 runs, then values read MANAGER_MAINTENANCE/AUDITOR and inserting any legacy string afterwards violates `ck_auth_users_application_role`. [AD-15]
- Given a user whose role was MANAGE, when they authenticate and hit any previously-permitted mutation, then outcomes are byte-identical to pre-migration behavior (parity harness = existing suites passing unmodified except identifiers). [FR-003]
- Given a token minted before deployment, when parsed, then authentication fails with the standard 401 shape rather than an error page. [robustness]
- Given OPA evaluation for any user, when input is assembled, then `subject.roles[0]` carries the extended taxonomy name while scope arrays remain exactly `derive()` output. [AD-15]
- Given the regenerated frontend contract, when any guarded route renders, then visibility/mutation affordances match pre-migration behavior under renamed roles. [NFR-P2-3]

## Spec Change Log

- (step-04) WAHA gate: deny-list `!= AUDITOR` rejected in review (would grant template mutation to seven new identity roles) in favour of the uniform `SUPER_ADMIN || MANAGER_MAINTENANCE` allow-list; boundary text updated. Documented as DW-131 in triage.
- (step-04) V45 statement order doc-corrected to DROP→UPDATE→ADD (DB CHECK evaluates per row at UPDATE time; old constraint rejects new values). Implementation already correct.

## Review Triage Log

- **R1 (step-04, Blind Hunter)**: P0 — V45 UPDATEs run under the old CHECK, aborted on any existing MANAGE/VIEWER row. -> PATCHED (reordered to DROP→UPDATE→ADD) + regression test 9.4-DB-005 added.
- **R2 (step-04, Blind Hunter)**: P0 — `syncro-api.ts` `isAuthUser` whitelist still three roles; TECHNICIAN/STAFF_MAINTENANCE/SECTION_LEADER pilot logins rejected. -> PATCHED (ten-role whitelist).
- **R3 (step-04, Blind Hunter)**: P1 — WAHA template gate deny-list widened to seven identity roles vs uniform allow-list. -> PATCHED to `SUPER_ADMIN || MANAGER_MAINTENANCE` allow-list. Status: **deferred as DW-131** (documented deviation from literal mapping; deliberate).
- **R4 (step-04, Blind Hunter)**: P2 — stale VIEWER naming in section-management.test.tsx description/comment. -> PATCHED.
- **R5 (step-04, Edge Case Hunter)**: P1 — V45 `DROP CONSTRAINT` not idempotent; 9.4-DB-005 re-execution errors. -> PATCHED (`DROP CONSTRAINT IF EXISTS`).
- **R6 (step-04, Edge Case Hunter)**: P2 — WAHA allow-list denies seven identity roles that deny-list would have allowed (TECHNICIAN pilot user gets 403 on template PUT). Accepted as intended (fail-closed, uniform pattern); folded into DW-131. No change.
- Verified handled (no finding): `@Enumerated(STRING)` entity mapping; JWT null/missing-role path; PDP `name()` assembly + rego; no role `switch` anywhere; NULL role (NOT NULL); constraint name/order; Flyway single-apply; frontend fail-closed role validation.

## Design Notes

- Why big-bang: with data migrated but code aliased, `role != MANAGE` guards flip to always-true/false SILENTLY (security regression invisible to tests that don't exercise both spellings). One atomic commit keeps "rename ⇒ zero semantic delta" provable by running the entire existing suite with only identifier substitutions.
- Golden example: `budi` (PRODUCTION_LEADER) logs in post-9-4: JWT role claim `PRODUCTION_LEADER`; dashboards render (read paths never gated); workorder creation arrives in Epic 10 gated by OPA using exactly this subject.roles entry.
- LocalAdmin bootstrap stays SUPER_ADMIN; LocalAdminProperties untouched.

## Verification

**Commands:**
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=RoleTaxonomyMigrationTest,PilotSeedTest,TeamServiceIntegrationTest,TeamControllerTest,SparepartAlertQueryServiceTeamScopeFilterIntegrationTest,MachineListTeamScopeFilterTest,AuditDecisionIdMigrationTest,PolicyDecisionPointTest,AuthzControllerTest,AuthzInterceptorTest,WahaTemplateControllerTest,WahaTemplateUpsertIntegrationTest"` -- BUILD SUCCESS (13/13 RoleTaxonomyMigration, 5/5 WAHA, all parity).
- Full backend: pre-existing Testcontainers flakiness under load (HikariPool connection-refused), unrelated to change.
- `cd syncro/apps/web && npx tsc --noEmit && npm run test:unit` -- PASS (0 tsc errors, 30/31 suite files pass, 286/287 + 1 post-fix).
- Manual: boot stack, login as migrated user; dashboard + master-data visibility identical; old JWT returns 401 JSON.

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 9, story 4; epic-9-context.md valid |
| 02 plan | pass | spec-9-4 written, status ready-for-dev |
| 03 implement | pass | full sweep + verify; subagent returned clean |
| 04 review | pass | Blind Hunter (3 P0/P1/P2 → all patched) + Edge Case Hunter (1 P1 → patched, 1 P2 → accepted); 0 critical unfixed |
| commit | c7e3874 | `feat(auth): migrate role taxonomy to ten PRD roles (story 9-4)` |
| finalize | c7e3874~1 | status done, followup_review_recommended: false |

**Defers appended:** DW-131 (WAHA deny-list→allow-list deviation documented).

**Unresolved risk:** none for this scope. Pre-existing Testcontainers flakiness persists (DW-127).
