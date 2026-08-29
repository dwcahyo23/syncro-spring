import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { WorkorderDashboardPageContent } from "./workorder-dashboard-page-content";

// ---------------------------------------------------------------------------
// Module-level mocks
// ---------------------------------------------------------------------------

let mockScope: { mode: string; availablePlants: Array<{ id: string; code: string; name: string }>; emptyReason: string | null } | null = {
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

let mockQueryData: { total: number; byStatus: Array<{ status: string; count: number }>; byCategory: Array<{ categoryCode: string | null; categoryLabel: string | null; count: number }> } | null = null;
let mockIsLoading = false;
let mockIsError = false;
let mockIsFetching = false;
const mockRefetch = vi.fn();

vi.mock("@/features/workorders/hooks/use-workorder-dashboard", () => ({
  useWorkorderDashboard: () => ({
    data: mockQueryData,
    isLoading: mockIsLoading,
    isError: mockIsError,
    isFetching: mockIsFetching,
    refetch: mockRefetch,
  }),
}));

vi.mock("@/lib/api/generated/syncro", () => ({
  useListSections: () => ({ data: { data: { items: [] } } }),
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
  return render(<WorkorderDashboardPageContent />, { wrapper: Wrapper });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("WorkorderDashboardPageContent", () => {
  beforeEach(() => {
    mockScope = { mode: "ASSIGNED", availablePlants: [{ id: "p1", code: "P1", name: "Plant 1" }], emptyReason: null };
    mockActivePlantId = "all";
    mockLoadError = false;
    mockQueryData = null;
    mockIsLoading = false;
    mockIsError = false;
    mockIsFetching = false;
    queryClient.clear();
  });

  it("renders loading state when scope is null", () => {
    mockScope = null;
    renderPage();
    expect(screen.getByText("Workorder Dashboard")).toBeTruthy();
  });

  it("renders error state when data fetch fails", () => {
    mockIsError = true;
    renderPage();
    expect(screen.getByText("Failed to load the workorder dashboard.")).toBeTruthy();
    expect(screen.getByText("Retry")).toBeTruthy();
  });

  it("renders empty state when no workorders", () => {
    mockQueryData = { total: 0, byStatus: [], byCategory: [] };
    renderPage();
    expect(screen.getByText("No workorders in scope")).toBeTruthy();
  });

  it("renders KPI cards and breakdowns when data is available", () => {
    mockQueryData = {
      total: 5,
      byStatus: [
        { status: "OPEN", count: 3 },
        { status: "DONE", count: 2 },
      ],
      byCategory: [
        { categoryCode: "BRK", categoryLabel: "Breakdown", count: 3 },
        { categoryCode: "PM", categoryLabel: "Preventive", count: 2 },
      ],
    };
    renderPage();
    expect(screen.getByText("5")).toBeTruthy();
    expect(screen.getByText("Status · OPEN")).toBeTruthy();
    // "3" appears in the KPI card and the by-status list.
    expect(screen.getAllByText("3").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Breakdown")).toBeTruthy();
    expect(screen.getByText("Preventive")).toBeTruthy();
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