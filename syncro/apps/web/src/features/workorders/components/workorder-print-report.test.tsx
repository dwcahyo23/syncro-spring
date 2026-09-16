// @vitest-environment jsdom
import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ReportPrintTemplateView } from "@/features/report-templates/hooks/use-report-print-template";
import type { WorkorderPrintReportView } from "@/features/workorders/print-types";
import { I18nProvider } from "@/test/i18n-wrapper";

import { WorkorderPrintReportPage } from "./workorder-print-report";

// ---------------------------------------------------------------------------
// Module-level mocks: every remote hook of the print page is stubbed so the
// three matrix states (no template / empty template / real template) can be
// driven without a server. PrintTemplateView stays REAL — these tests also
// cover the sanitize→substitute render pipeline end to end.
// ---------------------------------------------------------------------------

const baseHeader = {
  id: "WO-2609-00001",
  source: "MANUAL",
  status: "CLOSED",
  categoryCode: "BRK",
  categoryLabel: "Breakdown",
  machineId: "m-1",
  machineCode: "MC-1",
  machineName: "Press A",
  plantCode: "P1",
  description: "Bearing aus",
  assignedTechnicianId: "t-1",
  assignedTechnicianName: "Andi",
  createdAt: "2026-09-01T08:00:00Z",
  updatedAt: "2026-09-02T10:00:00Z",
  doneReason: null,
};

const woData: WorkorderPrintReportView = {
  header: baseHeader,
  sessions: [
    {
      id: "s-1",
      technicianId: "t-1",
      description: "Ganti bearing",
      startedAt: "2026-09-01T09:00:00Z",
      endedAt: "2026-09-01T11:00:00Z",
      durationMinutes: 120,
    },
  ],
  narrative: {
    reportChronological: "Led",
    reportAnalyze: "Wear",
    reportCorrective: "Replaced",
    reportPreventive: "Schedule lube",
  },
  cpk: {
    cpCkLower: "1.0",
    cpCkUpper: "1.3",
    cpk: "1.2",
    cpkPdfPresignedUrl: null,
    fmeaFailureType: null,
    stopTimeReason: null,
    stopTimeDetail: null,
  },
  evidence: [{ id: "e-1", filename: "foto.jpg", contentType: "image/jpeg", presignedUrl: "http://x/foto.jpg" }],
  parts: [{ id: "p-1", materialCode: "BRG-9", quantity: 2, status: "APPROVED", notes: null }],
  signature: null,
  leaderSignature: null,
};

let mockWoQuery = { data: woData as WorkorderPrintReportView | null, isLoading: false, isError: false };
let mockTemplateState: { data: ReportPrintTemplateView | null; isPending: boolean } = {
  data: null,
  isPending: false,
};
let mockLogo: { presignedUrl: string | null } | null = null;

vi.mock("@/features/workorders/hooks/use-workorder-print-report", () => ({
  useWorkorderPrintReport: () => ({ ...mockWoQuery, refetch: vi.fn() }),
}));
vi.mock("@/features/report-templates/hooks/use-report-print-template", () => ({
  useReportPrintTemplate: () => mockTemplateState,
}));
vi.mock("@/features/settings/hooks/use-company-logo", () => ({
  useCompanyLogo: () => ({ data: mockLogo }),
}));
vi.mock("@/features/workorders/hooks/use-workorder-approve", () => ({
  useWorkorderApprove: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/features/workorders/hooks/use-workorder-signature-upload", () => ({
  useWorkorderSignatureUpload: () => ({ mutate: vi.fn(), isPending: false }),
}));

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
const Wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>
    <I18nProvider>{children}</I18nProvider>
  </QueryClientProvider>
);

function renderPage() {
  return render(<WorkorderPrintReportPage workOrderId="WO-2609-00001" onClose={() => undefined} />, {
    wrapper: Wrapper,
  });
}

function templateWith(bodyHtml: string): ReportPrintTemplateView {
  return { id: "t-1", reportType: "WORK_ORDER", bodyHtml, updatedBy: null, updatedAt: "2026-09-15T00:00:00Z" };
}

beforeEach(() => {
  vi.useFakeTimers();
  mockWoQuery = { data: woData, isLoading: false, isError: false };
  mockTemplateState = { data: null, isPending: false };
  mockLogo = null;
  // jsdom lacks HTMLImageElement.decode; the auto-print effect waits on it whenever
  // an <img> (logo / stamp token) is in the page.
  if (!HTMLImageElement.prototype.decode) {
    Object.defineProperty(HTMLImageElement.prototype, "decode", { value: () => Promise.resolve(), configurable: true });
  }
  Object.defineProperty(window, "print", { value: vi.fn(), writable: true, configurable: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("WorkorderPrintReportPage — template states (amended spec 2026-09-15)", () => {
  it("no template (404 → null): zero .print-template-body, default header card, auto-print scheduled", async () => {
    const { container } = renderPage();
    expect(container.querySelectorAll(".print-template-body")).toHaveLength(0);
    // Default kop is the historical headerTitle string ("… — WO-id"); the
    // toolbar h1 ("Work Order Report") also exists but is no-print chrome.
    expect(container.textContent).toContain("Work Order Report —");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(window.print).toHaveBeenCalledTimes(1);
  });

  it("empty template (bodyHtml ''): identical default, zero .print-template-body", async () => {
    mockTemplateState = { data: templateWith(""), isPending: false };
    const { container } = renderPage();
    expect(container.querySelectorAll(".print-template-body")).toHaveLength(0);
    expect(container.textContent).toContain("Work Order Report —");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(window.print).toHaveBeenCalledTimes(1);
  });

  it("real template: .print-template-body renders with every {{key}} substituted, and the non-text blocks sit below it", () => {
    mockTemplateState = {
      data: templateWith("<h1>KOP</h1><p>{{nomor}}|{{mesin}}|{{kategori}}|{{cpk}}|{{stopTime}}|{{teknisi}}</p>"),
      isPending: false,
    };
    const { container } = renderPage();
    const bodies = container.querySelectorAll(".print-template-body");
    expect(bodies).toHaveLength(1);
    const bodyText = bodies[0].textContent ?? "";
    // P3a runtime parity: every key present in the template resolved from the
    // consumer map — null cpk/stopTime became "—", the rest carry real data.
    expect(bodyText).toContain("WO-2609-00001");
    expect(bodyText).toContain("MC-1 · Press A");
    expect(bodyText).toContain("BRK · Breakdown");
    expect(bodyText).toContain("1.2");
    expect(bodyText).toContain("Andi");
    expect(bodyText).not.toContain("{{");
    expect(bodyText).toContain("—");
  });

  it("real template: evidence/parts/sessions/signature blocks always render below the template (audit trail)", () => {
    mockTemplateState = { data: templateWith("<h1>KOP {{nomor}}</h1>"), isPending: false };
    const { container } = renderPage();
    const surface = container.querySelector(".print-surface");
    expect(surface).not.toBeNull();
    const bodyEl = surface?.querySelector(".print-template-body");
    expect(bodyEl).not.toBeNull();
    const children = Array.from(surface?.children ?? []);
    const bodyIndex = children.indexOf(bodyEl as Element);
    expect(bodyIndex).toBeGreaterThanOrEqual(0);
    // The signature card (unsigned → mandatory "Not yet signed" marker) and the
    // evidence/parts/sessions cards must all follow the template block.
    const tail = children.slice(bodyIndex + 1).map((el) => el.textContent ?? "");
    expect(tail.some((text) => text.includes("Not yet signed"))).toBe(true);
    expect(tail.some((text) => text.includes("Repair Sessions"))).toBe(true);
    expect(tail.some((text) => text.includes("Sparepart Requests"))).toBe(true);
    expect(tail.some((text) => text.includes("Evidence"))).toBe(true);
    // Template mode must NOT duplicate the default text blocks it replaces.
    // (Assert the header card's "… —" kop string, not the no-print toolbar h1.)
    expect(container.textContent).not.toContain("Work Order Report —");
    expect(tail.some((text) => text.includes("Report Narrative"))).toBe(false);
  });

  it("templatePending: window.print is never scheduled while the template read is in flight", async () => {
    mockTemplateState = { data: null, isPending: true };
    renderPage();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(window.print).not.toHaveBeenCalled();
  });

  it("P2: kategori with a null label prints the code alone — never 'null ·'", () => {
    mockWoQuery = {
      data: { ...woData, header: { ...baseHeader, categoryLabel: null } },
      isLoading: false,
      isError: false,
    };
    mockTemplateState = { data: templateWith("<p>[{{kategori}}]</p>"), isPending: false };
    const { container } = renderPage();
    const bodyText = container.querySelector(".print-template-body")?.textContent ?? "";
    expect(bodyText).toContain("[BRK]");
    expect(bodyText).not.toContain("null");
  });

  // --- leader-signature spec: image tokens last hop (page map → rendered template) ---

  it("{{logo}}/{{tandaTangan}} render as <img> with the right src/class at the token position", () => {
    mockLogo = { presignedUrl: "http://garage/logo.png" };
    mockWoQuery = {
      data: {
        ...woData,
        leaderSignature: { userId: "l-1", displayName: "Budi", signaturePresignedUrl: "http://garage/sig.png" },
      },
      isLoading: false,
      isError: false,
    };
    mockTemplateState = { data: templateWith("<p>KOP {{logo}} TTD {{tandaTangan}}</p>"), isPending: false };
    const { container } = renderPage();
    const body = container.querySelector(".print-template-body");
    expect(body).not.toBeNull();
    const imgs = body?.querySelectorAll("img") ?? [];
    expect(imgs).toHaveLength(2);
    expect(imgs[0].getAttribute("src")).toBe("http://garage/logo.png");
    expect(imgs[0].getAttribute("class")).toBe("print-logo");
    expect(imgs[1].getAttribute("src")).toBe("http://garage/sig.png");
    expect(imgs[1].getAttribute("class")).toBe("print-signature");
    // The tokens never leak raw or print "—" (they are images, not text).
    expect(body?.textContent).not.toContain("{{");
    expect(body?.textContent).not.toContain("—");
  });

  it("missing leader signature / unconfigured logo render nothing (no broken img, no dash)", () => {
    mockLogo = null;
    mockWoQuery = { data: { ...woData, leaderSignature: null }, isLoading: false, isError: false };
    mockTemplateState = { data: templateWith("<p>[{{logo}}][{{tandaTangan}}]</p>"), isPending: false };
    const { container } = renderPage();
    const body = container.querySelector(".print-template-body");
    expect(body?.querySelectorAll("img")).toHaveLength(0);
    expect(body?.textContent).toBe("[][]");
  });
});
