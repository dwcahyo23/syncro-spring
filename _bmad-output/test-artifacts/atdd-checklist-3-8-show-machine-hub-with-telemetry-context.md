---
storyId: "SPEC-3-8"
storyKey: "3-8-show-machine-hub-with-telemetry-context"
storyFile: "_bmad-output/implementation-artifacts/spec-3-8-show-machine-hub-with-telemetry-context.md"
atddChecklistPath: "_bmad-output/test-artifacts/atdd-checklist-3-8-show-machine-hub-with-telemetry-context.md"
generatedTestFiles: []
inputDocuments:
  - "_bmad-output/implementation-artifacts/spec-3-8-show-machine-hub-with-telemetry-context.md"
  - "_bmad-output/implementation-artifacts/epic-3-context.md"
  - "_bmad-output/implementation-artifacts/spec-3-7-show-latest-telemetry-dashboard.md"
stepsCompleted:
  - step-01-preflight-and-context
lastStep: step-01-preflight-and-context
lastSaved: 2026-08-10
---

# Red-Phase Acceptance Test Checklist — Story 3.8

## Meta

| Field | Value |
|-------|-------|
| **Story ID** | 3.8 |
| **Story Key** | 3-8-show-machine-hub-with-telemetry-context |
| **Title** | Show Machine Hub with Telemetry Context |
| **Route** | `/master-data/machines/[machineCode]` |
| **Phase** | RED (scaffolding, all tests skipped) |
| **Framework** | Playwright E2E (TypeScript) |
| **Communication Language** | Indonesian |

---

## Acceptance Criteria Coverage

### AC-1: Route & Navigation

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-001 | User navigates to `/master-data/machines/{machineCode}` and page loads | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-002 | Invalid machine code returns 404 error state | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-003 | Breadcrumb navigation shows: Dashboard > Master Data > Machines > {machineCode} | P1 | ⏭ SKIP |

### AC-2: Header Identity & Status

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-004 | Header displays machine code, name, and location | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-005 | Manual status badge shows ACTIVE (green) or INACTIVE (gray) | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-006 | Telemetry freshness badge shows ONLINE (green), STALE (yellow), or OFFLINE (red) | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-007 | Freshness indicator shows timestamp of last telemetry reception | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-008 | Refresh button reloads telemetry data manually | P2 | ⏭ SKIP |

### AC-3: Tab Navigation

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-009 | Tab navigation shows: Overview \| Telemetry \| Spareparts \| Alerts \| Audit Log | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-010 | Active tab is visually highlighted | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-011 | Clicking tab switches content without full page reload | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-012 | URL updates with hash fragment for tab state persistence | P3 | ⏭ SKIP |

### AC-4: Progressive Loading Performance

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-013 | Header renders first (<300ms TTFP target) | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-014 | Tab content loads progressively on tab click (<800ms target) | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-015 | Skeleton loaders shown during tab content fetch | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-016 | No waterfall request storm — headers then tabs sequentially | P2 | ⏭ SKIP |

### AC-5: Overview Tab Content

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-017 | Overview tab displays machine metadata form (read-only view) | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-018 | Shows: code, name, type, model, serial number, manufacturer | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-019 | Shows: installation date, commissioning date, warranty expiry | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-020 | Shows: responsible team/assignee with contact info | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-021 | Edit button navigates to edit page (if user has permission) | P2 | ⏭ SKIP |

### AC-6: Telemetry Tab Content

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-022 | Telemetry tab displays real-time sensor readings table | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-023 | Shows: sensor name, value, unit, timestamp, status | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-024 | Polling every 30s refreshes telemetry automatically | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-025 | Stale threshold warning appears after 5 min no telemetry | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-026 | Online machines show green dot indicator | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-027 | Inactive machines show banner: "Telemetry rejected — machine is INACTIVE" | P1 | ⏭ SKIP |

### AC-7: Spareparts Tab Content

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-028 | Spareparts tab shows installed spareparts list/table | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-029 | Each row shows: sparepart name, part number, installation date, remaining lifetime | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-030 | Lifetime progress bar visualizes remaining usage | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-031 | Empty state: "No spareparts installed" when list empty | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-032 | View details link opens sparepart detail modal/page | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-033 | Installation history available via machine-level API reference | P3 | ⏭ SKIP |

### AC-8: Alerts Tab Content

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-034 | Alerts tab displays active alerts for machine | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-035 | Each alert shows: severity, title, description, created timestamp | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-036 | Severity badges: CRITICAL (red), HIGH (orange), MEDIUM (yellow), LOW (blue) | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-037 | Click alert opens alert detail view | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-038 | Empty state: "No alerts yet" when no active alerts | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-039 | Filter by severity level available | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-040 | Sort by severity, timestamp, or status | P2 | ⏭ SKIP |

### AC-9: Audit Log Tab Content

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-041 | Audit Log tab displays machine-specific audit entries | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-042 | Shows: timestamp, actor, action, entity, field changes | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-043 | Dense sortable desktop table layout | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-044 | Expandable toggle for change detail view | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-045 | Before/After values shown for modified fields | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-046 | Empty state: "No audit entries yet" when list empty | P1 | ⏭ SKIP |

### AC-10: Plant Scope Enforcement

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-047 | Machine visible only if user has plant assignment for it | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-048 | Forbidden state: 403 response if user has no plant assignment | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-049 | Error message explains lack of access permissions | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-050 | All machine-related endpoints enforce plant scope middleware | P2 | ⏭ SKIP |

### AC-11: Mobile Responsive Design

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-051 | Simplified mobile layout on viewport < 768px | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-052 | Tab navigation becomes horizontal scroll on mobile | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-053 | Tables convert to stacked cards on mobile | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-054 | Touch targets minimum 44x44px for buttons/interactive elements | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-055 | Mobile-friendly scrollable content areas | P2 | ⏭ SKIP |

### AC-12: Error Handling

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-056 | ErrorBoundary component catches and displays errors gracefully | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-057 | Network failure shows retry button with backoff | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-058 | Partial load success shows available data + error summary | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-059 | Toast notification for non-critical errors | P3 | ⏭ SKIP |

### AC-13: Accessibility

| Test ID | Description | Priority | Status |
|---------|-------------|----------|--------|
| 3.8-ATDD-E2E-060 | Keyboard navigation works for all interactive elements | P1 | ⏭ SKIP |
| 3.8-ATDD-E2E-061 | Screen reader announces tab changes properly | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-062 | Focus indicators visible on all interactive elements | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-063 | ARIA labels for icon-only buttons | P2 | ⏭ SKIP |
| 3.8-ATDD-E2E-064 | Color contrast meets WCAG AA standards | P3 | ⏭ SKIP |

---

## Open Questions for GREEN Phase

1. Should the 5-minute stale threshold be configurable per plant? (Default: 5 min, max: 15 min)
2. What are the exact performance budgets for TTFP measurements?
3. Should polling interval adapt based on machine criticality?
4. Is breadcrumb depth sufficient or should drill-down be added?
5. Are touch target sizes adequate on all mobile devices tested?

---

## Implementation Notes

- **Component Reuse**: Leverage existing components from Story 3.7 ecosystem (`StatusBadge`, `Skeleton`, `EmptyState`, `ErrorBoundary`)
- **State Management**: TanStack Query handles cache management and revalidation
- **Auth Flow**: Requires authenticated SUPER_ADMIN session seeded before test execution
- **Data Seeding**: Machine data, telemetry, spareparts, alerts pre-seeded in test database
- **Viewport Testing**: Run both desktop (1920x1080) and mobile (375x700) viewports

---

## Usage Instructions

1. **Activate Tests**: Remove `.skip` from desired test cases when implementation is ready
2. **Preconditions**: 
   - Start app via `playwright.config.ts` webServer configuration
   - Seed test database with sample machine data
   - Create authenticated session for required roles
3. **Execution**:
   ```bash
   npm run test:e2e -- tests/e2e/machine-hub.atdd-red.spec.ts
   ```
4. **Reporting**: Generate HTML report with `npm run test:e2e:report`

---

*Generated by BMAD TEA atdd workflow — Indonesian communication style configured*
