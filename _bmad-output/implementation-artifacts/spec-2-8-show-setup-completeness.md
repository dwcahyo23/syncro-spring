---
title: '2-8 Show Setup Completeness'
type: 'feature'
created: '2026-08-07'
status: 'done'
review_loop_iteration: 0
baseline_revision: 'e188fe9e4117b6eea69db3b5027c750f3e5e6883'
followup_review_recommended: false
context: []
warnings: ['oversized']
---

# 2-8 Show Setup Completeness

## Intent

**Problem:** SUPER_ADMIN/MANAGE users have no single view telling them whether the master-data setup chain (plant → machine group → machine → sparepart → installation → responsibility) is complete, so they cannot know if a machine is ready to receive telemetry and alerts.

**Approach:** Add a backend-derived, plant-scoped setup completeness endpoint that returns one checklist of six steps with `COMPLETE`/`INCOMPLETE`/`BLOCKED` status, a next action and link per incomplete step, and machine telemetry-eligibility counts. Render it on a new Setup page with a reusable `SetupCompletenessChecklist` component. Backend computes all status; the UI never recalculates domain rules.

## Boundaries & Constraints

**Always:**
- Backend is the sole source of truth for step status, blocking, and eligibility. The frontend renders backend-provided statuses only (Epic 2 E2-R6).
- Plant scope: SUPER_ADMIN sees all plants; MANAGE/VIEWER see only their assigned plants; `EMPTY` scope reports a blocked Plant step.
- Setup completeness must NEVER infer machine active state from telemetry or query Redis/InfluxDB/MQTT. Machine `ACTIVE`/`INACTIVE` is manual master data and is not used to gate any step.
- Existing conventions: `com.syncro.<context>.{api,application,infrastructure}` packages, `MachineView`-style flat records, standard error contract `{code,message,fieldErrors,timestamp,traceId}`, per-module `@RestControllerAdvice`, `@AuthenticationPrincipal AuthenticatedUser`, Flyway only for schema (no new migration required), Orval-generated hooks + TanStack Query on frontend, `usePlantScope()` + `permittedPlants` + `enabled` gating, invalidation via plant-scoped query-key prefixes.
- No JPA entity may be returned from a controller; DTOs are records.
- `@SpringBootTest` (Testcontainers Postgres 17) for persistence behavior; MockMvc slice for API/error contract. Display names `2.8-API-00n P<n>` / `2.8-SVC-00n P<n>`.

**Block If:**
- If backend cannot boot due to the pre-existing responsibility entity/migration schema mismatch (`machine_responsibility` vs `machine_responsibilities`) after the entity-alignment fix in this spec, HALT with status `blocked` and that condition.

**Never:**
- No machine detail / dynamic route (App's first dynamic route stays out of scope). AC says "setup or machine context page" — Setup page satisfies it; the checklist component stays data-driven for future reuse.
- No new migration for 2.8 (all data is derivable from existing tables). Do NOT edit applied migrations.
- No telemetry/alerting/WAHA/audit-log behavior (audit is Story 2.9).
- Do not hand-edit generated Orval output; regenerate via `npm run generate:api`.
- Do not branch the UI on translated labels, colors, or Java exception names.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | Authenticated SUPER_ADMIN, complete pilot data (GM1/Forming/BF-08410/JBF19/Electric PLC Wecon LX5) | 200; 6 steps all `COMPLETE`; `overallStatus=COMPLETE`; machinesEligibleCount>0 | No error expected |
| PARTIAL_SETUP | Plant+group+machine exist, no sparepart/installation/responsibility | 200; steps SPAREPART/INSTALLATION/RESPONSIBILITY `INCOMPLETE` with nextAction + href; `overallStatus=INCOMPLETE` | No error expected |
| BLOCKED_CHAIN | Plant exists, no machine groups | MACHINE_GROUP `INCOMPLETE`; MACHINE/SPAREPART/INSTALLATION/RESPONSIBILITY `BLOCKED` with next action pointing to prerequisite | No error expected |
| EMPTY_SCOPE | MANAGE/VIEWER with no plant assignments | 200; PLANT `BLOCKED` (nextAction assigns plant); downstream steps `BLOCKED`; counts 0 | No error expected |
| SCOPED_USER | MANAGE assigned only GM1, other plant has data | Response reflects only GM1; other plant's machines not counted | No error expected |
| UNAUTHENTICATED | No bearer token | 401 standard safe auth error shape | SecurityConfig entry point |

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineResponsibilityEntity.java` -- BUGGY: maps `machine_responsibility`/`responsibility_level`/`plant_id`, mismatches applied V14; fix to `machine_responsibilities`/`level`, drop `plant_id`
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineResponsibilityRepository.java` -- add scoped count of responsibilities per plant; fix `findAllByPlantIds` to `machine.plant.id IN :plantIds`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantRepository.java` -- add count by ids / all
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/infrastructure/MachineGroupRepository.java` -- add count by plant ids
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` -- add count by plant ids; count eligible machines (has installation AND responsibility)
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartRepository.java` -- add count scoped via sparepart.machine.plant
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationRepository.java` -- add count scoped via machine.plant
- `syncro/apps/backend/src/main/java/com/syncro/setup/application/SetupCompletenessService.java` -- NEW: aggregate counts, derive step statuses, eligibility, scope
- `syncro/apps/backend/src/main/java/com/syncro/setup/api/SetupCompletenessController.java` -- NEW: `GET /api/v1/setup-completeness`
- `syncro/apps/backend/src/main/java/com/syncro/setup/api/SetupCompletenessDtos.java` -- NEW: response records
- `syncro/apps/backend/src/main/java/com/syncro/setup/api/SetupCompletenessExceptionHandler.java` -- NEW: minimal (PlantAccessDenied/403)
- `syncro/apps/backend/src/test/java/com/syncro/setup/api/SetupCompletenessControllerTest.java` -- NEW: MockMvc slice
- `syncro/apps/backend/src/test/java/com/syncro/setup/application/SetupCompletenessServiceIntegrationTest.java` -- NEW: Testcontainers
- `syncro/apps/web/src/lib/api/generated/*` -- regenerated via `npm run generate:api`
- `syncro/apps/web/src/components/syncro/setup-completeness-checklist.tsx` -- NEW: data-driven checklist
- `syncro/apps/web/src/app/(main)/dashboard/master-data/setup/page.tsx` -- NEW: route shell + RoleGuard
- `syncro/apps/web/src/features/setup/setup-completeness-page.tsx` -- NEW: page feature logic
- `syncro/apps/web/src/navigation/sidebar/sidebar-items.ts` -- add Setup sub-item under Master Data

## Tasks & Acceptance

**Execution:**
- [x] `MachineResponsibilityEntity.java` -- align `@Table(name="machine_responsibilities")`, `@Column(name="level")` for `responsibilityLevel`, remove `plantId` field/getter/constructor param, align `@UniqueConstraint(columnNames={"machine_id","user_id"})` -- prerequisite: schema validate must pass and 2.7 integration test must boot
- [x] `MachineResponsibilityRepository.java` -- replace `findAllByPlantIds` JPQL to `mr.machine.plant.id IN :plantIds`; add `long countByMachinePlantIdIn(List<UUID>)` -- scoped responsibility count
- [x] `PlantRepository.java` -- add `long countAll()`/`countByIdIn(List<UUID>)` -- plant step
- [x] `MachineGroupRepository.java` -- add `long countByPlantIdIn(List<UUID>)` -- group step
- [x] `MachineRepository.java` -- add `long countByPlantIdIn(List<UUID>)` and eligibility count query -- machine step + eligibility
- [x] `SparepartRepository.java` -- add scoped count (sparepart.machine.plant.id IN ids) -- sparepart step
- [x] `MachineSparepartInstallationRepository.java` -- add scoped count (machine.plant.id IN ids) -- installation step
- [x] `SetupCompletenessDtos.java` + `SetupCompletenessController.java` + `SetupCompletenessService.java` + `SetupCompletenessExceptionHandler.java` -- implement `GET /api/v1/setup-completeness` with 6 steps, scope, counts, eligibility; service derives statuses (INCOMPLETE vs BLOCKED per prerequisite chain) -- core feature
- [x] `SetupCompletenessControllerTest.java` -- MockMvc: 200 shape, EMPTY scope, scoped filtering, 401 -- API evidence
- [x] `SetupCompletenessServiceIntegrationTest.java` -- Testcontainers: partial→INCOMPLETE with nextActions; blocked chain; EMPTY scope; scoped user isolation; full pilot chain→COMPLETE+eligible; verify no telemetry dependency -- service evidence
- [x] Run backend tests, start backend, `npm run generate:api` -- generated client for new endpoint
- [x] `setup-completeness-checklist.tsx` + `setup-completeness-page.tsx` + `setup/page.tsx` + `sidebar-items.ts` -- render checklist with text+icon+badge (non-color-only), loading/empty/error/read-only/forbidden states, links to next actions -- UI evidence
- [x] `npm run check` / build + browser verify Setup route states -- completion evidence

**Acceptance Criteria:**
- Given authenticated SUPER_ADMIN/MANAGE user opens the Setup page, when data is partially or fully configured, then checklist shows plant, machine group, machine, sparepart, installation, and responsibility completion states.
- Given an incomplete step, when checklist renders, then it shows a clear next action and link to that step's management page.
- Given any step status, when rendered, then it uses text + icon + badge (never color alone).
- Given loading, empty, error, read-only, or forbidden state, when the checklist renders, then a durable corresponding state appears (skeleton, empty prompt, retry, read-only badge, forbidden copy).
- Given a machine with manual `ACTIVE` status but no telemetry ever received, when setup completeness is computed, then machine active state is never inferred from telemetry and no telemetry source is queried.

## Design Notes

Step status derivation (scoped to plantIds from `PlantScopeService.effectiveScope`):
- `PLANT` complete if plantCount>0; `BLOCKED` when scope is `EMPTY` (nextAction assigns a plant); else `INCOMPLETE` → `/master-data/plants`.
- `MACHINE_GROUP` complete if groupCount>0; `BLOCKED` when plantCount==0; else `INCOMPLETE` → `/master-data/machine-groups`.
- `MACHINE` complete if machineCount>0; `BLOCKED` when groupCount==0; else `INCOMPLETE` → `/master-data/machines`.
- `SPAREPART` complete if sparepartCount>0; `BLOCKED` when machineCount==0 (spareparts.machine_id NOT NULL); else `INCOMPLETE` → `/master-data/spareparts`.
- `INSTALLATION` complete if installationCount>0; `BLOCKED` when machineCount==0 or sparepartCount==0; else `INCOMPLETE` → `/master-data/installations`.
- `RESPONSIBILITY` complete if responsibilityCount>0; `BLOCKED` when machineCount==0; else `INCOMPLETE` → `/master-data/responsibilities`.
- `overallStatus` is `COMPLETE` iff all six steps complete. `machinesEligibleCount` = machines in scope having ≥1 installation AND ≥1 responsibility. `machineCount` = all machines in scope.

Response shape (flat records):
```json
{ "scope": { "mode": "UNRESTRICTED|ASSIGNED|EMPTY", "plantIds": [], "emptyReason": null },
  "overallStatus": "COMPLETE|INCOMPLETE",
  "machineCount": 0, "machinesEligibleCount": 0,
  "steps": [ { "key": "PLANT", "label": "Plant", "status": "COMPLETE|INCOMPLETE|BLOCKED",
               "nextAction": "Create a plant", "href": "/master-data/plants" } ] }
```
Status key order is the setup chain order. Prerequisite blocker chain mirrors the UX flow (plant → group → machine → sparepart → installation → responsibility).

## Verification

**Commands:**
- `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; mvn -q -f syncro/apps/backend/pom.xml test -Dtest="SetupCompletenessControllerTest,SetupCompletenessServiceIntegrationTest,MachineResponsibilityServiceIntegrationTest"` -- expected: all pass, including 2.7 integration now booting (prerequisite proof)
- `npm --prefix syncro/apps/web run check` -- expected: Biome + typecheck pass
- `npm --prefix syncro/apps/web run build` -- expected: production build succeeds

**Manual checks:**
- Start backend (Docker postgres up, `SPRING_PROFILES_ACTIVE=local`), confirm boot with `Schema validation` clean and `GET /api/v1/setup-completeness` returns 200 for SUPER_ADMIN and scoped data for MANAGE/VIEWER.
- Browser: `/dashboard/master-data/setup` renders checklist with complete/incomplete/blocked text+icon+badge states, links to next action, and loading/empty/error/read-only states.

## Auto Run Result

**Summary:** Implemented the plant-scoped setup completeness checklist (backend-derived, 6 steps) with a new Setup page. Backend: new `com.syncro.setup` package exposing `GET /api/v1/setup-completeness`, aggregate count queries on plant/machine-group/machine/sparepart/installation/responsibility repositories, and the prerequisite `MachineResponsibilityEntity` schema alignment (V14 `machine_responsibilities`/`level`, plant_id dropped) that unblocked boot. Frontend: regenerated Orval client, reusable `SetupCompletenessChecklist` component, `setup` feature page with loading/empty/error/read-only states, route under Master Data, sidebar entry. Also fixed story 2-7's stale `responsibility-management.tsx` to the regenerated hook names (its committed code referenced `useList`/`useAssign`/`useUnassign`/`getListQueryKey`, which the current backend OpenAPI no longer emits), restoring a green production build.

**Files changed:**
- Backend (new): `syncro/apps/backend/src/main/java/com/syncro/setup/{api/SetupCompletenessController.java, api/SetupCompletenessDtos.java, api/SetupCompletenessExceptionHandler.java, application/SetupCompletenessService.java}` -- endpoint, DTO records, error contract, status derivation per prerequisite chain
- Backend (new tests): `syncro/apps/backend/src/test/java/com/syncro/setup/{api/SetupCompletenessControllerTest.java, application/SetupCompletenessServiceIntegrationTest.java}` -- MockMvc slice (5 tests) + Testcontainers service evidence (6 tests)
- Backend (fixed): `MachineResponsibilityEntity.java` (table/column/unique alignment, plant_id dropped), `MachineResponsibilityRepository.java` (scoped `findAllByPlantIds` + `countByMachinePlantIdIn`), `PlantRepository.java`, `MachineGroupRepository.java`, `MachineRepository.java` (counts + eligibility join), `SparepartRepository.java`, `MachineSparepartInstallationRepository.java` (scoped counts)
- Backend test (updated): `MachineResponsibilityServiceIntegrationTest.java` (boots again after entity fix; 3 tests)
- Frontend (regenerated): `src/lib/api/generated/syncro.ts` + `model/{setupCompletenessResponse,step,scopeInfo}.ts` + `model/listMachineResponsibilitiesParams.ts`, removed stale `model/listParams.ts`
- Frontend (new): `src/components/syncro/setup-completeness-checklist.tsx`, `src/features/setup/setup-completeness-page.tsx`, `src/app/(main)/dashboard/master-data/setup/page.tsx`
- Frontend (changed): `src/navigation/sidebar/sidebar-items.ts` (Setup sub-item), `src/features/master-data/responsibilities/responsibility-management.tsx` (2-7 hook rename fix + typed error handling), `src/features/master-data/{machines,spareparts,sparepart-taxonomy,installations}/` pages (generated client alignment)

**Review findings breakdown:** This story was completed through the bmad-loop run `20260807-100428-de49` and this session's continuation. Patches applied: lucide icon names aligned to project `vendor.d.ts` whitelist (`CircleCheckIcon`/`Circle`/`OctagonXIcon`), Biome import sorting, unused import removed, `any`-typed error handler replaced with `SyncroApiError`-based `errorResponse` helper. Items deferred: noUnnecessaryConditions/useSortedClasses/noNestedTernary warnings on generated-adjacent conditionals match the accepted codebase-wide warning baseline (e.g. `machine-group-management.tsx`, `machine-management.tsx`) -- none are errors and Biome `check` reports 0 errors. Items rejected: none.

**Follow-up review recommendation:** `false` -- findings were localized, low-severity, and consistent with existing codebase patterns.

**Verification performed:**
- Backend: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="SetupCompletenessControllerTest,SetupCompletenessServiceIntegrationTest,MachineResponsibilityServiceIntegrationTest"` -- all 14 tests pass (5+6+3)
- Frontend lint: `npm --prefix syncro/apps/web run check` -- 0 errors (24 warnings/14 infos, matching accepted baseline)
- Frontend build: `npm --prefix syncro/apps/web run build` -- compiles successfully; `/dashboard/master-data/setup` route emitted
- Live endpoint: running backend on :8080 serves `/api/v1/setup-completeness` in OpenAPI; unauthenticated request returns 401 (correct security contract)
- Regeneration: `npm run generate:api` against live backend produced the new setup-completeness client types/hooks

**Residual risks:**
- Story 2-7 remains `review` on the sprint board; its frontend was aligned to the current generated client in this session, so a final 2-7 review should re-verify the responsibilities UI end to end.
- The stale paused bmad-loop run `20260807-100428-de49` still exists in `.bmad-loop/runs/`; it can be deleted or resumed (resume requires `--project` and a clean tree) without affecting this completed story.
- `npm run check` warning baseline (noUnnecessaryConditions on hook overload inference) persists project-wide; biome's static overload resolution flags `isLoading` on generated Orval hooks that take optional options without `initialData`.
