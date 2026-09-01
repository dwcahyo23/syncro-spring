---
title: 'Story 16-4: AuthUser Extension & Plant Working Calendar'
type: 'feature'
created: '2026-09-01'
status: 'done'
baseline_revision: 'a1cabd9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-16-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** auth_users lacks the identity-hardening columns from blueprint A13 (phone_verified_at, force_password_change, failed_login_attempts, locked_at, lock_reason), so login cannot enforce lockout/verification state. plant_working_calendars/dates tables and entities exist from 15-2 but have no API to manage them, so shift-aware scheduling data cannot be configured.

**Approach:** Add an additive Flyway migration (V2) adding the A13 columns to auth_users (department_id already exists; plant scope stays on the auth_user_plant_assignments pivot — no duplicate plant_id column). Map the new columns on AuthUserEntity, enforce lockout in AuthService.login (increment failed_login_attempts on failure, reject when locked, reset on success, honor force_password_change in the auth user view). Add PlantWorkingCalendarService/Controller providing CRUD for plant_working_calendars and plant_working_calendar_dates with OPA gate + audit. Seed a GM1 calendar for the current year.

## Boundaries & Constraints

**Always:**
- Additive migration only — `V2__auth_user_hardening.sql`; never edit V1.
- Login lockout: after MAX_FAILED_LOGIN_ATTEMPTS (configurable constant, default 5) consecutive failures, set locked_at; while locked_at is set, login is rejected with `ACCOUNT_LOCKED`; a successful login clears failed_login_attempts and locked_at.
- AuthUserView gains phoneVerifiedAt, forcePasswordChange, failedLoginAttempts, lockedAt, lockReason fields.
- Calendar mutations require SUPER_ADMIN|MANAGER_MAINTENANCE (OPA department_paths-like gate); reads any authenticated user.
- Calendar CRUD audit-logged with PLANT_WORKING_CALENDAR entity type (exists in enum + V1 CHECK).
- `department_id` relation already exists; do NOT add plant_id to auth_users (scope lives on the plant-assignment pivot per AD-2).

**Block If:**
- V2 cannot apply cleanly on top of V1 → HALT blocked.

**Never:**
- No changes to V1.
- No changes to the multi-plant assignment model.
- No frontend work (calendar UI is not in this story's scope; backend only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Login success | correct password, not locked | token issued; failed_login_attempts reset to 0 | no error |
| Login failure | wrong password, not locked | 401; failed_login_attempts incremented | BAD_CREDENTIALS |
| Lockout reached | 5th consecutive failure | locked_at set; login rejected | ACCOUNT_LOCKED |
| Locked login | locked_at set | login rejected regardless of password | ACCOUNT_LOCKED |
| Calendar create | valid plantId + year + workweekMode | 201; row persisted; audit written | 400 VALIDATION_ERROR |
| Duplicate calendar | same (plantId, year) | 400 duplicate | uq_plant_working_calendars_plant_year |
| Calendar date add | valid calendar + date | persisted; unique (calendar, date) | 400 duplicate |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- DO NOT EDIT; V2 goes next to it
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java` -- add phoneVerifiedAt, forcePasswordChange, failedLoginAttempts, lockedAt, lockReason + getters + resetLoginFailures()/recordLoginFailure(int max)/lock()
- `syncro/apps/backend/src/main/java/com/syncro/auth/application/AuthService.java` -- login lockout logic; toView gains new fields
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthDtos.java` -- AuthUserView gains new fields
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/PlantWorkingCalendarEntity.java` -- add update()/deactivate-style methods as needed
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/PlantWorkingCalendarDateEntity.java` -- add update() if needed
- `syncro/apps/backend/src/main/java/com/syncro/org/application/PlantWorkingCalendarService.java` -- NEW: list/get/create/update calendar + add/remove dates; audit; mutation gate
- `syncro/apps/backend/src/main/java/com/syncro/org/api/PlantWorkingCalendarController.java` + Dtos + ExceptionHandler -- NEW REST surface at /api/v1/plant-working-calendars
- `syncro/authz/policy/authz.rego` -- add /api/v1/plant-working-calendars paths to department_paths
- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- seed a GM1 calendar for current year (idempotent)
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- possibly add V2 applied assertion (keep existing V1 assertions green)
- `syncro/apps/backend/src/test/java/com/syncro/org/application/PlantWorkingCalendarServiceTest.java` -- NEW
- `syncro/apps/backend/src/test/java/com/syncro/org/api/PlantWorkingCalendarControllerTest.java` -- NEW
- `syncro/apps/backend/src/test/java/com/syncro/auth/application/AuthServiceTest.java` -- lockout behavior

## Tasks & Acceptance

**Execution:**
1. Write `V2__auth_user_hardening.sql` (additive columns + partial default backfill)
2. AuthUserEntity + AuthService.login lockout + AuthDtos.AuthUserView
3. PlantWorkingCalendarService + Controller + Dtos + ExceptionHandler
4. rego: add calendar paths
5. Seed GM1 calendar
6. Tests: AuthServiceTest lockout cases, PlantWorkingCalendarServiceTest/ControllerTest, PilotSeedTest calendar count

**Acceptance Criteria:**
- Given a user with locked_at set, when login is attempted with correct password, then 401 ACCOUNT_LOCKED and no token issued.
- Given 5 consecutive failed logins, when the 5th fails, then locked_at is set and subsequent logins are rejected.
- Given a successful login after failures, when the user authenticates, then failed_login_attempts resets to 0 and locked_at clears.
- Given a SUPER_ADMIN, when creating a plant working calendar and adding dates, then rows persist and audit rows are written (PLANT_WORKING_CALENDAR).
- Given a duplicate (plantId, year) calendar, when creating again, then 400 duplicate error.
- Given the pilot seed, when applied twice, then exactly one GM1 calendar row exists.

## Spec Change Log

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: BUILD SUCCESS.
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=AuthServiceTest,PlantWorkingCalendarServiceTest,PlantWorkingCalendarControllerTest,PilotSeedTest"` -- expected: all green.
