import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AuditLogListResponse, ListAuditLogEntriesParams } from "@/lib/api/generated/model";

import { createAuditLogEntry } from "../../../tests/support/helpers/audit-log-factory";
import { AuditLogPage } from "./audit-log-page";

type AuditLogQueryState = {
  data: { data: AuditLogListResponse };
  isLoading: boolean;
  isError: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

let auditLogQuery: AuditLogQueryState;
let latestParams: ListAuditLogEntriesParams | undefined;
let mockScope: {
  activePlantId: string;
  scope: { mode: "UNRESTRICTED" | "EMPTY" | "ASSIGNED"; availablePlants: unknown[] };
  loadError: boolean;
};

let mockUser = { id: "user-1", loginIdentifier: "admin@syncro.dev", applicationRole: "SUPER_ADMIN" };

vi.mock("@/lib/api/generated/syncro", () => ({
  useListAuditLogEntries: (params: ListAuditLogEntriesParams) => {
    latestParams = params;
    return auditLogQuery;
  },
  useListPlants: () => ({ data: { data: { items: [] } }, isLoading: false }),
}));

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockUser,
}));

vi.mock("@/features/plant-scope/plant-scope-store", () => ({
  usePlantScope: () => mockScope,
}));

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

describe("Audit Log Page — automate edge cases (query contract + scope)", () => {
  beforeEach(() => {
    mockUser = { id: "user-1", loginIdentifier: "admin@syncro.dev", applicationRole: "SUPER_ADMIN" };
    mockScope = {
      activePlantId: "all",
      scope: { mode: "UNRESTRICTED", availablePlants: [] },
      loadError: false,
    };
    latestParams = undefined;
    auditLogQuery = {
      data: { data: { items: [], totalElements: 0 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
  });

  it("[P1] default query contract has no filters, page 0, size 50, newest-first sort", () => {
    render(<AuditLogPage />, { wrapper: Wrapper });
    expect(latestParams).toMatchObject({
      entityType: undefined,
      actor: undefined,
      plantId: undefined,
      from: undefined,
      to: undefined,
      page: 0,
      size: 50,
      sort: "createdAt,desc",
    });
  });

  it("[P1] from/to date filters send UTC day boundaries", () => {
    render(<AuditLogPage />, { wrapper: Wrapper });
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-08-01" } });
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-08-07" } });
    expect(latestParams?.from).toBe("2026-08-01T00:00:00.000Z");
    expect(latestParams?.to).toBe("2026-08-07T23:59:59.999Z");
  });

  it("[P1] actor filter is trimmed and omitted when blank", () => {
    render(<AuditLogPage />, { wrapper: Wrapper });
    fireEvent.change(screen.getByLabelText("Actor"), { target: { value: "  alice@syncro.dev  " } });
    expect(latestParams?.actor).toBe("alice@syncro.dev");
    fireEvent.change(screen.getByLabelText("Actor"), { target: { value: "   " } });
    expect(latestParams?.actor).toBeUndefined();
  });

  it("[P1] sorting by the Actor column toggles asc then desc", () => {
    auditLogQuery = {
      data: { data: { items: [createAuditLogEntry()], totalElements: 1 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    const { container } = render(<AuditLogPage />, { wrapper: Wrapper });
    const desktop = container.querySelector(".hidden.md\\:block");
    if (!desktop) throw new Error("desktop table container not found");
    const desktopView = within(desktop as HTMLElement);
    fireEvent.click(desktopView.getByRole("button", { name: "Actor" }));
    expect(latestParams?.sort).toBe("actorName,asc");
    fireEvent.click(desktopView.getByRole("button", { name: "Actor" }));
    expect(latestParams?.sort).toBe("actorName,desc");
  });

  it("[P2] pagination controls move the page parameter", () => {
    auditLogQuery = {
      data: { data: { items: [createAuditLogEntry(), createAuditLogEntry()], totalElements: 75 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    render(<AuditLogPage />, { wrapper: Wrapper });
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(latestParams?.page).toBe(1);
    fireEvent.click(screen.getByRole("button", { name: "Go to previous page" }));
    expect(latestParams?.page).toBe(0);
  });

  it("[P1] EMPTY scope disables the plant selector", () => {
    mockScope = { activePlantId: "all", scope: { mode: "EMPTY", availablePlants: [] }, loadError: false };
    render(<AuditLogPage />, { wrapper: Wrapper });
    expect(screen.getByLabelText("Plant")).toBeDisabled();
  });
});
