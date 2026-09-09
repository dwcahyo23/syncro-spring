import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";
import type { AlertView } from "@/lib/api/generated/model";
import { I18nProvider } from "@/test/i18n-wrapper";

import { AlertDetailPageContent } from "./alert-detail-page-content";

const invalidateQueries = vi.fn();
const detailRefetch = vi.fn();

type MutationOptions = { onSuccess?: () => void };
const acknowledgeOptions = vi.hoisted(() => ({ current: undefined as MutationOptions | undefined }));

let alertData: { data: AlertView };

vi.mock("@tanstack/react-query", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-query")>();
  return {
    ...actual,
    useQueryClient: () => ({ invalidateQueries }),
  };
});

vi.mock("@/lib/api/generated/syncro", () => ({
  getListAlertsQueryKey: () => ["alerts", "list"],
  useGetAlert: () => ({
    data: alertData,
    isLoading: false,
    isError: false,
    error: undefined,
    refetch: detailRefetch,
  }),
  useAcknowledgeAlert: (options: { mutation: MutationOptions }) => {
    acknowledgeOptions.current = options.mutation;
    return { mutate: vi.fn(), isPending: false };
  },
  useResolveAlert: () => ({ mutate: vi.fn(), isPending: false }),
  useResolveAlertOverride: () => ({ mutate: vi.fn(), isPending: false }),
  useGetAlertNotifications: () => ({
    data: undefined,
    isLoading: false,
    isError: false,
    error: undefined,
    refetch: vi.fn(),
  }),
  useListAuditLogEntries: () => ({
    data: undefined,
    isLoading: false,
    isError: false,
    error: undefined,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => ({ applicationRole: "SUPER_ADMIN" }),
}));

function alert(overrides: Partial<AlertView> = {}): AlertView {
  return {
    id: "a-1",
    status: "OPEN",
    machineCode: "BF-08410",
    plantCode: "GM1",
    thresholdPercentage: 90,
    consumedPercentageSnapshot: 90.0,
    traceId: "t-1",
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
    ...overrides,
  } as AlertView;
}

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>
    <I18nProvider>
      <TooltipProvider>{children}</TooltipProvider>
    </I18nProvider>
  </QueryClientProvider>
);

describe("AlertDetailPageContent mutations", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    acknowledgeOptions.current = undefined;
    alertData = { data: alert() };
  });

  it("[P1] DW-39 acknowledge onSuccess invalidates the alerts list cache and refetches detail", () => {
    render(<AlertDetailPageContent alertId="a-1" />, { wrapper: Wrapper });

    const options = acknowledgeOptions.current;
    expect(options?.onSuccess).toBeDefined();
    options?.onSuccess?.();

    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ["alerts", "list"] });
    expect(detailRefetch).toHaveBeenCalled();
  });
});

describe("AlertDetailPageContent procurement risk", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    acknowledgeOptions.current = undefined;
  });

  it("renders procurement risk evidence (rate, basis, lead time, projected depletion) and does not show threshold sentence", () => {
    alertData = {
      data: alert({
        alertType: "PROCUREMENT_RISK",
        thresholdPercentage: null,
        currentCounterSnapshot: null,
        consumedProductionCountSnapshot: null,
        consumedPercentageSnapshot: null,
        leadTimeHours: 36.5,
        ratePerOperatingHour: 1200,
        calculationBasis: "ROLLING_30_DAY",
        projectedDepletionAt: "2026-09-05T10:00:00Z",
      }),
    };

    render(<AlertDetailPageContent alertId="a-1" />, { wrapper: Wrapper });

    expect(screen.getByText("Projected depletion falls within the sparepart lead-time window.")).toBeInTheDocument();
    expect(screen.getByText("1200.00 counters/op-hour")).toBeInTheDocument();
    expect(screen.getByText("Rolling 30 days")).toBeInTheDocument();
    expect(screen.getByText("36.50 h")).toBeInTheDocument();
    expect(screen.getByText("Sep 5, 2026, 10:00 AM UTC")).toBeInTheDocument();
    expect(screen.queryByText(/consumed percentage/i)).toBeNull();
    expect(screen.queryByText("Lifetime Evidence")).toBeNull();
  });

  it("renders threshold alert with null thresholdPercentage guard", () => {
    alertData = {
      data: alert({
        alertType: "THRESHOLD_PERCENTAGE",
        thresholdPercentage: 90,
        consumedPercentageSnapshot: 90.0,
      }),
    };

    render(<AlertDetailPageContent alertId="a-1" />, { wrapper: Wrapper });

    expect(screen.getAllByText("90.00%").length).toBeGreaterThan(0);
    expect(screen.getAllByText("90%").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/reached the configured threshold/i).length).toBeGreaterThan(0);
  });
});
