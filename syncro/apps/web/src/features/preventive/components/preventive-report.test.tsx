// @vitest-environment jsdom
import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PreventiveReportView } from "@/features/preventive/types";
import type { ReportPrintTemplateView } from "@/features/report-templates/hooks/use-report-print-template";
import { I18nProvider } from "@/test/i18n-wrapper";

import { PreventiveReportPage } from "./preventive-report";

// ---------------------------------------------------------------------------
// Same three-state matrix as the WO print page (amended spec 2026-09-15):
// no template / empty template / real template with the mandatory non-text
// blocks below it. PrintTemplateView stays REAL on purpose.
// ---------------------------------------------------------------------------

const pmData: PreventiveReportView = {
  scheduleId: "sch-1",
  programTitle: "PM UNIQUE KOP",
  category: "MECHANICAL",
  scheduleType: "WEEKLY",
  autoWorkorder: true,
  machineId: "m-1",
  dueDate: "2026-09-10",
  scheduleStatus: "COMPLETED",
  completedAt: "2026-09-10T08:00:00Z",
  performedBy: "Budi",
  notes: "OK",
  assessment: "GOOD",
  signerIdentity: null,
  approvedAt: null,
  items: [{ position: 1, label: "Getaran", value: "0.2", lsl: "0", usl: "1", note: null }],
  evidence: [{ id: "e-1", filename: "pm.jpg", contentType: "image/jpeg", presignedUrl: "http://x/pm.jpg" }],
  signaturePresignedUrl: null,
  workOrderId: "WO-2609-00007",
};

let mockPmQuery = { data: pmData as PreventiveReportView | null, isLoading: false, isError: false };
let mockTemplateState: { data: ReportPrintTemplateView | null; isPending: boolean } = {
  data: null,
  isPending: false,
};

vi.mock("@/features/preventive/hooks/use-preventive", () => ({
  usePreventiveReport: () => ({ ...mockPmQuery, refetch: vi.fn() }),
}));
vi.mock("@/features/report-templates/hooks/use-report-print-template", () => ({
  useReportPrintTemplate: () => mockTemplateState,
}));
vi.mock("@/features/settings/hooks/use-company-logo", () => ({
  useCompanyLogo: () => ({ data: null }),
}));

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
const Wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>
    <I18nProvider>{children}</I18nProvider>
  </QueryClientProvider>
);

function renderPage() {
  return render(<PreventiveReportPage scheduleId="sch-1" onClose={() => undefined} />, { wrapper: Wrapper });
}

function templateWith(bodyHtml: string): ReportPrintTemplateView {
  return { id: "t-2", reportType: "PREVENTIVE", bodyHtml, updatedBy: null, updatedAt: "2026-09-15T00:00:00Z" };
}

beforeEach(() => {
  vi.useFakeTimers();
  mockPmQuery = { data: pmData, isLoading: false, isError: false };
  mockTemplateState = { data: null, isPending: false };
  Object.defineProperty(window, "print", { value: vi.fn(), writable: true, configurable: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("PreventiveReportPage — template states (amended spec 2026-09-15)", () => {
  it("no template: zero .print-template-body, default header (programTitle), auto-print scheduled", async () => {
    const { container } = renderPage();
    expect(container.querySelectorAll(".print-template-body")).toHaveLength(0);
    expect(container.textContent).toContain("PM UNIQUE KOP");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(window.print).toHaveBeenCalledTimes(1);
  });

  it("empty template (bodyHtml ''): identical default, zero .print-template-body", async () => {
    mockTemplateState = { data: templateWith(""), isPending: false };
    const { container } = renderPage();
    expect(container.querySelectorAll(".print-template-body")).toHaveLength(0);
    expect(container.textContent).toContain("PM UNIQUE KOP");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(window.print).toHaveBeenCalledTimes(1);
  });

  it("real template: all 10 PREVENTIVE keys resolve (empty → —), checklist/evidence/signature below, no duplicate kop", () => {
    mockTemplateState = {
      data: templateWith(
        "<p>{{nomor}}|{{tanggal}}|{{kategori}}|{{status}}|{{teknisi}}|{{deskripsi}}|{{jadwal}}|{{penilaian}}|{{catatan}}|{{persetujuan}}</p>",
      ),
      isPending: false,
    };
    const { container } = renderPage();
    const bodies = container.querySelectorAll(".print-template-body");
    expect(bodies).toHaveLength(1);
    const bodyText = bodies[0].textContent ?? "";
    expect(bodyText).toContain("WO-2609-00007");
    expect(bodyText).toContain("2026-09-10");
    expect(bodyText).toContain("MECHANICAL");
    expect(bodyText).toContain("Budi");
    expect(bodyText).toContain("PM UNIQUE KOP");
    expect(bodyText).toContain("GOOD");
    expect(bodyText).not.toContain("{{");
    // persetujuan = signerIdentity (null here) → "—" per matrix row "Placeholder data kosong".
    expect(bodyText).toContain("—");

    const surface = container.querySelector(".print-surface");
    const children = Array.from(surface?.children ?? []);
    const bodyIndex = children.indexOf(bodies[0]);
    expect(bodyIndex).toBeGreaterThanOrEqual(0);
    const tail = children.slice(bodyIndex + 1).map((el) => el.textContent ?? "");
    // Audit trail: checklist measurements, evidence and the (unsigned-marker)
    // signature card always follow the template.
    expect(tail.some((text) => text.includes("Checklist Results"))).toBe(true);
    expect(tail.some((text) => text.includes("Evidence"))).toBe(true);
    expect(tail.some((text) => text.includes("Not yet signed"))).toBe(true);
    // The default header card (programTitle as its title) must NOT render on top.
    expect(children.slice(0, bodyIndex)).toHaveLength(0);
  });

  it("real template + signed: the actual approval card is used below the template", () => {
    mockPmQuery = {
      data: { ...pmData, signerIdentity: "Kepala", approvedAt: "2026-09-10T09:00:00Z" },
      isLoading: false,
      isError: false,
    };
    mockTemplateState = { data: templateWith("<h1>KOP</h1>"), isPending: false };
    const { container } = renderPage();
    expect(container.textContent).toContain("Signed by:");
    expect(container.textContent).not.toContain("Not yet signed");
  });

  it("templatePending: window.print is never scheduled while the template read is in flight", async () => {
    mockTemplateState = { data: null, isPending: true };
    renderPage();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(window.print).not.toHaveBeenCalled();
  });
});
