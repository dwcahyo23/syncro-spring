import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { syncroFetch } from "@/lib/api/orval-mutator";

import { useMtbfMttr } from "./use-mtbf-mttr";
import { useTechnicianKpi } from "./use-technician-kpi";

vi.mock("@/lib/api/orval-mutator", () => ({
  syncroFetch: vi.fn(),
}));

const mockedSyncroFetch = vi.mocked(syncroFetch);

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

const Wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

function mtbfPayload() {
  return {
    data: {
      mtbf: { status: "AVAILABLE", valueHours: 48.0, workorderCount: 3 },
      mttr: { status: "AVAILABLE", valueHours: 2.5, workorderCount: 2 },
      windowFrom: "2026-07-30T00:00:00Z",
      windowTo: "2026-08-29T00:00:00Z",
      computedAt: "2026-08-29T08:00:00Z",
      cacheAgeMs: null,
      stale: false,
    },
  };
}

function kpiPayload() {
  return {
    data: {
      technicians: [],
      windowFrom: "2026-07-30T00:00:00Z",
      windowTo: "2026-08-29T00:00:00Z",
      computedAt: "2026-08-29T08:00:00Z",
      cacheAgeMs: null,
      stale: false,
    },
  };
}

describe("analytics dashboard hooks", () => {
  beforeEach(() => {
    queryClient.clear();
    vi.clearAllMocks();
  });

  it("useMtbfMttr builds the plantId query string and passes it to syncroFetch", async () => {
    mockedSyncroFetch.mockResolvedValue(mtbfPayload() as never);

    const { result } = renderHook(() => useMtbfMttr("plant-123", true), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.mtbf.valueHours).toBe(48.0);
    expect(mockedSyncroFetch).toHaveBeenCalledWith("/api/v1/dashboard/mtbf-mttr?plantId=plant-123", {
      method: "GET",
    });
  });

  it("useMtbfMttr omits the query string when plantId is absent", async () => {
    mockedSyncroFetch.mockResolvedValue(mtbfPayload() as never);

    const { result } = renderHook(() => useMtbfMttr(undefined, true), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockedSyncroFetch).toHaveBeenCalledWith("/api/v1/dashboard/mtbf-mttr", { method: "GET" });
  });

  it("useTechnicianKpi builds the plantId query string and passes it to syncroFetch", async () => {
    mockedSyncroFetch.mockResolvedValue(kpiPayload() as never);

    const { result } = renderHook(() => useTechnicianKpi("plant-456", true), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockedSyncroFetch).toHaveBeenCalledWith("/api/v1/dashboard/technician-kpi?plantId=plant-456", {
      method: "GET",
    });
  });

  it("useTechnicianKpi omits the query string when plantId is absent", async () => {
    mockedSyncroFetch.mockResolvedValue(kpiPayload() as never);

    const { result } = renderHook(() => useTechnicianKpi(undefined, true), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockedSyncroFetch).toHaveBeenCalledWith("/api/v1/dashboard/technician-kpi", { method: "GET" });
  });
});
