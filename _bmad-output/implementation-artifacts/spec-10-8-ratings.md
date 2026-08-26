---
title: 'Ratings'
type: 'feature'
created: '2026-08-26'
baseline_revision: 27e8dc8
final_revision: 042ed2a
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-7-todos-and-kanban.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** A closed workorder has no performance feedback — section leaders cannot rate the technicians who executed it and production leaders cannot rate the maintenance workorder itself, so the Epic 14 KPI/quality dashboards have no rating source data (FR-121, FR-124).

**Approach:** Add rating dimensions as configurable data (a `rating_dimensions` table, SUPER_ADMIN-managed, shared by both rating types per FR-124 "shared dimension config") plus two immutable rating flows on closed workorders: `POST /{id}/ratings/technician` (in-scope section leader rates a technician who executed the workorder) and `POST /{id}/ratings/workorder` (PRODUCTION_LEADER with plant access rates the workorder). Scores are 1–5 per dimension, stored in a normalized child table because dimensions are data. A `/workorders/ratings` page lists rateable closed workorders with a star-rating panel.

## Boundaries & Constraints

**Always:**
- **V53** (additive, on V52): three tables + audit extension.
  - `rating_dimensions` — `id UUID PK DEFAULT gen_random_uuid()`, `code VARCHAR(40) NOT NULL UNIQUE`, `label VARCHAR(100) NOT NULL`, `sort_order INTEGER NOT NULL DEFAULT 0`, `created_by UUID NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Index `idx_rating_dimensions_sort_order`. Seed one default set (Speed / Work Quality / Tidiness) so the UI is usable before SUPER_ADMIN customizes (AD-14, OQ-1 default).
  - `workorder_ratings` — `id UUID PK DEFAULT gen_random_uuid()`, `workorder_id VARCHAR(50) NOT NULL REFERENCES work_orders(id)`, `rating_type VARCHAR(12) NOT NULL CHECK (rating_type IN ('TECHNICIAN','WORKORDER'))`, `rated_user_id UUID`, `rater_user_id UUID NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Unique `uq_workorder_ratings_identity (workorder_id, rating_type, rated_user_id)` — for WORKORDER type `rated_user_id` is NULL and the unique constraint still enforces one-per-workorder (PostgreSQL treats NULLs as distinct in unique indexes, so WORKORDER-type needs a partial unique index `uq_workorder_ratings_workorder ON workorder_ratings(workorder_id) WHERE rating_type = 'WORKORDER'`). For TECHNICIAN type the composite unique enforces one rating per technician per workorder.
  - `workorder_rating_scores` — `rating_id UUID NOT NULL REFERENCES workorder_ratings(id) ON DELETE CASCADE`, `dimension_id UUID NOT NULL REFERENCES rating_dimensions(id)`, `score SMALLINT NOT NULL CHECK (score BETWEEN 1 AND 5)`, PK `(rating_id, dimension_id)`.
  - Audit: drop/re-add `ck_audit_log_entity_type` adding `'WORKORDER_RATING'` (V52 pattern, list all prior types). `AuditEntityType.WORKORDER_RATING`.
- **Rating eligibility:** the workorder must be CLOSED (read the status lifecycle from `WorkOrderStatus.CLOSED`). Anything not CLOSED → 400 `RATING_WORKORDER_NOT_CLOSED`. Ratings are immutable after submission — there is no update/delete endpoint; a duplicate submission hits the unique constraint and returns `RATING_ALREADY_EXISTS` (409).
- **Technician rating (FR-121):** rater must be the in-scope section leader of the workorder's machine group (reuse the 10-7 `isInScopeLeader` semantics — SUPER_ADMIN exempt). Request body: `ratedUserId` (the technician being rated) + `scores` map `{dimensionCode: 1..5}`. The rated user must exist. Rated technician may be the workorder's `assignedTechnicianId` OR a user who logged a repair session on this workorder (executor pool) — v1 validates the user exists and belongs to the workorder's executor pool; unknown → 400 `RATING_USER_NOT_EXECUTOR`. Self-rating is impossible because a section leader cannot be the executing technician on their own workorder (AD-14).
- **Workorder rating (FR-124):** rater must be `ApplicationRole.PRODUCTION_LEADER` with plant access to the workorder's plant (`PlantScopeService.canAccessPlant` — SUPER_ADMIN exempt, but a non-PRODUCTION_LEADER non-SUPER_ADMIN is rejected → 403 `FORBIDDEN`). Request body: `scores` map only (no rated user — bound to the workorder).
- **Scores validation:** every submitted `scores` key must be an existing dimension code, every value an integer 1–5. Unknown dimension → 400 `VALIDATION_ERROR` fieldErrors. Missing dimensions are allowed (a rater may score a subset). The saved rating stores exactly the submitted dimension scores.
- **Read endpoints:** `GET /{id}/ratings` — list ratings (with scores) for a workorder, any authenticated user (workorder read posture). `GET /api/v1/rating-dimensions` — any authenticated user. `GET /api/v1/rating-dimensions` is read; dimension CRUD is SUPER_ADMIN-only.
- **Dimension CRUD (SUPER_ADMIN):** `POST /api/v1/rating-dimensions`, `PUT /api/v1/rating-dimensions/{code}`, `DELETE /api/v1/rating-dimensions/{code}` — SUPER_ADMIN only, audit-logged (entity type `RATING_DIMENSION`; reuse the V53 CHECK extension — add `'RATING_DIMENSION'` alongside `'WORKORDER_RATING'`). Deleting a dimension referenced by existing scores → 400 `RATING_DIMENSION_IN_USE` (FK violation guard, checked in service before delete).
- **Rego:** `workorder_rating_paths := {"/api/v1/workorders/*/ratings", "/api/v1/workorders/*/ratings/*"}` — mutation allow set `{SUPER_ADMIN (top-level), SECTION_LEADER, PRODUCTION_LEADER, MANAGER_MAINTENANCE, MAINTENANCE_LEADER}` for technician rating; workorder rating is PRODUCTION_LEADER. To keep the policy simple and service-authoritative (like 10-7), use a single five-role allow set `{MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER}` on the mutation paths — the service gate is authoritative for who-can-rate-whom. Reads (GET) flow through generic `read_allowed`. `rating_dimension_paths := {"/api/v1/rating-dimensions", "/api/v1/rating-dimensions/*"}` — mutations SUPER_ADMIN-only; reads generic.
- **Errors:** unknown workorder → 404 `WORKORDER_NOT_FOUND`; unknown rated user → 404 `USER_NOT_FOUND`; workorder not CLOSED → 400 `RATING_WORKORDER_NOT_CLOSED`; duplicate rating → 409 `RATING_ALREADY_EXISTS`; rated user not in executor pool → 400 `RATING_USER_NOT_EXECUTOR`; forbidden → 403 `FORBIDDEN`; validation → 400 `VALIDATION_ERROR`; dimension in use on delete → 400 `RATING_DIMENSION_IN_USE`.
- **Frontend:** page `/workorders/ratings` — lists CLOSED workorders the user can rate (section leaders: their group's closed workorders + assigned/session technicians; production leaders: their plant's closed workorders), each with a star-rating panel (5 stars per dimension, click to submit). Star component: small local component using Lucide `Star` (no new dependency). Loading/empty/error states required.

**Block If:** nothing.

**Never:**
- Never touch V47-V52 or add V54 — V53 is the only migration for 10.8.
- Never implement workorder list/detail — stays deferred (10-6). The ratings page reuses the closed-workorder query, not a full list view.
- Never add update/delete for a rating — immutable after submission (FR-121/FR-124).
- Never store dimensions as code (hardcoded enums) — dimensions are data (AD-14).
- Never rate an OPEN/ASSIGNED/IN_PROGRESS/ON_PROCUREMENT/DONE workorder — CLOSED only.
- Never allow a section leader to rate a workorder (that's the production leader's job) or a production leader to rate a technician.
- Never compute averages/aggregates in this story — the ratings are input + per-workorder view only; aggregation is Epic 14.
- Never rate self (a section leader rating themselves as technician) — structurally prevented by AD-14.
- Never touch ratings for synced workorders differently — ratings are local operational fields (AD-3 "preserved") allowed on both sources.
- Never add new Spring dependencies or a third-party rating widget.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| TECH_RATING_OK | in-scope section leader, CLOSED workorder, valid scores | 201 `RatingView`; scores persisted; audit CREATE | — |
| TECH_RATING_NOT_CLOSED | workorder in OPEN/ASSIGNED/IN_PROGRESS/DONE | 400 RATING_WORKORDER_NOT_CLOSED | — |
| TECH_RATING_FORBIDDEN | not in-scope leader (e.g. TECHNICIAN/AUDITOR) | 403 FORBIDDEN | — |
| TECH_RATING_USER_NOT_EXECUTOR | ratedUserId not in workorder executor pool | 400 RATING_USER_NOT_EXECUTOR | — |
| TECH_RATING_UNKNOWN_DIM | scores key is not an existing dimension | 400 VALIDATION_ERROR fieldErrors | — |
| TECH_RATING_BAD_SCORE | score <1 or >5 or non-integer | 400 VALIDATION_ERROR fieldErrors | — |
| TECH_RATING_DUPLICATE | same (workorder, TECHNICIAN, rated_user) exists | 409 RATING_ALREADY_EXISTS | — |
| WO_RATING_OK | PRODUCTION_LEADER + plant access, CLOSED workorder | 201 `RatingView`; audit CREATE | — |
| WO_RATING_FORBIDDEN | not PRODUCTION_LEADER (e.g. SECTION_LEADER) | 403 FORBIDDEN | — |
| WO_RATING_NO_PLANT | PRODUCTION_LEADER without plant access | 403 FORBIDDEN | — |
| WO_RATING_DUPLICATE | workorder already has a WORKORDER rating | 409 RATING_ALREADY_EXISTS | — |
| RATINGS_READ_OK | any authenticated user | 200 `RatingView[]` with scores | — |
| DIM_CREATE_OK | SUPER_ADMIN creates dimension | 201 `RatingDimensionView`; audit | — |
| DIM_CREATE_FORBIDDEN | non-SUPER_ADMIN | 403 FORBIDDEN | — |
| DIM_CREATE_DUP_CODE | code already exists | 409 RATING_DIMENSION_CODE_EXISTS | — |
| DIM_DELETE_IN_USE | dimension has existing scores | 400 RATING_DIMENSION_IN_USE | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V53__workorder_ratings.sql` -- NEW -- 3 tables + seed + audit entity_type extension.

**Domain:**
- `com/syncro/maintenance/domain/workorder/RatingType.java` -- NEW -- enum TECHNICIAN, WORKORDER.
- `com/syncro/maintenance/domain/workorder/WorkorderRating.java` -- NEW -- record: id, workorderId, ratingType, ratedUserId, raterUserId, createdAt, scores (Map<String,Integer> dimensionCode→score).
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- + WORKORDER_RATING, RATING_DIMENSION.

**Persistence:**
- `com/syncro/maintenance/infrastructure/db/RatingDimensionEntity.java` / `RatingDimensionRepository.java` -- NEW -- config table CRUD.
- `com/syncro/maintenance/infrastructure/db/WorkorderRatingEntity.java` / `WorkorderRatingRepository.java` -- NEW -- header + findForWorkorder / exists checks.
- `com/syncro/maintenance/infrastructure/db/WorkorderRatingScoreEntity.java` / `WorkorderRatingScoreRepository.java` -- NEW -- child scores.
- `com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- MODIFY -- `findClosedForRating` (CLOSED workorders filtered by scope) + executor-pool query (assigned technician + session technicians).

**Application:**
- `com/syncro/maintenance/application/WorkOrderRatingService.java` -- NEW -- rateTechnician, rateWorkorder, listRatings, dimension CRUD + gates + audit.
- `com/syncro/maintenance/application/WorkOrderMapper.java` -- MODIFY -- rating/dimension entity → domain mapping.

**API:**
- `com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- `POST /{id}/ratings/technician`, `POST /{id}/ratings/workorder`, `GET /{id}/ratings`.
- `com/syncro/maintenance/api/RatingDimensionController.java` -- NEW -- `GET/POST /api/v1/rating-dimensions`, `PUT/DELETE /api/v1/rating-dimensions/{code}`.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- `RateTechnicianRequest`, `RateWorkorderRequest`, `RatingView`, `RatingDimensionView`, `CreateRatingDimensionRequest`.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- MODIFY -- RATING_* codes.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- `workorder_rating_paths` (five-role) + `rating_dimension_paths` (SUPER_ADMIN mutation).
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases.
- `syncro/.env.example` -- MODIFY -- rating paths in enforced-paths.

**Frontend:**
- `src/app/workorders/ratings/page.tsx` -- NEW -- ratings page (Server Component wrapper).
- `src/features/workorders/components/rating-panel.tsx` -- NEW -- client component: dimension list + 5-star input + submit.
- `src/features/workorders/components/star-rating.tsx` -- NEW -- 5-star clickable control (Lucide Star).
- `src/features/workorders/components/rateable-workorder-card.tsx` -- NEW -- card for a closed workorder with rating status.
- `src/features/workorders/hooks/use-ratings.ts` -- NEW -- data fetch + submit hooks.
- `src/features/workorders/types.ts` -- MODIFY -- rating/dimension types.
- `src/lib/api/generated/syncro.ts` -- MODIFY -- regenerate or add types.

**Tests:**
- `com/syncro/maintenance/application/WorkOrderRatingServiceTest.java` -- NEW -- rating + dimension CRUD + gates.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- rating endpoint shapes.
- `com/syncro/db/WorkorderRatingsMigrationTest.java` -- NEW -- V53 tables/constraints/seed (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [x] `resources/db/migration/V53__workorder_ratings.sql` -- 3 tables + seed + audit extension.
- [x] `RatingType.java` / `WorkorderRating.java` / `AuditEntityType.java` -- domain types.
- [x] `RatingDimensionEntity/Repository` + `WorkorderRatingEntity/Repository` + `WorkorderRatingScoreEntity/Repository` -- persistence.
- [x] `WorkOrderRepository.java` -- findClosedForRating + executor-pool query.
- [x] `WorkOrderRatingService.java` -- rateTechnician/rateWorkorder/listRatings/dimension CRUD + gates + audit.
- [x] `WorkOrderMapper.java` -- rating/dimension mapping.
- [x] `WorkOrderController.java` + `RatingDimensionController.java` + DTOs + exception handler -- endpoints + RATING_* codes.
- [x] `authz.rego` + `authz_test.rego` + `.env.example` -- rating paths + parity.
- [x] Frontend: ratings page + star panel + hooks.
- [x] Tests (service + controller + migration).

**Acceptance Criteria:**
- Given a CLOSED workorder in an in-scope section leader's group, when the leader rates a technician who executed it, then the rating is persisted with 1–5 scores per configured dimension and audit-logged with WORKORDER_RATING CREATE; a second rating of the same technician on the same workorder is rejected with RATING_ALREADY_EXISTS. [FR-121]
- Given a CLOSED workorder, when a PRODUCTION_LEADER with access to its plant rates it, then a WORKORDER-type rating bound to the workorder is persisted; a second workorder rating is rejected. [FR-124]
- Given a rating is submitted with a non-integer/out-of-range score or an unknown dimension, when submitted, then 400 VALIDATION_ERROR is returned and nothing is persisted.
- Given a workorder that is not CLOSED, when a rating is attempted, then 400 RATING_WORKORDER_NOT_CLOSED is returned.
- Given rating dimensions, when a SUPER_ADMIN creates/updates/deletes a dimension, then the dimension config is persisted and audit-logged; a non-SUPER_ADMIN is rejected; deleting a dimension with existing scores is rejected with RATING_DIMENSION_IN_USE. [AD-14]
- Given a CLOSED workorder with ratings, when the ratings endpoint is called by any authenticated user, then the ratings with per-dimension scores are returned.
- Given OPA enforcement, then the rating mutation paths are default-deny (five-role allow set) and dimension mutation paths are SUPER_ADMIN-only, with parity tests. [FR-160]

## Design Notes

- **Dimensions are data, not code.** A `rating_dimensions` table with SUPER_ADMIN CRUD is the AD-14 contract. Both rating types share the same dimension set (FR-124 "shared dimension config with FR-121"). Seed Speed/Work Quality/Tidiness (the UJ-3 example) so the UI works before any customization. Scores are stored in a normalized child table (`workorder_rating_scores`) because the dimension set is dynamic — a column-per-dimension schema would need a migration whenever SUPER_ADMIN adds a dimension.
- **Two rating types share one table.** `workorder_ratings.rating_type` distinguishes technician vs workorder ratings. The WORKORDER type has `rated_user_id = NULL` and needs a partial unique index (`WHERE rating_type='WORKORDER'`) because PostgreSQL unique indexes treat NULLs as distinct — otherwise two WORKORDER ratings would be allowed.
- **Immutability via unique constraints.** No update/delete endpoints exist for ratings. The composite unique `(workorder_id, rating_type, rated_user_id)` plus the partial WORKORDER unique make duplicates a DB-level rejection, surfaced as 409 RATING_ALREADY_EXISTS.
- **Executor pool for technician rating.** A rated technician must have executed the workorder: either it's the workorder's `assigned_technician_id` or a user with a repair session on it. This prevents rating random users. The pool is read via one repository query (no N+1).
- **PRODUCTION_LEADER gate = plant access.** AD-15 says production-leader scope is "line/plant-derived from auth_user_plant_assignments". There is no production-line binding in v1, so the gate is `role == PRODUCTION_LEADER && canAccessPlant(workorder.plant)` — consistent with AD-15 and the absence of a line model.
- **Frontend star control is local.** A small Lucide `Star`-based 5-star clickable input — no rating widget dependency. The ratings page is a separate route (workorder detail stays deferred); it lists rateable closed workorders with their rating status.
- **Kanban query reuse.** `findClosedForRating` mirrors `findKanbanRows` (10-7): single query, scope-filtered by plant/group, but filtering on `status = CLOSED` instead of non-terminal.

## Verification

**Commands:**
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderRatingServiceTest,WorkOrderControllerTest,WorkorderRatingsMigrationTest"` -- expected BUILD SUCCESS.
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrder*Test"` -- expected no regressions.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new rating parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/workorders src/app/workorders` -- expected clean.
