import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";
import { I18nProvider } from "@/test/i18n-wrapper";

import { MachineDashboardPageContent } from "./machine-dashboard-page-content";

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

let mockQueryData: {
  items: Array<{
    machineId: string;
    code: string;
    name: string | null;
    status: string;
    machineGroupName: string | null;
    plantCode: string;
    plantName: string;
    openWorkOrderCount: number;
    openAlertCount: number;
    telemetryFreshness: {
      freshnessState: string;
      running: boolean;
      runtimeHours: number | null;
      counting: number | null;
      lastReceivedAt: string | null;
    } | null;
    lifetimeRisk: { maxConsumedPercentage: string | null; thresholdPercentage: string | null; status: string };
  }>;
} | null = {
  items: [],
};
let mockIsLoading = false;
let mockIsError = false;
let mockIsFetching = false;
const mockRefetch = vi.fn();

vi.mock("@/features/machines/hooks/use-machine-dashboard", () => ({
  useMachineDashboard: () => ({
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
  <I18nProvider>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{children}</TooltipProvider>
    </QueryClientProvider>
  </I18nProvider>
);

function renderPage() {
  return render(<MachineDashboardPageContent />, { wrapper: Wrapper });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("MachineDashboardPageContent", () => {
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
    expect(screen.getByText("Machine Dashboard")).toBeTruthy();
    expect(document.querySelectorAll("[data-slot='skeleton']").length).toBeGreaterThanOrEqual(1);
  });

  it("renders error state when data fetch fails", () => {
    mockIsError = true;
    renderPage();
    expect(screen.getByText("Failed to load the machine dashboard.")).toBeTruthy();
    expect(screen.getByText("Retry")).toBeTruthy();
  });

  it("renders empty state when no machines in scope", () => {
    mockQueryData = { items: [] };
    renderPage();
    expect(screen.getByText("No machines in scope")).toBeTruthy();
  });

  it("renders machine cards when data is available", () => {
    mockQueryData = {
      items: [
        {
          machineId: "m1",
          code: "MC-001",
          name: "Machine 1",
          status: "ACTIVE",
          machineGroupName: "Forming",
          plantCode: "P1",
          plantName: "Plant 1",
          openWorkOrderCount: 2,
          openAlertCount: 1,
          telemetryFreshness: {
            freshnessState: "ONLINE",
            running: true,
            runtimeHours: 123.4,
            counting: 1000,
            lastReceivedAt: "2026-08-10T10:00:00Z",
          },
          lifetimeRisk: { maxConsumedPercentage: "82.00", thresholdPercentage: "80", status: "AT_RISK" },
        },
      ],
    };
    renderPage();
    expect(screen.getByText("Machine 1")).toBeTruthy();
    expect(screen.getByText("MC-001 · Forming")).toBeTruthy();
    // Open workorder count and open alert count are numeric — use getAllByText for ambiguous numbers.
    expect(screen.getAllByText("2").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("At risk")).toBeTruthy();
  });

  it("renders unknown telemetry when telemetry is null", () => {
    mockQueryData = {
      items: [
        {
          machineId: "m2",
          code: "MC-002",
          name: "Machine 2",
          status: "ACTIVE",
          machineGroupName: null,
          plantCode: "P1",
          plantName: "Plant 1",
          openWorkOrderCount: 0,
          openAlertCount: 0,
          telemetryFreshness: null,
          lifetimeRisk: { maxConsumedPercentage: null, thresholdPercentage: null, status: "NO_DATA" },
        },
      ],
    };
    renderPage();
    expect(screen.getByText("Unknown")).toBeTruthy();
    expect(screen.getByText("No data")).toBeTruthy();
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
