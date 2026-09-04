import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AnalyticsPageContent } from "./analytics-page-content";

// ---------------------------------------------------------------------------
// Module-level mocks
// ---------------------------------------------------------------------------

let mockScope: {
  mode: string;
  availablePlants: Array<{ id: string; code: string; name: string }>;
  emptyReason: string | null;
} | null = {
  mode: "ASSIGNED",
  availablePlants: [{ id: "p1", code: "P1", name: "Plant 1" }],
  emptyReason: null,
};
let mockActivePlantId = "all";
let mockLoadError = false;

vi.mock("@/features/plant-scope/plant-scope-store", () => ({
  usePlantScope: () => ({
    scope: mockScope,
    activePlantId: mockActivePlantId,
    loadError: mockLoadError,
  }),
}));

let mockMtbfData: {
  mtbf: { status: "AVAILABLE" | "INSUFFICIENT_DATA"; valueHours: number | null; workorderCount: number };
  mttr: { status: "AVAILABLE" | "INSUFFICIENT_DATA"; valueHours: number | null; workorderCount: number };
  windowFrom: string;
  windowTo: string;
  computedAt: string;
  cacheAgeMs: number | null;
  stale: boolean;
} | null = null;
let mockMtbfLoading = false;
let mockMtbfError = false;
const mockMtbfRefetch = vi.fn();

vi.mock("@/features/analytics/hooks/use-mtbf-mttr", () => ({
  useMtbfMttr: () => ({
    data: mockMtbfData,
    isLoading: mockMtbfLoading,
    isError: mockMtbfError,
    isFetching: false,
    refetch: mockMtbfRefetch,
  }),
}));

let mockKpiData: {
  technicians: Array<{
    technicianId: string;
    technicianName: string;
    completedCount: number;
    averageMttrHours: number | null;
    onTimePercentage: number | null;
    ratings: Array<{ dimensionId: string; dimensionCode: string; dimensionLabel: string; averageScore: number | null }>;
  }>;
  windowFrom: string;
  windowTo: string;
  computedAt: string;
  cacheAgeMs: number | null;
  stale: boolean;
} | null = null;
let mockKpiLoading = false;
let mockKpiError = false;
const mockKpiRefetch = vi.fn();

vi.mock("@/features/analytics/hooks/use-technician-kpi", () => ({
  useTechnicianKpi: () => ({
    data: mockKpiData,
    isLoading: mockKpiLoading,
    isError: mockKpiError,
    isFetching: false,
    refetch: mockKpiRefetch,
  }),
}));

// ---------------------------------------------------------------------------
// Story 20-2: materialized monthly KPI + target hooks (contract-shaped fixtures)
// ---------------------------------------------------------------------------

type MonthlyTargetStatus = "ON_TARGET" | "BELOW_TARGET" | "ABOVE_TARGET" | "NO_TARGET" | "INSUFFICIENT_DATA";

interface MonthlyResponse {
  type: string;
  month: string;
  status: "AVAILABLE" | "INSUFFICIENT_DATA";
  mtbfRows: Array<{
    plantId: string;
    machineId: string;
    mtbfDays: number | null;
    targetValue: number | null;
    targetStatus: MonthlyTargetStatus;
  }>;
  mttrRows: Array<{
    plantId: string;
    wallClockMinutes: number | null;
    actualWorkingMinutes: number | null;
    targetValue: number | null;
    targetStatus: MonthlyTargetStatus;
  }>;
  marRows: Array<{
    plantId: string;
    plannedAvailableMinutes: number;
    downtimeMinutes: number;
    marPercent: number | null;
    sourceStatus: string | null;
    sourceMessage: string | null;
    targetValue: number | null;
    targetStatus: MonthlyTargetStatus;
  }>;
  pmCompletionRows: Array<{
    plantId: string;
    completionRate: number | null;
    completedCount: number;
    plannedCount: number;
    sourceStatus: string | null;
    sourceMessage: string | null;
    targetValue: number | null;
    targetStatus: MonthlyTargetStatus;
  }>;
  technicianRows: Array<{
    plantId: string;
    technicianId: string;
    averageRating: number | null;
    totalWo: number;
    firstTimeFixRate: number | null;
    targetValue: number | null;
    targetStatus: MonthlyTargetStatus;
  }>;
  breakdownRows: Array<{
    plantId: string;
    count: number;
    targetValue: number | null;
    targetStatus: MonthlyTargetStatus;
  }>;
  target: {
    plantId: string;
    month: string;
    monthlyBreakdownTarget: number | null;
    mtbfTargetDays: number | null;
    mttrTargetMinutes: number | null;
    oeeQualityPercent: number | null;
    oeePerformancePercent: number | null;
  } | null;
  refresh: {
    refreshKey: string;
    status: "RUNNING" | "SUCCESS" | "FAILED";
    refreshedAt: string;
    message: string | null;
  } | null;
}

function insufficientMonthly(type: string): MonthlyResponse {
  return {
    type,
    month: "2026-09-01",
    status: "INSUFFICIENT_DATA",
    mtbfRows: [],
    mttrRows: [],
    marRows: [],
    pmCompletionRows: [],
    technicianRows: [],
    breakdownRows: [],
    target: null,
    refresh: null,
  };
}

let mockMonthly: Record<string, MonthlyResponse> = {};
let mockMonthlyLoading = false;
let mockMonthlyError = false;
const mockMonthlyRefetch = vi.fn();

vi.mock("@/features/analytics/hooks/use-kpi-materialized", () => ({
  kpiMaterializedQueryPrefix: "/api/v1/kpi/materialized",
  kpiMaterializedQueryKey: (type: string, month: string, plantId?: string) => [
    "/api/v1/kpi/materialized",
    type,
    month,
    plantId ?? "all",
  ],
  useKpiMaterialized: (type: string) => ({
    data: mockMonthlyLoading || mockMonthlyError ? undefined : (mockMonthly[type] ?? insufficientMonthly(type)),
    isLoading: mockMonthlyLoading,
    isError: mockMonthlyError,
    isFetching: false,
    refetch: mockMonthlyRefetch,
  }),
}));

const mockTargetsMutate = vi.fn();

vi.mock("@/features/analytics/hooks/use-kpi-targets", () => ({
  kpiTargetsQueryPrefix: "/api/v1/kpi/targets",
  useKpiTargets: () => ({ data: [], isLoading: false, isError: false, refetch: vi.fn() }),
  useUpsertKpiTarget: () => ({ mutate: mockTargetsMutate, isPending: false }),
}));

let mockAuthUser: { id: string; loginIdentifier: string; applicationRole: string } | null = {
  id: "u-1",
  loginIdentifier: "manager@syncro.test",
  applicationRole: "MANAGER_MAINTENANCE",
};

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockAuthUser,
}));

// ---------------------------------------------------------------------------
// Test wrapper
// ---------------------------------------------------------------------------

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

const Wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

function renderPage() {
  return render(<AnalyticsPageContent />, { wrapper: Wrapper });
}

type MtbfStatus = "AVAILABLE" | "INSUFFICIENT_DATA";

const baseMtbf = (overrides: Partial<NonNullable<typeof mockMtbfData>> = {}): NonNullable<typeof mockMtbfData> => ({
  mtbf: { status: "AVAILABLE" as MtbfStatus, valueHours: 48.0, workorderCount: 5 },
  mttr: { status: "AVAILABLE" as MtbfStatus, valueHours: 2.5, workorderCount: 3 },
  windowFrom: "2026-07-30T00:00:00Z",
  windowTo: "2026-08-29T00:00:00Z",
  computedAt: "2026-08-29T08:00:00Z",
  cacheAgeMs: null,
  stale: false,
  ...overrides,
});

const baseKpi = (overrides: Partial<NonNullable<typeof mockKpiData>> = {}) => ({
  technicians: [
    {
      technicianId: "t1",
      technicianName: "Technician A",
      completedCount: 10,
      averageMttrHours: 3.0,
      onTimePercentage: 80.0,
      ratings: [
        { dimensionId: "d1", dimensionCode: "RESPONSIVENESS", dimensionLabel: "Responsiveness", averageScore: 4.5 },
      ],
    },
  ],
  windowFrom: "2026-07-30T00:00:00Z",
  windowTo: "2026-08-29T00:00:00Z",
  computedAt: "2026-08-29T08:00:00Z",
  cacheAgeMs: null,
  stale: false,
  ...overrides,
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("AnalyticsPageContent", () => {
  beforeEach(() => {
    mockScope = { mode: "ASSIGNED", availablePlants: [{ id: "p1", code: "P1", name: "Plant 1" }], emptyReason: null };
    mockActivePlantId = "all";
    mockLoadError = false;
    mockMtbfData = null;
    mockMtbfLoading = false;
    mockMtbfError = false;
    mockKpiData = null;
    mockKpiLoading = false;
    mockKpiError = false;
    mockMonthly = {};
    mockMonthlyLoading = false;
    mockMonthlyError = false;
    mockTargetsMutate.mockClear();
    mockAuthUser = { id: "u-1", loginIdentifier: "manager@syncro.test", applicationRole: "MANAGER_MAINTENANCE" };
    queryClient.clear();
  });

  it("renders loading state when scope is null", () => {
    mockScope = null;
    renderPage();
    expect(screen.getByText("Analytics")).toBeTruthy();
  });

  it("renders error state for MTBF/MTTR when fetch fails", () => {
    mockMtbfError = true;
    renderPage();
    expect(screen.getByText("Failed to load the MTBF/MTTR analytics.")).toBeTruthy();
    expect(screen.getByText("Retry")).toBeTruthy();
  });

  it("renders insufficient data state (no fabricated values)", () => {
    mockMtbfData = baseMtbf({
      mtbf: { status: "INSUFFICIENT_DATA", valueHours: null, workorderCount: 1 },
      mttr: { status: "INSUFFICIENT_DATA", valueHours: null, workorderCount: 0 },
    });
    renderPage();
    expect(screen.getAllByText("Insufficient data").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(2); // em-dash for null values
  });

  it("renders MTBF/MTTR values when data is available", () => {
    mockMtbfData = baseMtbf();
    renderPage();
    expect(screen.getByText("MTBF")).toBeTruthy();
    expect(screen.getByText("MTTR")).toBeTruthy();
    expect(screen.getByText("48.0 h")).toBeTruthy();
    expect(screen.getByText("2.5 h")).toBeTruthy();
    expect(screen.getByText(/Reliability window/)).toBeTruthy();
  });

  it("renders stale indicator as labeled badge (not color-only)", () => {
    mockMtbfData = baseMtbf({ stale: true });
    renderPage();
    expect(screen.getByText("Stale")).toBeTruthy();
    expect(screen.getByText(/Data may be out of date/)).toBeTruthy();
  });

  it("renders technician KPI table with objective KPIs and ratings", () => {
    mockMtbfData = baseMtbf();
    mockKpiData = baseKpi();
    renderPage();
    fireEvent.click(screen.getByText("Technician KPI"));
    expect(screen.getByText("Technician A")).toBeTruthy();
    expect(screen.getByText("10")).toBeTruthy(); // completedCount
    expect(screen.getByText("3.0 h")).toBeTruthy(); // avg MTTR hours
    expect(screen.getByText("80.0%")).toBeTruthy(); // on-time %
    expect(screen.getByText(/Responsiveness:/)).toBeTruthy();
    expect(screen.getByText("4.5 / 5")).toBeTruthy();
  });

  it("renders technician KPI empty state", () => {
    mockMtbfData = baseMtbf();
    mockKpiData = baseKpi({ technicians: [] });
    renderPage();
    fireEvent.click(screen.getByText("Technician KPI"));
    expect(screen.getByText("No technicians in scope")).toBeTruthy();
  });

  it("renders technician with no ratings as objective-only", () => {
    mockMtbfData = baseMtbf();
    mockKpiData = baseKpi({
      technicians: [
        {
          technicianId: "t2",
          technicianName: "Technician B",
          completedCount: 3,
          averageMttrHours: null,
          onTimePercentage: null,
          ratings: [],
        },
      ],
    });
    renderPage();
    fireEvent.click(screen.getByText("Technician KPI"));
    expect(screen.getByText("Technician B")).toBeTruthy();
    expect(screen.getByText("No ratings")).toBeTruthy();
  });

  it("renders technician KPI error state", () => {
    mockMtbfData = baseMtbf();
    mockKpiError = true;
    renderPage();
    fireEvent.click(screen.getByText("Technician KPI"));
    expect(screen.getByText("Failed to load the technician KPI analytics.")).toBeTruthy();
  });

  it("renders stale indicator even when the technician list is empty", () => {
    mockMtbfData = baseMtbf();
    mockKpiData = baseKpi({ technicians: [], stale: true });
    renderPage();
    fireEvent.click(screen.getByText("Technician KPI"));
    expect(screen.getByText("Stale")).toBeTruthy();
    expect(screen.getByText(/Data may be out of date/)).toBeTruthy();
    expect(screen.getByText("No technicians in scope")).toBeTruthy();
  });

  it("renders em-dash for out-of-range on-time percentage (no fabricated value)", () => {
    mockMtbfData = baseMtbf();
    mockKpiData = baseKpi({
      technicians: [
        {
          technicianId: "t3",
          technicianName: "Technician C",
          completedCount: 0,
          averageMttrHours: null,
          onTimePercentage: Number.POSITIVE_INFINITY,
          ratings: [],
        },
      ],
    });
    renderPage();
    fireEvent.click(screen.getByText("Technician KPI"));
    expect(screen.getByText("Technician C")).toBeTruthy();
    // Infinity on-time % → sentinel fallback (em-dash). Average MTTR null also renders
    // em-dash, so multiple matches are expected.
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(2);
  });

  it("renders plant scope error state", () => {
    mockLoadError = true;
    renderPage();
    expect(screen.getByText("Plant scope unavailable. Try again or contact your administrator.")).toBeTruthy();
  });

  it("renders empty scope state", () => {
    mockScope = { mode: "EMPTY", availablePlants: [], emptyReason: "NO_PLANTS_ASSIGNED" };
    renderPage();
    expect(screen.getByText("No plants assigned to your account. Contact your administrator.")).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // Story 20-2: Monthly KPI tab (materialized actual-vs-target consumption)
  // -------------------------------------------------------------------------

  function openMonthlyTab() {
    fireEvent.click(screen.getByText("Monthly KPI"));
  }

  it("renders monthly actual vs target rows with text verdict badges (non-color-only)", () => {
    mockMonthly = {
      mtbf: {
        ...insufficientMonthly("mtbf"),
        status: "AVAILABLE",
        mtbfRows: [
          {
            plantId: "p1",
            machineId: "m-aaaa-1111",
            mtbfDays: 10.42,
            targetValue: 8.0,
            targetStatus: "ON_TARGET",
          },
          {
            plantId: "p1",
            machineId: "m-bbbb-2222",
            mtbfDays: 5.0,
            targetValue: 8.0,
            targetStatus: "BELOW_TARGET",
          },
        ],
      },
      mttr: {
        ...insufficientMonthly("mttr"),
        status: "AVAILABLE",
        mttrRows: [
          {
            plantId: "p1",
            wallClockMinutes: 75.0,
            actualWorkingMinutes: 75.0,
            targetValue: 60.0,
            targetStatus: "ABOVE_TARGET",
          },
        ],
      },
      breakdown: {
        ...insufficientMonthly("breakdown"),
        status: "AVAILABLE",
        breakdownRows: [{ plantId: "p1", count: 2, targetValue: 3.0, targetStatus: "ON_TARGET" }],
      },
    };
    renderPage();
    openMonthlyTab();

    // Verdicts render as text labels, never color-only.
    expect(screen.getAllByText("On target").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("Below target")).toBeTruthy();
    expect(screen.getByText("Above target")).toBeTruthy();
    // Actual vs target values render from the backend payload (no client-side math).
    expect(screen.getByText("10.42 d")).toBeTruthy();
    expect(screen.getAllByText("8.00 d").length).toBe(2); // both MTBF rows share the 8.00 target
    expect(screen.getByText("75 min")).toBeTruthy();
    expect(screen.getByText("60 min")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy(); // breakdown count
    expect(screen.getByText("3")).toBeTruthy(); // breakdown target
  });

  it("renders NO_TARGET rows with an explicit text badge", () => {
    mockMonthly = {
      technician: {
        ...insufficientMonthly("technician"),
        status: "AVAILABLE",
        technicianRows: [
          {
            plantId: "p1",
            technicianId: "t-cccc-3333",
            averageRating: 4.5,
            totalWo: 7,
            firstTimeFixRate: 85.0,
            targetValue: null,
            targetStatus: "NO_TARGET",
          },
        ],
      },
    };
    renderPage();
    openMonthlyTab();

    expect(screen.getByText("No target")).toBeTruthy();
    // Actuals still render (combined cell: rating · WO · first-time-fix).
    expect(screen.getByText(/4\.5 \/ 5 · 7 WO · 85\.0%/)).toBeTruthy();
  });

  it("renders an explicit insufficient-data badge for months without rows (never zeros)", () => {
    mockMonthly = {}; // every type → INSUFFICIENT_DATA
    renderPage();
    openMonthlyTab();

    expect(screen.getAllByText("Insufficient data").length).toBeGreaterThanOrEqual(6);
    expect(screen.queryByText("0.00 d")).toBeNull();
    expect(screen.queryByText("0 min")).toBeNull();
  });

  it("surfaces FAILED refresh evidence as a warning", () => {
    mockMonthly = {
      mtbf: {
        ...insufficientMonthly("mtbf"),
        status: "AVAILABLE",
        mtbfRows: [
          {
            plantId: "p1",
            machineId: "m-aaaa-1111",
            mtbfDays: 12.0,
            targetValue: null,
            targetStatus: "NO_TARGET",
          },
        ],
        refresh: {
          refreshKey: "mtbf:2026-09",
          status: "FAILED",
          refreshedAt: "2026-09-01T00:00:00Z",
          message: "IllegalStateException",
        },
      },
    };
    renderPage();
    openMonthlyTab();

    expect(screen.getByText("Refresh failed")).toBeTruthy();
    expect(screen.getByText(/may be stale/)).toBeTruthy();
  });

  it("shows the Configure targets button for MANAGER_MAINTENANCE and opens the dialog", () => {
    mockAuthUser = { id: "u-1", loginIdentifier: "manager@syncro.test", applicationRole: "MANAGER_MAINTENANCE" };
    renderPage();
    openMonthlyTab();

    fireEvent.click(screen.getByText("Configure targets"));
    expect(screen.getByText("Configure KPI targets")).toBeTruthy();
    expect(screen.getByText("Monthly breakdown target")).toBeTruthy();
    expect(screen.getByText("MTBF target (days)")).toBeTruthy();
    expect(screen.getByText("MTTR target (minutes)")).toBeTruthy();
    expect(screen.getByText("OEE quality (%)")).toBeTruthy();
    expect(screen.getByText("OEE performance (%)")).toBeTruthy();
  });

  it("hides the Configure targets affordance for TECHNICIAN (server-side gate is authoritative)", () => {
    mockAuthUser = { id: "u-2", loginIdentifier: "tech@syncro.test", applicationRole: "TECHNICIAN" };
    renderPage();
    openMonthlyTab();

    expect(screen.queryByText("Configure targets")).toBeNull();
  });

  it("shows the Configure targets button for SUPER_ADMIN", () => {
    mockAuthUser = { id: "u-3", loginIdentifier: "admin@syncro.test", applicationRole: "SUPER_ADMIN" };
    renderPage();
    openMonthlyTab();

    expect(screen.getByText("Configure targets")).toBeTruthy();
  });

  it("renders monthly loading and error states", () => {
    mockMonthlyLoading = true;
    const { unmount } = renderPage();
    openMonthlyTab();
    // Loading: skeletons only — no verdict rows, no insufficient-data badges.
    expect(screen.queryByText("Insufficient data")).toBeNull();
    expect(screen.queryByText("On target")).toBeNull();
    unmount();

    mockMonthlyLoading = false;
    mockMonthlyError = true;
    renderPage();
    openMonthlyTab();
    expect(screen.getAllByText(/Failed to load/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("Retry").length).toBeGreaterThanOrEqual(1);
  });

  it("renders MTTR wall-clock actual when actual-working is null (fallback precedence)", () => {
    // Review 20-2: the other MTTR fixture has both variants equal, so the
    // `actualWorking ?? wallClock` display fallback is undistinguishable. A null
    // actual-working must render the wall-clock value, not an em-dash.
    mockMonthly = {
      mttr: {
        ...insufficientMonthly("mttr"),
        status: "AVAILABLE",
        mttrRows: [
          {
            plantId: "p1",
            wallClockMinutes: 75.0,
            actualWorkingMinutes: null,
            targetValue: 60.0,
            targetStatus: "ABOVE_TARGET",
          },
        ],
      },
    };
    renderPage();
    openMonthlyTab();

    expect(screen.getByText("75 min")).toBeTruthy();
    expect(screen.getByText("Above target")).toBeTruthy();
  });

  it("submits the target dialog with null for empty fields (20-1 partial-update semantics)", async () => {
    // Review 20-2: the write path was never exercised — an empty field must send null
    // (keep stored value), not 0 (which would silently overwrite a configured target).
    renderPage();
    openMonthlyTab();
    fireEvent.click(screen.getByText("Configure targets"));

    fireEvent.change(screen.getByLabelText("MTBF target (days)"), { target: { value: "30.5" } });
    fireEvent.click(screen.getByText("Save target"));

    // zodResolver validation is async — wait for the submit callback to land.
    await waitFor(() => expect(mockTargetsMutate).toHaveBeenCalledTimes(1));
    const payload = mockTargetsMutate.mock.calls[0][0] as Record<string, unknown>;
    expect(payload.plantId).toBe("p1");
    expect(payload.mtbfTargetDays).toBe(30.5);
    expect(payload.monthlyBreakdownTarget).toBeNull();
    expect(payload.mttrTargetMinutes).toBeNull();
    expect(payload.oeeQualityPercent).toBeNull();
    expect(payload.oeePerformancePercent).toBeNull();
  });

  it("rejects a fractional monthly breakdown target client-side", async () => {
    // Review 20-2: backend column is Integer; the dialog must not send 2.5.
    renderPage();
    openMonthlyTab();
    fireEvent.click(screen.getByText("Configure targets"));

    fireEvent.change(screen.getByLabelText("Monthly breakdown target"), { target: { value: "2.5" } });
    fireEvent.click(screen.getByText("Save target"));

    await waitFor(() => expect(screen.getByText("Must be a whole number.")).toBeTruthy());
    expect(mockTargetsMutate).not.toHaveBeenCalled();
  });
});
