import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";
import type { ListMachinesParams, MachineListResponse } from "@/lib/api/generated/model";
import { I18nProvider } from "@/test/i18n-wrapper";

import type { TelemetryMachineView } from "../types";
import { TelemetryDashboardPage } from "./telemetry-dashboard-page";

type TelemetryQueryState = {
  data: { data: MachineListResponse };
  isLoading: boolean;
  isError: boolean;
  isFetching: boolean;
  refetch: ReturnType<typeof vi.fn>;
  dataUpdatedAt?: number;
};

let telemetryQuery: TelemetryQueryState;
let latestParams: ListMachinesParams | undefined;
let mockScope: {
  activePlantId: string;
  scope: { mode: "UNRESTRICTED" | "EMPTY" | "ASSIGNED"; availablePlants: unknown[] };
  loadError: boolean;
};

vi.mock("@/lib/api/generated/syncro", () => ({
  useListMachines: (params: ListMachinesParams) => {
    latestParams = params;
    return telemetryQuery;
  },
  useListPlants: () => ({ data: { data: { items: [] } }, isLoading: false }),
}));

vi.mock("@/features/plant-scope/plant-scope-store", () => ({
  usePlantScope: () => mockScope,
}));

function machine(overrides: Partial<TelemetryMachineView> = {}): TelemetryMachineView {
  return {
    id: "m-1",
    code: "BF-08410",
    name: "JBF19",
    machineGroupName: "Forming",
    status: "ACTIVE",
    optionalTelemetryFields: [],
    latestTelemetry: {
      machineId: "m-1",
      running: true,
      runtimeHours: 123.4,
      counting: 4200,
      lastReceivedAt: "2026-08-10T05:00:00Z",
      freshnessState: "ONLINE",
      optionalFields: {},
      hasOptionalFields: false,
    },
    ...overrides,
  };
}

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <I18nProvider>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{children}</TooltipProvider>
    </QueryClientProvider>
  </I18nProvider>
);

describe("Telemetry Dashboard Page", () => {
  beforeEach(() => {
    mockScope = {
      activePlantId: "all",
      scope: { mode: "UNRESTRICTED", availablePlants: [] },
      loadError: false,
    };
    latestParams = undefined;
    telemetryQuery = {
      data: { data: { items: [], totalElements: 0 } },
      isLoading: false,
      isError: false,
      isFetching: false,
      refetch: vi.fn(),
      dataUpdatedAt: Date.now(),
    };
  });

  it("[P1] 3-7-WEB-001 default query filters ACTIVE machines sorted by code", () => {
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });
    expect(latestParams).toMatchObject({ status: "ACTIVE", sort: "code,asc" });
    expect(latestParams?.plantId).toBeUndefined();
  });

  it("[P1] 3-7-WEB-002 renders telemetry values for each active machine", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: { data: { items: [machine()], totalElements: 1 } },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText("JBF19")).toBeInTheDocument();
    expect(screen.getByText(/BF-08410/)).toBeInTheDocument();
    expect(screen.getByText("Running")).toBeInTheDocument();
    expect(screen.getByText("123.4 h")).toBeInTheDocument();
    expect(screen.getByText("4,200")).toBeInTheDocument();
    expect(screen.getByText("Online")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
  });

  it("[P1] 3-7-WEB-003 machine without telemetry shows no-data label instead of error", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: { data: { items: [machine({ latestTelemetry: undefined })], totalElements: 1 } },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText("No data received")).toBeInTheDocument();
    expect(screen.getByText("No data received yet for this machine.")).toBeInTheDocument();
  });

  it("[P1] 3-7-WEB-004 optional telemetry fields render dynamically", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: {
        data: {
          items: [
            machine({
              latestTelemetry: {
                ...machine().latestTelemetry,
                optionalFields: { vibration: "0.42" },
                hasOptionalFields: true,
              },
            }),
          ],
          totalElements: 1,
        },
      },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText("vibration")).toBeInTheDocument();
    expect(screen.getByText("0.42")).toBeInTheDocument();
  });

  it("[P1] 3-7-WEB-005 empty plant shows helpful empty state", () => {
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });
    expect(screen.getByText("No active machines found")).toBeInTheDocument();
    expect(screen.getByText("Activate machines to see their latest telemetry here.")).toBeInTheDocument();
  });

  it("[P1] 3-7-WEB-006 error state shows retry button that refetches", () => {
    telemetryQuery = { ...telemetryQuery, isError: true };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(telemetryQuery.refetch).toHaveBeenCalled();
  });

  it("[P2] 3-7-WEB-007 manual refresh button triggers refetch", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: { data: { items: [machine()], totalElements: 1 } },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(telemetryQuery.refetch).toHaveBeenCalled();
  });

  it("[P1] 3-7-WEB-008 EMPTY plant scope disables query and shows assignment message", () => {
    mockScope = { activePlantId: "all", scope: { mode: "EMPTY", availablePlants: [] }, loadError: false };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(latestParams?.plantId).toBeUndefined();
    expect(screen.getByText("No plants assigned")).toBeInTheDocument();
    expect(screen.getByLabelText("Plant")).toBeDisabled();
  });

  it("[P2] 3-7-WEB-009 stale freshness badge exposes non-color-only label", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: {
        data: {
          items: [
            machine({
              id: "m-stale",
              latestTelemetry: {
                ...machine().latestTelemetry,
                freshnessState: "STALE",
                running: false,
              },
            }),
          ],
          totalElements: 1,
        },
      },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    const badge = screen.getByText("Stale");
    expect(badge).toBeInTheDocument();
    expect(badge.getAttribute("aria-label")).toContain("Telemetry freshness: Stale");
    expect(screen.getByText("Stopped")).toBeInTheDocument();
  });

  it("[P2] 3-7-WEB-010 loading state renders skeletons", () => {
    telemetryQuery = { ...telemetryQuery, isLoading: true };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });
    expect(screen.getByText(/Loading telemetry/)).toBeInTheDocument();
  });

  it("[P2] stale data banner shows last-updated time and refresh action", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: { data: { items: [machine()], totalElements: 1 } },
      dataUpdatedAt: Date.now() - 3 * 60_000,
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText(/Last updated 3 min ago/)).toBeInTheDocument();
    const refreshNow = screen.getByRole("button", { name: "Refresh now" });
    fireEvent.click(refreshNow);
    expect(telemetryQuery.refetch).toHaveBeenCalled();
  });

  it("[P1] DW-34 truncation notice renders when totalElements exceeds returned items", () => {
    const visible = [
      machine({ id: "m-1" }),
      machine({ id: "m-2", code: "BF-00002" }),
      machine({ id: "m-3", code: "BF-00003" }),
    ];
    telemetryQuery = {
      ...telemetryQuery,
      data: { data: { items: visible, totalElements: 250 } },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText(/3 active machines/)).toBeInTheDocument();
    expect(screen.getByText(/showing first 3 of 250 matching/)).toBeInTheDocument();
  });

  it("[P1] DW-34 no truncation notice for a fleet that fits the page entirely", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: {
        data: {
          items: [machine({ id: "m-1" }), machine({ id: "m-2", code: "BF-00002" })],
          totalElements: 2,
        },
      },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText(/2 active machines/)).toBeInTheDocument();
    expect(screen.queryByText(/showing first/)).not.toBeInTheDocument();
  });

  it("[P2] DW-34 single machine renders singular label without notice", () => {
    telemetryQuery = {
      ...telemetryQuery,
      data: { data: { items: [machine()], totalElements: 1 } },
    };
    render(<TelemetryDashboardPage />, { wrapper: Wrapper });

    expect(screen.getByText("1 active machine")).toBeInTheDocument();
    expect(screen.queryByText(/showing first/)).not.toBeInTheDocument();
  });
});
