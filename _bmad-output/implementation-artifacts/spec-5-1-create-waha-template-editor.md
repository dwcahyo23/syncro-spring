# Story 5.1: Create WAHA Template Editor

Status: review

## Story

As a SUPER_ADMIN or MANAGE user,
I want to create and edit WAHA WhatsApp alert message templates,
so that alert notifications use consistent operational text.

## Acceptance Criteria

1. **Given** user has role `SUPER_ADMIN` or `MANAGE`  
   **When** user opens the WAHA Templates page (`/dashboard/waha-templates`)  
   **Then** current active template is loaded and displayed in the editor (or empty state if none)

2. **Given** user is in the editor  
   **When** user edits template text and saves  
   **Then** template text is persisted in PostgreSQL (`waha_templates` table)  
   **And** success toast is shown ("Template saved")  
   **And** updated template is reflected in the preview

3. **Given** user is in the editor  
   **When** user uses the variable picker dropdown  
   **Then** selected variable is inserted at cursor position in the textarea  
   **And** supported variables are: `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, `{alertTime}`

4. **Given** user types an unknown variable (e.g. `{unknownField}`)  
   **When** template text is validated  
   **Then** inline validation error is shown identifying the unknown variable  
   **And** save is blocked until error is resolved

5. **Given** template text contains valid variables  
   **When** preview is rendered  
   **Then** preview shows sample data substituted safely (no XSS, no raw variable tokens leaked)  
   **And** preview is rendered client-side from sample data (no additional backend call required)

6. **Given** no template exists in PostgreSQL  
   **When** backend starts or the endpoint is first called  
   **Then** a default starter template exists (seeded via Flyway `V21__seed_default_waha_template.sql`)  
   **And** notification queueing (Story 5.2) is not blocked by missing template

7. **Given** user has role `VIEWER`  
   **When** user opens the WAHA Templates page  
   **Then** template text is shown in read-only mode  
   **And** save action is not available

8. **Given** user does NOT have any permitted role  
   **When** user attempts to access the WAHA Templates page  
   **Then** `RoleGuard` renders forbidden state (403-style)

9. **Given** backend call is in-flight  
   **When** template is loading  
   **Then** loading skeleton is shown

10. **Given** backend call fails  
    **When** template cannot be loaded  
    **Then** error state is shown with retry action

## Tasks / Subtasks

- [ ] **Task 1: Flyway migration — create `waha_templates` table** (AC: 1, 6)
  - [ ] Create `V21__create_waha_templates.sql` under `syncro/apps/backend/src/main/resources/db/migration/`
  - [ ] Schema: `id UUID PK`, `template_key VARCHAR(64) UNIQUE NOT NULL`, `body TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`
  - [ ] Index on `template_key`

- [ ] **Task 2: Flyway seed — default starter template** (AC: 6)
  - [ ] Create `V22__seed_default_waha_template.sql`
  - [ ] Insert one row with `template_key = 'alert_notification'` and a sensible default body using all 8 supported variables

- [ ] **Task 3: Backend — notification module domain + infrastructure** (AC: 1, 2, 6)
  - [ ] Create package `com.syncro.notification` with sub-packages `api/`, `application/`, `domain/`, `infrastructure/`
  - [ ] `WahaTemplate` domain record: `id`, `templateKey`, `body`, `createdAt`, `updatedAt`
  - [ ] `WahaTemplateEntity` JPA entity mapped to `waha_templates` — follow `SparepartAlertEntity` pattern (Jakarta persistence, no `javax.*`)
  - [ ] `WahaTemplateRepository` Spring Data JPA interface with `findByTemplateKey(String key): Optional<WahaTemplateEntity>`

- [ ] **Task 4: Backend — application service** (AC: 1, 2, 6)
  - [ ] `WahaTemplateService` with:
    - `getActiveTemplate(): WahaTemplate` — load by key `alert_notification`, throw `WahaTemplateNotFoundException` if missing
    - `upsertTemplate(String body, AuthenticatedUser actor): WahaTemplate` — validate body (no unknown variables), persist, return updated view
  - [ ] Variable validation: known set = `{machineCode}`, `{machineName}`, `{plantCode}`, `{machineGroup}`, `{sparepartName}`, `{thresholdPercent}`, `{currentCount}`, `{alertTime}` — regex scan for `\{[^}]+\}` tokens, reject unknowns with `WahaTemplateValidationException` listing offending tokens

- [ ] **Task 5: Backend — REST controller + DTOs + exception handler** (AC: 1, 2, 7, 8)
  - [ ] `WahaTemplateController` at `@RequestMapping("/api/v1/notification/templates")`
    - `GET /` → `200 WahaTemplateView` (roles: `SUPER_ADMIN`, `MANAGE`, `VIEWER`)
    - `PUT /` → `200 WahaTemplateView` (roles: `SUPER_ADMIN`, `MANAGE` only; `VIEWER` → `403`)
  - [ ] `WahaTemplateDtos`: `WahaTemplateView(id, templateKey, body, updatedAt)`, `UpsertTemplateRequest(@NotBlank String body)`
  - [ ] `WahaTemplateExceptionHandler` (`@RestControllerAdvice(assignableTypes = WahaTemplateController.class)`, `@Order(HIGHEST_PRECEDENCE)`)
    - `WahaTemplateNotFoundException` → 404 `TEMPLATE_NOT_FOUND`
    - `WahaTemplateValidationException` → 400 `TEMPLATE_INVALID_VARIABLES` with `fieldErrors` listing each unknown token
    - `MethodArgumentNotValidException` → 400 `VALIDATION_ERROR`
  - [ ] `ErrorResponse` record follows existing shape: `code`, `message`, `fieldErrors`, `timestamp`, `traceId`
  - [ ] Security: enforce role check in service (use `AuthenticatedUser` principal like `SparepartAlertController`)

- [ ] **Task 6: Backend — OpenAPI / orval codegen** (AC: 1, 2)
  - [ ] Add `operationId = "getActiveWahaTemplate"` and `operationId = "upsertWahaTemplate"` to controller methods with `@Operation` + `@ApiResponses`
  - [ ] Run `mvn spring-boot:run` (or equivalent) to regenerate OpenAPI spec, then run orval codegen on frontend: `npm run generate` (or equivalent) in `syncro/apps/web/`
  - [ ] Confirm generated hooks `useGetActiveWahaTemplate` and `useUpsertWahaTemplate` appear in `src/lib/api/generated/syncro.ts`

- [ ] **Task 7: Frontend — `WahaTemplateEditor` shared component** (AC: 2, 3, 4, 5, 7)
  - [ ] Create `syncro/apps/web/src/components/syncro/waha-template-editor.tsx`
  - [ ] Implement props interface exactly as spec'd in `frontend-hardening-specification.md:465`:
    ```typescript
    interface WahaTemplateEditorProps {
      value: string;
      onChange: (value: string) => void;
      availableVariables: string[];
      preview?: string;
      onSave?: () => void;
      readOnly?: boolean;
    }
    ```
  - [ ] Built from: shadcn `Textarea` + shadcn `DropdownMenu` (variable picker inserts at cursor) + shadcn `Card` (preview panel)
  - [ ] Variable validation inline: regex `\{[^}]+\}` → filter against `availableVariables` → show inline error per unknown token below textarea
  - [ ] Preview panel: client-side substitution using hardcoded sample data map; render as plain `<pre>` or `<p>` (no HTML injection)
  - [ ] Save button disabled when: (a) validation errors present, (b) `readOnly=true`, (c) save in-flight
  - [ ] Success feedback: call `sonner` `toast.success("Template saved")` on successful save

- [ ] **Task 8: Frontend — `waha-templates` feature page content** (AC: 1, 2, 7, 8, 9, 10)
  - [ ] Create `syncro/apps/web/src/features/waha-templates/waha-template-page-content.tsx`
  - [ ] Use `useGetActiveWahaTemplate` hook (staleTime: 30_000, retry logic matching alerts pattern)
  - [ ] Use `useUpsertWahaTemplate` mutation for save
  - [ ] Handle states: loading (Skeleton), error (retry button), forbidden (403 message), read-only (`VIEWER`), editable (`SUPER_ADMIN`/`MANAGE`)
  - [ ] Render `WahaTemplateEditor` with `availableVariables` list hardcoded from the 8 known tokens
  - [ ] Client-side preview: sample data map:
    ```
    machineCode = "BF-08410", machineName = "JBF19", plantCode = "GM1",
    machineGroup = "Forming", sparepartName = "Electric PLC Wecon LX5",
    thresholdPercent = "90", currentCount = "9450", alertTime = "2026-08-20 08:00"
    ```

- [ ] **Task 9: Frontend — update `waha-templates` page route** (AC: 7, 8)
  - [ ] Update `syncro/apps/web/src/app/(main)/dashboard/waha-templates/page.tsx`
  - [ ] Replace `ModulePlaceholder` with `WahaTemplatePageContent`
  - [ ] Update `RoleGuard allowedRoles` to include `["SUPER_ADMIN", "MANAGE", "VIEWER"]` (guard handles forbidden rendering for others; read-only enforced in content)

- [ ] **Task 10: Backend integration test** (AC: 1, 2, 4, 6, 7)
  - [ ] Create `syncro/apps/backend/src/test/java/com/syncro/notification/WahaTemplateControllerTest.java`
  - [ ] Test cases (follow `IntegrationTestSupport` pattern):
    - `GET /api/v1/notification/templates` → 200 with default template body
    - `PUT /api/v1/notification/templates` with valid body → 200, body persisted
    - `PUT /api/v1/notification/templates` with unknown variable `{badVar}` → 400 `TEMPLATE_INVALID_VARIABLES`
    - `PUT /api/v1/notification/templates` as `VIEWER` → 403
    - `GET /api/v1/notification/templates` unauthenticated → 401

## Dev Notes

### Backend Module Structure

Follow the exact 4-layer package pattern used by `alert/`:
```
com.syncro.notification/
  api/         → WahaTemplateController, WahaTemplateDtos, WahaTemplateExceptionHandler
  application/ → WahaTemplateService (exceptions as inner classes)
  domain/      → WahaTemplate (record), known variable set constant
  infrastructure/ → WahaTemplateEntity, WahaTemplateRepository
```

### Key Backend Patterns

- **Jakarta namespace** — all `import jakarta.persistence.*` (Spring Boot 4, no `javax.*`)
- **JPA Entity pattern** — `protected` no-arg constructor, all-args constructor, no Lombok, explicit getters like `SparepartAlertEntity`
- **Error shape** — `record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp, String traceId)` matching `SparepartAlertExceptionHandler:64`
- **Exception handler** — `@Order(Ordered.HIGHEST_PRECEDENCE)`, `@RestControllerAdvice(assignableTypes = WahaTemplateController.class)`, inject `Clock`
- **Auth principal** — `@AuthenticationPrincipal AuthenticatedUser user` on PUT endpoint
- **Spring Security** — enforce role via `hasRole(...)` in SecurityConfig or via explicit check in service; `VIEWER` must get 403 on PUT
- **Flyway naming** — highest existing migration is `V20__create_machine_counter_states.sql` → use `V21__` and `V22__`

### Variable Validation Logic

```java
private static final Set<String> KNOWN_VARIABLES = Set.of(
    "{machineCode}", "{machineName}", "{plantCode}", "{machineGroup}",
    "{sparepartName}", "{thresholdPercent}", "{currentCount}", "{alertTime}"
);

// Scan body with: Pattern.compile("\\{[^}]+\\}")
// Collect all tokens, filter out KNOWN_VARIABLES → unknown set
// If non-empty → throw WahaTemplateValidationException(unknownTokens)
```

### Frontend Patterns

- **API hooks** — generated by orval from `src/lib/api/generated/syncro.ts`; use `useGetActiveWahaTemplate` + `useUpsertWahaTemplate`; follow `useListAlerts` retry/staleTime pattern (`alert-list-page-content.tsx:22-37`)
- **Error handling** — `SyncroApiError` check for 403/401 like `alert-list-page-content.tsx:52`
- **Toast** — use `sonner` (already in stack); `toast.success("Template saved")`
- **Component location** — `src/components/syncro/waha-template-editor.tsx` (matches `frontend-hardening-specification.md:463`)
- **Feature page** — `src/features/waha-templates/waha-template-page-content.tsx`
- **Route page** — `src/app/(main)/dashboard/waha-templates/page.tsx` (already exists as placeholder)
- **RoleGuard** — update to `["SUPER_ADMIN", "MANAGE", "VIEWER"]`; read-only enforced by passing `readOnly={role === "VIEWER"}` to content component
- **No new dependencies** — use only existing: shadcn Textarea, DropdownMenu, Card, Skeleton, sonner

### Variable Picker — Cursor Insertion

Use `useRef<HTMLTextAreaElement>` + `selectionStart`/`selectionEnd` to insert variable at cursor:
```typescript
const insertVariable = (variable: string) => {
  const el = textareaRef.current;
  if (!el) return;
  const start = el.selectionStart ?? value.length;
  const end = el.selectionEnd ?? value.length;
  const next = value.slice(0, start) + variable + value.slice(end);
  onChange(next);
  // restore cursor after React re-render
  requestAnimationFrame(() => {
    el.setSelectionRange(start + variable.length, start + variable.length);
    el.focus();
  });
};
```

### Client-Side Preview Sample Data

```typescript
const PREVIEW_SAMPLE: Record<string, string> = {
  machineCode: "BF-08410",
  machineName: "JBF19",
  plantCode: "GM1",
  machineGroup: "Forming",
  sparepartName: "Electric PLC Wecon LX5",
  thresholdPercent: "90",
  currentCount: "9450",
  alertTime: "2026-08-20 08:00",
};

const renderPreview = (template: string) =>
  template.replace(/\{(\w+)\}/g, (_, key) => PREVIEW_SAMPLE[key] ?? `{${key}}`);
```

### Project Structure Notes

- **New backend package**: `com.syncro.notification` — does NOT exist yet; create from scratch
- **New frontend feature**: `src/features/waha-templates/` — does NOT exist yet
- **Existing page**: `src/app/(main)/dashboard/waha-templates/page.tsx` — EXISTS as placeholder, update it
- **Existing component dir**: `src/components/syncro/` — add `waha-template-editor.tsx` here
- **Flyway V21/V22**: next available after V20 (`V20__create_machine_counter_states.sql`)
- **orval codegen**: must be run after backend OpenAPI annotation added; check `package.json` for the generate script name

### References

- Epic 5.1 AC: [Source: `_bmad-output/planning-artifacts/epics.md#Story 5.1`]
- WahaTemplateEditor interface: [Source: `_bmad-output/planning-artifacts/frontend-hardening-specification.md#4.14`]
- WahaTemplateEditor UX spec: [Source: `_bmad-output/planning-artifacts/ux-design-specification.md#WahaTemplateEditor`]
- Backend module pattern: [Source: `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java`]
- Entity pattern: [Source: `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java`]
- Exception handler pattern: [Source: `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertExceptionHandler.java`]
- Frontend API hook pattern: [Source: `syncro/apps/web/src/features/alerts/alert-list-page-content.tsx:22-37`]
- Flyway migration pattern: [Source: `syncro/apps/backend/src/main/resources/db/migration/V19__create_sparepart_alerts.sql`]
- Architecture module map: [Source: `_bmad-output/planning-artifacts/architecture.md#WAHA Notifications`]
- Frontend route: [Source: `_bmad-output/planning-artifacts/app-shell-navigation-specification.md` — `/waha-templates`]
- FR-056/FR-063: WYSIWYG WAHA template editor requirement
- UX-DR-018: WahaTemplateEditor component with 8 variables
- AR-013, AR-017, AR-018: covered by this story

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
