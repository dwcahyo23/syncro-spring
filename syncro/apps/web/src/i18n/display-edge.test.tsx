import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, renderHook, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it } from "vitest";

import { StatusBadge } from "@/components/syncro/status-badge";
import { TooltipProvider } from "@/components/ui/tooltip";
import { AlertStatusBadge } from "@/features/alerts/alert-status-badge";
import { AlertTypeBadge } from "@/features/alerts/alert-type-badge";
import { type ApiErrorResponse, apiErrorMessage } from "@/lib/api/error-response";
import { useNumberFormatter, useRelativeTime } from "@/lib/i18n/format";
import enMessages from "@/messages/en.json";
import idMessages from "@/messages/id.json";

// Story 23-2 I/O matrix coverage: renders the Indonesian locale (the e2e specs
// run the login shell, which has no badges; the component tests pin `en`).

function IdProvider({ children }: { children: ReactNode }) {
  return (
    <NextIntlClientProvider locale="id" messages={idMessages}>
      <QueryClientProvider client={new QueryClient()}>
        <TooltipProvider>{children}</TooltipProvider>
      </QueryClientProvider>
    </NextIntlClientProvider>
  );
}

describe("locale-aware display edge (matrix rows 3, 5, 6, 7)", () => {
  it("status badge renders the Indonesian catalog label keyed by code (row 3)", () => {
    render(<AlertStatusBadge status="ACKNOWLEDGED" />, { wrapper: IdProvider });
    expect(screen.getByText("Diakui")).toBeTruthy();
  });

  it("status badge falls back to the raw code for an unknown value (row 3 error arm)", () => {
    render(<AlertStatusBadge status={"WEIRD" as never} />, { wrapper: IdProvider });
    expect(screen.getByText("WEIRD")).toBeTruthy();
  });

  // P1 crash-guard regression (review pass): unknown server codes must never
  // render a raw next-intl error or throw — they resolve to the UNKNOWN arm.
  it("alert type badge maps an unknown code to the UNKNOWN label (P1 guard)", () => {
    render(<AlertTypeBadge alertType={"SOMETHING_NEW" as never} />, { wrapper: IdProvider });
    expect(screen.getByText("Tidak diketahui")).toBeTruthy();
  });

  it("telemetry status badge survives an unknown freshness value (P1 guard)", () => {
    render(<StatusBadge freshness={"GLITCHED" as never} />, { wrapper: IdProvider });
    // Fallback is the raw code itself — no throw, no key echo.
    expect(screen.getByText("GLITCHED")).toBeTruthy();
  });

  it("currency formats with id-ID grouping and Rp symbol (row 5)", () => {
    const { result } = renderHook(() => useNumberFormatter(), { wrapper: IdProvider });
    expect(result.current.currency(1500, "IDR")).toContain("1.500");
    expect(result.current.number(1234567)).toContain("1.234.567");
  });

  it("relative time renders the Indonesian minutes-ago bucket (row 6)", () => {
    const now = new Date("2026-09-09T12:00:00Z");
    const { result } = renderHook(() => useRelativeTime(), { wrapper: IdProvider });
    expect(result.current(new Date("2026-09-09T11:55:00Z"), now)).toBe("5 menit lalu");
    expect(result.current(new Date("2026-09-09T11:59:40Z"), now)).toBe("baru saja");
  });

  it("ICU plural selects one vs other in en, single form in id (row 7)", () => {
    const now = new Date("2026-09-09T12:00:00Z");
    const EnWrapper = ({ children }: { children: ReactNode }) => (
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <QueryClientProvider client={new QueryClient()}>
          <TooltipProvider>{children}</TooltipProvider>
        </QueryClientProvider>
      </NextIntlClientProvider>
    );
    const one = renderHook(() => useRelativeTime(), { wrapper: EnWrapper });
    expect(one.result.current(new Date("2026-09-09T11:59:00Z"), now)).toBe("1 minute ago");
    const many = renderHook(() => useRelativeTime(), { wrapper: EnWrapper });
    expect(many.result.current(new Date("2026-09-09T11:55:00Z"), now)).toBe("5 minutes ago");
    const id = renderHook(() => useRelativeTime(), { wrapper: IdProvider });
    expect(id.result.current(new Date("2026-09-09T11:55:00Z"), now)).toBe("5 menit lalu");
  });
});

describe("apiErrorMessage chain (matrix row 4)", () => {
  // Minimal ErrorTranslator over the id errors catalog — same contract as
  // next-intl's t() slice the helper depends on.
  const errors = (idMessages as unknown as { errors: Record<string, string> }).errors;
  const t = Object.assign((key: string) => errors[key] ?? key, { has: (key: string) => key in errors });

  const response = (code: string, message: string): ApiErrorResponse => ({ code, message });

  it("known code resolves to the translated errors string", () => {
    expect(apiErrorMessage(t, response("INVALID_STATE_TRANSITION", "backend english"))).toBe(
      "Aksi ini tidak diizinkan pada status saat ini.",
    );
  });

  it("unknown code falls back to the backend message (data, rendered as-is)", () => {
    expect(apiErrorMessage(t, response("SOMETHING_NEW", "Backend said no."))).toBe("Backend said no.");
  });

  it("null payload / empty message resolves to the generic translated error, never a raw code", () => {
    expect(apiErrorMessage(t, null)).toBe("Terjadi kesalahan. Silakan coba lagi.");
    expect(apiErrorMessage(t, response("X", ""))).toBe("Terjadi kesalahan. Silakan coba lagi.");
  });
});
