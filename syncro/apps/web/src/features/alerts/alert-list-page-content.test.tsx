import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";

import { AlertListPageContent } from "./alert-list-page-content";

let listData:
  | { data: { items: Array<Record<string, unknown>>; totalElements: number; page: number; size: number; sort: string } }
  | undefined;
let isLoading = false;
let isError = false;
let error: unknown;

vi.mock("@/lib/api/generated/syncro", () => ({
  useListAlerts: () => ({
    data: listData,
    isLoading,
    isError,
    error,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/i18n/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>
    <TooltipProvider>{children}</TooltipProvider>
  </QueryClientProvider>
);

describe("AlertListPageContent", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    isLoading = false;
    isError = false;
    error = undefined;
    listData = {
      data: {
        items: [],
        totalElements: 0,
        page: 0,
        size: 50,
        sort: "createdAt,desc",
      },
    };
  });

  it("renders the Type column with AlertTypeBadge", () => {
    listData = {
      data: {
        items: [
          {
            id: "a-1",
            alertType: "PROCUREMENT_RISK",
            status: "OPEN",
            machineCode: "BF-08410",
            plantCode: "GM1",
            sparepartCode: "SP-001",
            functionName: "Primary",
            thresholdPercentage: null,
            consumedPercentageSnapshot: null,
            createdAt: "2026-08-01T00:00:00Z",
          },
        ],
        totalElements: 1,
        page: 0,
        size: 50,
        sort: "createdAt,desc",
      },
    };

    render(<AlertListPageContent />, { wrapper: Wrapper });

    expect(screen.getByText("Procurement risk")).toBeInTheDocument();
    expect(screen.getByText("Type")).toBeInTheDocument();
  });

  it("renders a dash for null thresholdPercentage", () => {
    listData = {
      data: {
        items: [
          {
            id: "a-2",
            alertType: "PROCUREMENT_RISK",
            status: "OPEN",
            machineCode: "BF-08410",
            plantCode: "GM1",
            sparepartCode: "SP-001",
            functionName: "Primary",
            thresholdPercentage: null,
            consumedPercentageSnapshot: null,
            createdAt: "2026-08-01T00:00:00Z",
          },
        ],
        totalElements: 1,
        page: 0,
        size: 50,
        sort: "createdAt,desc",
      },
    };

    render(<AlertListPageContent />, { wrapper: Wrapper });

    const thresholdCells = screen.getAllByText("-");
    expect(thresholdCells.length).toBeGreaterThanOrEqual(1);
  });

  it("renders threshold percentage for THRESHOLD_PERCENTAGE alerts", () => {
    listData = {
      data: {
        items: [
          {
            id: "a-3",
            alertType: "THRESHOLD_PERCENTAGE",
            status: "OPEN",
            machineCode: "BF-08410",
            plantCode: "GM1",
            sparepartCode: "SP-001",
            functionName: "Primary",
            thresholdPercentage: 90,
            consumedPercentageSnapshot: 90.0,
            createdAt: "2026-08-01T00:00:00Z",
          },
        ],
        totalElements: 1,
        page: 0,
        size: 50,
        sort: "createdAt,desc",
      },
    };

    render(<AlertListPageContent />, { wrapper: Wrapper });

    expect(screen.getByText("90%")).toBeInTheDocument();
    expect(screen.getByText("90.0%")).toBeInTheDocument();
  });
});
