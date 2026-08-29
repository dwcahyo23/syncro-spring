import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
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
});
