---
title: 'Create WAHA Template Editor'
type: 'feature'
created: '2026-08-20'
status: 'review'
review_loop_iteration: 0
baseline_commit: 'f06bd01bcce3607f64462117bf44ec8f840bb2ac'
context:
  - '_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** No WAHA WhatsApp message template exists in the system, so notification queueing (Story 5.2) is blocked and admins have no way to configure the alert message text sent to technicians.

**Approach:** Build the full vertical slice — Flyway migration for `waha_templates` table + default seed, a new `com.syncro.notification` backend module (4-layer: api/application/domain/infrastructure) with REST endpoints, and a `WahaTemplateEditor` frontend component wired into the existing `/dashboard/waha-templates` placeholder page.

## Boundaries & Constraints

**Always:**
- Jakarta namespace only (`jakarta.persistence.*`) — Spring Boot 4, no `javax.*`
- Flyway V21 and V22 are next available (V20 = `create_machine_counter_states`)
- Backend module lives at `com.syncro.notification` — does not exist yet, create from scratch
- Known variable set is exactly 8: `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, `{alertTime}`
- Error response shape: `record ErrorResponse(String code, String message, Map<String,String> fieldErrors, String timestamp, String traceId)`
- Frontend: no new npm dependencies — use existing shadcn Textarea, DropdownMenu, Card, Skeleton, sonner
- orval codegen must run after controller annotations added

**Ask First:**
- If security role enforcement for PUT should go in SecurityConfig filter or in service layer (current pattern uses service-layer check — follow that unless blocked)
- If `VIEWER` role should see the WAHA Templates page at all (current page.tsx uses `SUPER_ADMIN` only — story AC says VIEWER can view read-only)

**Never:**
- Do not implement notification job queueing, escalation, or WAHA HTTP calls (Story 5.2+)
- Do not render preview as HTML — plain text `<pre>` only (no XSS risk)
- Do not add new UI libraries beyond existing stack
- Do not modify existing Flyway migrations

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| GET template — default exists | GET `/api/v1/notification/templates` authenticated | 200 with seeded default body | N/A |
| GET template — unauthenticated | No JWT | 401 | Spring Security intercepts |
| PUT valid template | Body with all known variables | 200, persisted, success toast | N/A |
| PUT unknown variable | Body contains `{badVar}` | 400 `TEMPLATE_INVALID_VARIABLES`, fieldErrors listing `{badVar}` | Inline error shown in editor |
| PUT as VIEWER | VIEWER role | 403 `FORBIDDEN` | Editor in read-only, no save shown |
| PUT empty body | `body: ""` | 400 `VALIDATION_ERROR` (`@NotBlank`) | Inline error |
| Preview render | Template with `{machineCode}` | Shows `BF-08410` (sample data) | Unknown token rendered as-is |
| Page load error | Backend unavailable | Error state + retry button | `SyncroApiError` handled |

</frozen-after-approval>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V21__create_waha_templates.sql` -- NEW: schema for waha_templates table
- `syncro/apps/backend/src/main/resources/db/migration/V22__seed_default_waha_template.sql` -- NEW: default alert_notification template seed
- `syncro/apps/backend/src/main/java/com/syncro/notification/domain/WahaTemplate.java` -- NEW: domain record
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaTemplateEntity.java` -- NEW: JPA entity, follows SparepartAlertEntity pattern
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaTemplateRepository.java` -- NEW: Spring Data JPA interface
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/WahaTemplateService.java` -- NEW: get + upsert with variable validation
- `syncro/apps/backend/src/main/java/com/syncro/notification/api/WahaTemplateDtos.java` -- NEW: WahaTemplateView, UpsertTemplateRequest
- `syncro/apps/backend/src/main/java/com/syncro/notification/api/WahaTemplateController.java` -- NEW: GET + PUT at /api/v1/notification/templates
- `syncro/apps/backend/src/main/java/com/syncro/notification/api/WahaTemplateExceptionHandler.java` -- NEW: exception handler, follows SparepartAlertExceptionHandler pattern
- `syncro/apps/backend/src/test/java/com/syncro/notification/WahaTemplateControllerTest.java` -- NEW: integration tests
- `syncro/apps/web/src/components/syncro/waha-template-editor.tsx` -- NEW: shared editor component
- `syncro/apps/web/src/features/waha-templates/waha-template-page-content.tsx` -- NEW: page content with API hooks
- `syncro/apps/web/src/app/(main)/dashboard/waha-templates/page.tsx` -- MODIFY: replace ModulePlaceholder, update RoleGuard roles

## Tasks & Acceptance

**Execution:**
- [ ] `V21__create_waha_templates.sql` -- CREATE TABLE waha_templates (id UUID PK, template_key VARCHAR(64) UNIQUE NOT NULL, body TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL) + index on template_key
- [ ] `V22__seed_default_waha_template.sql` -- INSERT default row: template_key='alert_notification', body using all 8 variables with sensible operational text
- [ ] `WahaTemplate.java` -- domain record: id UUID, templateKey String, body String, createdAt Instant, updatedAt Instant
- [ ] `WahaTemplateEntity.java` -- JPA @Entity @Table(name="waha_templates"), Jakarta imports, protected no-arg ctor, all-args ctor, explicit getters, no Lombok
- [ ] `WahaTemplateRepository.java` -- extends JpaRepository<WahaTemplateEntity, UUID>, add findByTemplateKey(String): Optional<WahaTemplateEntity>
- [ ] `WahaTemplateService.java` -- getActiveTemplate() loads by key "alert_notification" or throws WahaTemplateNotFoundException; upsertTemplate() validates variables via regex \{[^}]+\} against KNOWN_VARIABLES set, throws WahaTemplateValidationException(Set<String> unknownTokens) if any unknown; persists via save(); inner exception classes
- [ ] `WahaTemplateDtos.java` -- record WahaTemplateView(UUID id, String templateKey, String body, Instant updatedAt); record UpsertTemplateRequest(@NotBlank String body)
- [ ] `WahaTemplateController.java` -- @RestController @RequestMapping("/api/v1/notification/templates"); GET "/" operationId="getActiveWahaTemplate" roles SUPER_ADMIN+MANAGE+VIEWER; PUT "/" operationId="upsertWahaTemplate" roles SUPER_ADMIN+MANAGE only; inject @AuthenticationPrincipal AuthenticatedUser; @Operation + @ApiResponses on each method
- [ ] `WahaTemplateExceptionHandler.java` -- @Order(HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes=WahaTemplateController.class); handle WahaTemplateNotFoundException→404, WahaTemplateValidationException→400 with fieldErrors, MethodArgumentNotValidException→400, inject Clock
- [ ] `WahaTemplateControllerTest.java` -- integration tests: GET returns default; PUT valid→200; PUT unknown var→400 TEMPLATE_INVALID_VARIABLES; PUT as VIEWER→403; GET unauthenticated→401; extend IntegrationTestSupport
- [ ] run orval codegen -- after backend compiles, regenerate frontend API client: verify useGetActiveWahaTemplate + useUpsertWahaTemplate hooks present in src/lib/api/generated/syncro.ts
- [ ] `waha-template-editor.tsx` -- WahaTemplateEditorProps {value, onChange, availableVariables, preview?, onSave?, readOnly?}; Textarea with useRef for cursor insertion; DropdownMenu variable picker calling insertVariable(); inline validation showing unknown tokens; preview Card with client-side renderPreview(); save button disabled on errors/readOnly/in-flight; sonner toast.success on save
- [ ] `waha-template-page-content.tsx` -- "use client"; useGetActiveWahaTemplate (staleTime:30_000, retry pattern from alert-list-page-content); useUpsertWahaTemplate mutation; local state for draft body; handle loading/error/forbidden/readOnly states; pass PREVIEW_SAMPLE-rendered string as preview prop; KNOWN_VARIABLES array as availableVariables
- [ ] `page.tsx` (waha-templates) -- replace ModulePlaceholder with WahaTemplatePageContent; update RoleGuard allowedRoles to ["SUPER_ADMIN","MANAGE","VIEWER"]; pass role to content for readOnly determination

**Acceptance Criteria:**
- Given authenticated SUPER_ADMIN, when GET /api/v1/notification/templates, then 200 with default template body containing all 8 variables
- Given body with `{unknownVar}`, when PUT /api/v1/notification/templates, then 400 with code TEMPLATE_INVALID_VARIABLES and fieldErrors listing the unknown token
- Given VIEWER role, when PUT /api/v1/notification/templates, then 403
- Given unauthenticated request, when GET /api/v1/notification/templates, then 401
- Given valid body, when SUPER_ADMIN saves template, then success toast "Template saved" appears and preview updates
- Given page loads with VIEWER role, then editor textarea is read-only and save button is absent

## Spec Change Log

## Design Notes

**Variable insertion at cursor:**
```typescript
const insertVariable = (variable: string) => {
  const el = textareaRef.current;
  if (!el) return;
  const start = el.selectionStart ?? value.length;
  const end = el.selectionEnd ?? value.length;
  onChange(value.slice(0, start) + variable + value.slice(end));
  requestAnimationFrame(() => {
    el.setSelectionRange(start + variable.length, start + variable.length);
    el.focus();
  });
};
```

**Client-side preview sample data (pilot machine):**
```typescript
const PREVIEW_SAMPLE: Record<string, string> = {
  machineCode: "BF-08410", machineName: "JBF19", plantCode: "GM1",
  machineGroup: "Forming", sparepartName: "Electric PLC Wecon LX5",
  thresholdPercent: "90", currentCount: "9450", alertTime: "2026-08-20 08:00",
};
const renderPreview = (t: string) =>
  t.replace(/\{(\w+)\}/g, (_, k) => PREVIEW_SAMPLE[k] ?? `{${k}}`);
```

**Backend variable validation:**
```java
private static final Set<String> KNOWN_VARIABLES = Set.of(
    "{machineCode}","{machineName}","{plantCode}","{machineGroup}",
    "{sparepartName}","{thresholdPercent}","{currentCount}","{alertTime}");
// scan with Pattern.compile("\\{[^}]+\\}"), collect, removeAll(KNOWN_VARIABLES)
```

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn test -pl . -Dtest=WahaTemplateControllerTest` -- expected: all 5 test cases green
- `cd syncro/apps/backend && mvn compile` -- expected: BUILD SUCCESS, no Jakarta/javax errors
- `cd syncro/apps/web && npm run build` -- expected: no TypeScript errors, no missing import errors
- `cd syncro/apps/web && npm run lint` -- expected: no biome lint errors in new files
