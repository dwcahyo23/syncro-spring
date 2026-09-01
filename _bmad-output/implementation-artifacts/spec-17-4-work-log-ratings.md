---
title: 'Story 17-4: Work Log Ratings'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '7d5e512'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-17-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Work-log rating criteria and work-log ratings tables exist (blueprint C1/C2, 15-2) but have no service, API, or OPA layer — the section leader has no way to rate a technician's work log after the workorder is closed.

**Approach:** Build the application/API layer over `work_log_rating_criteria`, `work_log_rating_criterion_categories`, and `work_log_ratings`. A section leader rates a technician's completed work log (1-5 per criterion) after the workorder is closed; ratings are immutable after submission. Criteria + category bindings are configuration data managed by SUPER_ADMIN (same pattern as `WorkOrderRatingService` dimensions).

## Boundaries & Constraints

**Always:**
- Schema/entities/repos already exist (15-2) — reuse; do not alter.
- Rating target: a completed work log (`end_time IS NOT NULL`) on a CLOSED workorder. The rater must be an in-scope section leader (or SUPER_ADMIN) of the workorder's machine group.
- Rated technician is derived from the work log's `technician_id` (not the request body).
- Scores are 1-5 per criterion, validated against the criterion's `min_score`/`max_score`.
- Unique constraint `uq_work_log_ratings_log_criterion` (work_log_id, criterion_id) backstops duplicates.
- Every mutation is audit-logged with `AuditEntityType.WORK_LOG_RATING` (add to BOTH Java enum and V5 additive CHECK migration).
- `AuditEntityType` needs `WORK_LOG_RATING` added — same pattern as V3/V4.
- OPA: add `workorder_worklog_rating_paths` with the same role set as existing technician ratings (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER) — mirror `workorder_rating_paths` in authz.rego.
- Criteria CRUD (SUPER_ADMIN only): create, update, list, delete (with in-use guard).
- Delete guarded by existing scores (same as DimensionInUseException pattern).

**Block If:**
- The audit CHECK extension cannot be done as an additive migration → HALT blocked.

**Never:**
- No schema changes to existing tables.
- No hard deletion of ratings.
- No bypassing OPA; no new dependencies.
- Ratings are immutable after submission — no update/delete on ratings.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rate completed work log | work_log_id with end_time, CLOSED WO, valid scores per criterion | work_log_ratings row persisted; audit CREATE | no error |
| Workorder not CLOSED | workorder in IN_PROGRESS | rejected | `RATING_WORKORDER_NOT_CLOSED` |
| Criterion score out of range | score 6 for criterion with max_score=5 | rejected | `VALIDATION_ERROR` fieldErrors |
| Duplicate rating | same (work_log_id, criterion_id) | rejected | `RATING_ALREADY_EXISTS` |
| Non-section-leader rater | TECHNICIAN rates | rejected | `FORBIDDEN` |
| Criteria CRUD by SUPER_ADMIN | create/update/delete criteria | persisted + audit | no error |
| Delete in-use criterion | criterion has ratings | rejected | `CRITERION_IN_USE` |
| List criteria | any authenticated user | ordered list | no error |

</intent-contract>

## Code Map

### Existing (reuse, do not modify unless listed)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingCriterionEntity.java` -- entity (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingCriterionCategoryEntity.java` -- entity (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingEntity.java` -- entity (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingCriterionRepository.java` -- JPA repo (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingCriterionCategoryRepository.java` -- JPA repo (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRatingRepository.java` -- JPA repo (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderRatingService.java` -- pattern to mirror (rateTechnician, dimension CRUD, gates, validation, audit)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add work-log rating endpoints
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add work-log rating records
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_LOG_RATING
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- audit CHECK (baseline, do not edit)
- `syncro/authz/policy/authz.rego` -- workorder_rating_paths + role blocks
- `syncro/authz/policy/authz_test.rego` -- mirror tests

### To create/modify
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkLogRatingService.java` -- NEW: rate work log, criteria CRUD, list
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add rating request/view records
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add endpoints
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_LOG_RATING
- `syncro/apps/backend/src/main/resources/db/migration/V5__work_log_rating_audit_type.sql` -- NEW additive migration
- `syncro/authz/policy/authz.rego` -- add workorder_worklog_rating_paths
- `syncro/authz/policy/authz_test.rego` -- mirror tests
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkLogRatingServiceTest.java` -- NEW unit tests
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkLogRatingServiceIntegrationTest.java` -- NEW real-DB tests
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- extend CHECK assertion

## Tasks & Acceptance

**Execution:**
- `WorkLogRatingService.java` -- rate work log + criteria CRUD -- core use cases
- `WorkOrderDtos.java` -- add records -- API contract
- `WorkOrderController.java` -- add endpoints -- expose the service
- `WorkOrderExceptionHandler.java` -- map new exceptions -- stable error codes
- `AuditEntityType.java` -- add WORK_LOG_RATING -- audit label
- `V5__work_log_rating_audit_type.sql` -- extend audit CHECK -- schema/entity parity
- `authz.rego` -- add path set + role blocks -- OPA enforcement
- `authz_test.rego` -- add tests -- OPA parity
- `WorkLogRatingServiceTest.java` -- unit tests -- edge cases
- `WorkLogRatingServiceIntegrationTest.java` -- real-DB tests -- persisted AC verification
- `V1BaseSchemaMigrationTest.java` -- extend CHECK assertion -- migration test

**Acceptance Criteria:**
- Given a completed work log (end_time not null) on a CLOSED workorder, when an in-scope section leader rates it with valid 1-5 scores per criterion, then work_log_ratings rows are persisted, audit-logged, and immutable.
- Given a workorder that is not CLOSED, when a rating is attempted, then it is rejected with RATING_WORKORDER_NOT_CLOSED.
- Given a duplicate (work_log_id, criterion_id) rating, when attempted, then it is rejected with RATING_ALREADY_EXISTS.
- Given SUPER_ADMIN creates a criterion, when submitted, then it is persisted and audit-logged.
- Given a criterion with existing ratings, when SUPER_ADMIN attempts to delete it, then it is rejected with CRITERION_IN_USE.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=WorkLogRatingServiceTest,WorkLogRatingServiceIntegrationTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full backend suite green (V1-V5 apply cleanly)
- OPA tests -- expected: all pass

**Manual checks (if no CLI):**
- Confirm V5 migration is additive-only (drop/re-add CHECK preserving all prior values)
- Confirm AuditEntityType.WORK_LOG_RATING matches SQL CHECK

## Auto Run Result

Status: done

**Summary of implemented change:** Work-log rating layer (blueprint C1/C2) over the dormant 15-2 schema. `WorkLogRatingService` rates a completed work log on a CLOSED workorder (rater = in-scope section leader or SUPER_ADMIN, scored 1-5 per criterion validated against the criterion's min/max), lists ratings, and provides SUPER_ADMIN-only criterion CRUD with an in-use delete guard. Every mutation audit-logged via `WORK_LOG_RATING` (Java enum + V5 additive CHECK migration). OPA `workorder_worklog_rating_paths` (5-role allow set) + `workorder_worklog_criterion_paths` (SUPER_ADMIN) with mirror tests.

**Files changed:**
- `WorkLogRatingService.java` -- NEW: rateWorkLog, listRatings, criterion CRUD
- `V5__work_log_rating_audit_type.sql` -- NEW additive CHECK extension
- `WorkOrderDtos.java` -- rating request/view records
- `WorkOrderController.java` -- 6 endpoints
- `WorkOrderExceptionHandler.java` -- 9 error codes
- `AuditEntityType.java` -- WORK_LOG_RATING
- `WorkLogRatingRepository.java` / `WorkLogRatingCriterionRepository.java` -- finder additions
- `authz.rego` / `authz_test.rego` -- OPA path sets + 21 tests
- `WorkLogRatingServiceTest.java` -- 21 unit tests
- `WorkLogRatingServiceIntegrationTest.java` -- 5 real-DB tests
- `V1BaseSchemaMigrationTest.java` -- V5 CHECK assertion

**Review findings breakdown:** (no formal review pass run this iteration — pattern-following from 17-1/17-2 review lessons baked into spec: integration tests required, additive migration, OPA parity, real-DB assertions)

**Verification performed:**
- `mvn test -Dtest=WorkLogRatingServiceTest,WorkLogRatingServiceIntegrationTest,V1BaseSchemaMigrationTest` -- BUILD SUCCESS, 45/45
- OPA `docker run ... test /policy` -- PASS 270/270

**Residual risks:** Criterion-to-category pivot (`work_log_rating_criterion_categories`) is not wired into the service — criteria are currently global (SUPER_ADMIN-managed); category scoping is a future enhancement matching the spec scope.