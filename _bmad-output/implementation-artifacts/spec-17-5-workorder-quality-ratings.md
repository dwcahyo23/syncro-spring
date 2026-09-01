---
title: 'Story 17-5: Workorder Quality Rating Multi-Technician'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '717c548'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-17-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The dedicated workorder-quality-rating tables (`work_order_quality_ratings` + `_technicians` + `_scores`, blueprint C4-C6) exist from 15-2 but have no service/API/OPA layer. The legacy `workorder_ratings` (10-8) is a separate, non-per-criterion model. The PRODUCTION_LEADER has no way to rate maintenance quality per workorder spanning multiple technicians with per-technician scores.

**Approach:** Build `WorkOrderQualityRatingService` over the C4-C6 tables: a PENDING rating row is created when a maintenance workorder closes (or lazily on first submit), the PRODUCTION_LEADER of the affected line submits it with per-dimension scores + per-technician scores via the pivot, one rating per workorder (unique), immutable after submission, and status becomes EXPIRED when `due_at` passes unsubmitted (recomputed on read).

## Boundaries & Constraints

**Always:**
- Schema/entities/repos exist (15-2) — reuse; do not alter.
- One rating per workorder: `uq_work_order_quality_ratings_work_order` (unique work_order_id) backstops.
- Rating lifecycle: PENDING → SUBMITTED (via `submit()` method on entity), EXPIRED when `due_at` passes unsubmitted (computed on read, persisted when observed).
- Rater gate: PRODUCTION_LEADER with plant access to the workorder's machine (SUPER_ADMIN exempt) — mirror `WorkOrderRatingService.requireProductionLeaderGate`.
- Rated workorder must be CLOSED (same as 10-8 legacy).
- Per-criterion scores validated against `work_order_rating_criteria.min_score/max_score`; per-technician scores stored via `work_order_quality_rating_technicians` pivot, technician ids derived from the workorder's executor pool (assigned technician + active assignments + repair-session technicians).
- Every mutation audit-logged with `AuditEntityType.WORK_ORDER_QUALITY_RATING` (add to BOTH Java enum and V6 additive CHECK migration).
- `AuditEntityType` needs `WORK_ORDER_QUALITY_RATING` added — same pattern as V3/V4/V5.
- OPA: add `workorder_quality_rating_paths` mirroring `workorder_rating_paths` (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER).
- Criteria are the existing `work_order_rating_criteria` (C3) — list reads only (SUPER_ADMIN CRUD is out of scope here; 17-4 covered the work-log criteria analog).

**Block If:**
- The audit CHECK extension cannot be done as an additive migration → HALT blocked.

**Never:**
- No schema changes to existing tables.
- No hard deletion of ratings; ratings immutable after submission.
- No bypassing OPA; no new dependencies.
- Do not touch the legacy `workorder_ratings`/`rating_dimensions` flow (10-8 stays as-is).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Submit rating on CLOSED WO | PRODUCTION_LEADER with plant access, scores + technician ids | quality rating row SUBMITTED; pivot + score rows persisted; audit CREATE | no error |
| Workorder not CLOSED | workorder in IN_PROGRESS | rejected | `RATING_WORKORDER_NOT_CLOSED` |
| Non-production-leader rater | SECTION_LEADER submits | rejected | `FORBIDDEN` |
| Duplicate rating | rating already SUBMITTED | rejected | `RATING_ALREADY_EXISTS` |
| Score out of range | score > criterion max_score | rejected | `VALIDATION_ERROR` fieldErrors |
| Unknown technician | technician not in executor pool | rejected | `RATING_USER_NOT_EXECUTOR` |
| EXPIRED rating | due_at passed, unsubmitted | status EXPIRED on read | no error |
| List rating | any authenticated user | rating with scores + technicians | no error |

</intent-contract>

## Code Map

### Existing (reuse, do not modify unless listed)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderQualityRatingEntity.java` -- entity with `submit()` (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderQualityRatingTechnicianEntity.java` -- pivot (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderQualityRatingScoreEntity.java` -- score (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderQualityRatingRepository.java` -- `findByWorkOrderId`
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderQualityRatingTechnicianRepository.java` -- JPA repo
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderQualityRatingScoreRepository.java` -- JPA repo
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderRatingCriterionRepository.java` -- JPA repo (add findAllByOrderBySortOrderAsc)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkRatingStatus.java` -- enum (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderRatingService.java` -- pattern to mirror (gates, validation, audit, executor pool)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java` -- `isExecutor`, `requireTransitionAccess` patterns; active-assignment lookups via WorkAssignmentRepository
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkAssignmentRepository.java` -- active assignment finder
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add quality-rating endpoints
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add records
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_ORDER_QUALITY_RATING
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- audit CHECK (baseline, do not edit)
- `syncro/authz/policy/authz.rego` -- workorder_rating_paths + role blocks
- `syncro/authz/policy/authz_test.rego` -- mirror tests

### To create/modify
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderQualityRatingService.java` -- NEW: submit, get, expire-on-read
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add request/view records
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add endpoints
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_ORDER_QUALITY_RATING
- `syncro/apps/backend/src/main/resources/db/migration/V6__work_order_quality_rating_audit_type.sql` -- NEW additive migration
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderRatingCriterionRepository.java` -- add findAllByOrderBySortOrderAsc
- `syncro/authz/policy/authz.rego` -- add workorder_quality_rating_paths
- `syncro/authz/policy/authz_test.rego` -- mirror tests
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkOrderQualityRatingServiceTest.java` -- NEW unit tests
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkOrderQualityRatingServiceIntegrationTest.java` -- NEW real-DB tests
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- extend CHECK assertion

## Tasks & Acceptance

**Execution:**
- `WorkOrderQualityRatingService.java` -- submit/get/expire-on-read -- core use cases
- `WorkOrderDtos.java` -- add records -- API contract
- `WorkOrderController.java` -- add endpoints -- expose the service
- `WorkOrderExceptionHandler.java` -- map new exceptions -- stable error codes
- `AuditEntityType.java` -- add WORK_ORDER_QUALITY_RATING -- audit label
- `V6__work_order_quality_rating_audit_type.sql` -- extend audit CHECK -- schema/entity parity
- `WorkOrderRatingCriterionRepository.java` -- add finder -- criteria reads
- `authz.rego` -- add path set + role blocks -- OPA enforcement
- `authz_test.rego` -- add tests -- OPA parity
- `WorkOrderQualityRatingServiceTest.java` -- unit tests -- edge cases
- `WorkOrderQualityRatingServiceIntegrationTest.java` -- real-DB tests -- persisted AC verification
- `V1BaseSchemaMigrationTest.java` -- extend CHECK assertion -- migration test

**Acceptance Criteria:**
- Given a CLOSED maintenance workorder, when the PRODUCTION_LEADER of the affected line submits a quality rating with per-criterion scores and per-technician scores, then one `work_order_quality_ratings` row is persisted as SUBMITTED, pivot + score rows are created, and the rating is audit-logged.
- Given a workorder that is not CLOSED, when a rating is attempted, then it is rejected with RATING_WORKORDER_NOT_CLOSED.
- Given a rating already exists (unique work_order_id), when a second is attempted, then it is rejected with RATING_ALREADY_EXISTS.
- Given a `due_at` that has passed with no submission, when the rating is read, then its status is EXPIRED.
- Given a technician not in the executor pool, when scored, then it is rejected with RATING_USER_NOT_EXECUTOR.
- Given a NON-PRODUCTION_LEADER submits, when attempted, then it is rejected with FORBIDDEN.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=WorkOrderQualityRatingServiceTest,WorkOrderQualityRatingServiceIntegrationTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full backend suite green (V1-V6 apply cleanly)
- OPA tests -- expected: all pass

**Manual checks (if no CLI):**
- Confirm V6 migration is additive-only (drop/re-add CHECK preserving all prior values)
- Confirm AuditEntityType.WORK_ORDER_QUALITY_RATING matches SQL CHECK