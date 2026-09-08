import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AlertNotificationHistoryResponse, NotificationJobView } from "@/lib/api/generated/model";

import { AlertNotificationHistory } from "./alert-notification-history";

vi.mock("@/lib/api/orval-mutator", () => ({
  SyncroApiError: class SyncroApiError extends Error {},
}));

function job(overrides: Partial<NotificationJobView> = {}): NotificationJobView {
  return {
    id: "job-1",
    alertId: "a-1",
    escalationLevel: "TECHNICIAN",
    status: "SENT",
    recipientDisplayName: "Tech GM1",
    recipientPhoneMasked: "+62***890",
    attemptCount: 4,
    maxAttempts: 3,
    sentAt: "2026-08-20T10:00:00Z",
    createdAt: "2026-08-20T09:00:00Z",
    updatedAt: "2026-08-20T10:00:00Z",
    attempts: [
      {
        attemptNumber: 1,
        status: "FAILED",
        attemptedAt: "2026-08-20T09:01:00Z",
        responseDetail: "HTTP 500",
        traceId: "t1",
      },
      {
        attemptNumber: 2,
        status: "FAILED",
        attemptedAt: "2026-08-20T09:03:00Z",
        responseDetail: "timeout",
        traceId: "t1",
      },
      {
        attemptNumber: 3,
        status: "FAILED",
        attemptedAt: "2026-08-20T09:05:00Z",
        responseDetail: null as unknown as string,
        traceId: "t1",
      },
      { attemptNumber: 4, status: "SENT", attemptedAt: "2026-08-20T10:00:00Z", responseDetail: "ok", traceId: "t1" },
    ],
    ...overrides,
  } as NotificationJobView;
}

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

describe("AlertNotificationHistory", () => {
  beforeEach(() => {
    queryClient.clear();
  });

  it("[P1] DW-86 renders ALL attempts beyond the old three-row cap", () => {
    const history: AlertNotificationHistoryResponse = {
      items: [job()],
      total: 1,
    };
    render(
      <AlertNotificationHistory
        history={history}
        auditEntries={undefined}
        isLoadingHistory={false}
        isLoadingAudit={false}
        errorHistory={null}
        errorAudit={null}
      />,
      { wrapper: Wrapper },
    );

    fireEventExpandAll();
    for (const n of [1, 2, 3, 4]) {
      expect(screen.getAllByText(n, { selector: "td" }).length).toBeGreaterThan(0);
    }
  });

  it("[P2] DW-85 jobs render following escalation order regardless of input order", () => {
    const history: AlertNotificationHistoryResponse = {
      items: [
        job({ id: "job-staff", escalationLevel: "STAFF" as NotificationJobView["escalationLevel"] }),
        job({ id: "job-tech", escalationLevel: "TECHNICIAN" as NotificationJobView["escalationLevel"] }),
      ],
      total: 2,
    };
    const { container } = render(
      <AlertNotificationHistory
        history={history}
        auditEntries={undefined}
        isLoadingHistory={false}
        isLoadingAudit={false}
        errorHistory={null}
        errorAudit={null}
      />,
      { wrapper: Wrapper },
    );

    const levels = Array.from(container.querySelectorAll("tbody tr td:first-child")).map((cell) => cell.textContent);
    expect(levels.indexOf("TECHNICIAN")).toBeLessThan(levels.indexOf("STAFF"));
  });

  function fireEventExpandAll() {
    const toggles = screen.getAllByRole("button", { name: "Toggle attempts" });
    fireEvent.click(toggles[0]);
  }
});
