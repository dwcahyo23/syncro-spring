import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SyncroApiError, syncroFetch } from "@/lib/api/orval-mutator";
import { I18nProvider } from "@/test/i18n-wrapper";

import {
  reportPrintTemplateQueryKey,
  useReportPrintTemplate,
  useUpsertReportPrintTemplate,
} from "./use-report-print-template";

vi.mock("@/lib/api/orval-mutator", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/orval-mutator")>();
  return {
    ...actual,
    syncroFetch: vi.fn(),
  };
});

const mockedSyncroFetch = vi.mocked(syncroFetch);

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

const Wrapper = ({ children }: { children: ReactNode }) => (
  <I18nProvider>
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  </I18nProvider>
);

describe("useReportPrintTemplate", () => {
  beforeEach(() => {
    queryClient.clear();
    vi.clearAllMocks();
  });

  it("resolves the stored template (matrix: Cetak WO, template ada)", async () => {
    mockedSyncroFetch.mockResolvedValue({
      data: {
        id: "t1",
        reportType: "WORK_ORDER",
        bodyHtml: "<p>{{nomor}}</p>",
        updatedBy: null,
        updatedAt: "2026-09-15T00:00:00Z",
      },
    } as never);

    const { result } = renderHook(() => useReportPrintTemplate("WORK_ORDER"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.bodyHtml).toBe("<p>{{nomor}}</p>");
    expect(mockedSyncroFetch).toHaveBeenCalledWith("/api/v1/report-templates/WORK_ORDER", { method: "GET" });
  });

  it("maps a 404 to null so the print page falls back silently (matrix: template kosong)", async () => {
    mockedSyncroFetch.mockRejectedValue(new SyncroApiError(404, { code: "TEMPLATE_NOT_FOUND" }));

    const { result } = renderHook(() => useReportPrintTemplate("PREVENTIVE"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it("surfaces other errors (not silently swallowed)", async () => {
    mockedSyncroFetch.mockRejectedValue(new SyncroApiError(500, {}));

    const { result } = renderHook(() => useReportPrintTemplate("WORK_ORDER"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useUpsertReportPrintTemplate", () => {
  beforeEach(() => {
    queryClient.clear();
    vi.clearAllMocks();
  });

  it("primes the CORRECT per-type query key on success (WO save must not leak into PREVENTIVE cache)", async () => {
    const saved = {
      id: "t9",
      reportType: "WORK_ORDER" as const,
      bodyHtml: "<p>WO {{nomor}}</p>",
      updatedBy: null,
      updatedAt: "2026-09-15T00:00:00Z",
    };
    mockedSyncroFetch.mockResolvedValue({ data: saved } as never);

    const { result } = renderHook(() => useUpsertReportPrintTemplate("WORK_ORDER"), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync({ bodyHtml: saved.bodyHtml });
    });

    expect(mockedSyncroFetch).toHaveBeenCalledWith("/api/v1/report-templates/WORK_ORDER", {
      method: "PUT",
      body: JSON.stringify({ bodyHtml: saved.bodyHtml }),
    });
    // The print page for WORK_ORDER reads exactly this key…
    expect(queryClient.getQueryData(reportPrintTemplateQueryKey("WORK_ORDER"))).toEqual(saved);
    // …and the other type stays untouched (tab & jenis independen).
    expect(queryClient.getQueryData(reportPrintTemplateQueryKey("PREVENTIVE"))).toBeUndefined();
  });
});
