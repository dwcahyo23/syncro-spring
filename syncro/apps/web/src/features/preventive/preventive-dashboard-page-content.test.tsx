import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PreventiveDashboardPageContent } from "./preventive-dashboard-page-content";

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

let mockQueryData: { dueCount: number; overdueCount: number; upcoming: Array<{
  scheduleId: string; machineId: string; programId: string; dueDate: string;
  status: string; derivedStatus: string; category: string; scheduleType: string;
  machineCode: string; machineName: string | null; programTitle: string;
}> } | null = null;
let mockIsLoading = false;
let mockIsError = false;
let mockIsFetching = false;
const mockRefetch = vi.fn();

vi.mock("@/features/preventive/hooks/use-preventive-dashboard", () => ({
  usePreventiveDashboard: () => ({
    data: mockQueryData,
    isLoading: mockIsLoading,
    isError: mockIsError,
    isFetching: mockIsFetching,
    refetch: mockRefetch,
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
  return render(<PreventiveDashboardPageContent />, { wrapper: Wrapper });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("PreventiveDashboardPageContent", () => {
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
    expect(screen.getByText("Preventive Dashboard")).toBeTruthy();
  });

  it("renders error state when data fetch fails", () => {
    mockIsError = true;
    renderPage();
    expect(screen.getByText("Failed to load the preventive dashboard.")).toBeTruthy();
    expect(screen.getByText("Retry")).toBeTruthy();
  });

  it("renders empty state when no schedules", () => {
    mockQueryData = { dueCount: 0, overdueCount: 0, upcoming: [] };
    renderPage();
    expect(screen.getByText("No scheduled preventive tasks")).toBeTruthy();
  });

  it("renders due/overdue cards and upcoming with machine details when data is available", () => {
    mockQueryData = {
      dueCount: 1,
      overdueCount: 1,
      upcoming: [
        {
          scheduleId: "s1",
          machineId: "m1",
          programId: "pr1",
          dueDate: "2026-08-24",
          status: "SCHEDULED",
          derivedStatus: "OVERDUE",
          category: "MECHANICAL",
          scheduleType: "MONTHLY",
          machineCode: "MC-001",
          machineName: "Machine 1",
          programTitle: "Monthly Check",
        },
      ],
    };
    renderPage();
    // "1" appears in the Due and Overdue KPI cards.
    expect(screen.getAllByText("1").length).toBeGreaterThanOrEqual(1);
    // "Overdue" appears in the KPI card label and the row badge.
    expect(screen.getAllByText("Overdue").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Machine 1")).toBeTruthy();
    // "MC-001" is split across the machine-name and mono-code spans.
    expect(screen.getByText(/MC-001/)).toBeTruthy();
    expect(screen.getByText(/Monthly Check/)).toBeTruthy();
  });

  it("renders overdue schedule as labeled badge (not color-only)", () => {
    mockQueryData = {
      dueCount: 0,
      overdueCount: 1,
      upcoming: [
        {
          scheduleId: "s2",
          machineId: "m1",
          programId: "pr1",
          dueDate: "2026-08-20",
          status: "SCHEDULED",
          derivedStatus: "OVERDUE",
          category: "ELECTRICAL",
          scheduleType: "ANNUAL",
          machineCode: "MC-002",
          machineName: null,
          programTitle: "Annual Check",
        },
      ],
    };
    renderPage();
    const overdueBadges = screen.getAllByText("Overdue");
    expect(overdueBadges.length).toBeGreaterThanOrEqual(1);
    // At least one "Overdue" occurrence is the row badge with the non-color-only label.
    const badge = overdueBadges.find((el) => el.getAttribute("aria-label")?.includes("Overdue schedule"));
    expect(badge).toBeTruthy();
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