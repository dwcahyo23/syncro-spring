import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AuditLogEntryView, AuditLogListResponse, ListAuditLogEntriesParams } from "@/lib/api/generated/model";

import { AuditLogPage } from "./audit-log-page";

type AuditLogQueryState = {
  data: { data: AuditLogListResponse };
  isLoading: boolean;
  isError: boolean;
  refetch: ReturnType<typeof vi.fn>;
};

let auditLogQuery: AuditLogQueryState;
let latestParams: ListAuditLogEntriesParams | undefined;

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
  usePlantScope: () => ({
    activePlantId: "all",
    scope: { mode: "UNRESTRICTED", availablePlants: [] },
    loadError: false,
  }),
}));

const queryClient = new QueryClient();

const Wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

describe("Audit Log Page (ATDD RED scaffold)", () => {
  beforeEach(() => {
    mockUser = { id: "user-1", loginIdentifier: "admin@syncro.dev", applicationRole: "SUPER_ADMIN" };
    latestParams = undefined;
    auditLogQuery = {
      data: { data: { items: [], totalElements: 0 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
  });

  it.skip("[P0] resets page to 0 when a filter (from/actor/entityType/to) changes while on page > 0 (R-2.9-6)", () => {
    // RED flagship — skip until the fix lands. audit-log-page.tsx only calls setPage(0) on
    // plant change, size change, and reset, so changing entityType/actor/from/to keeps a
    // stale page index. The test drives pagination through the real "Go to next page" button
    // (onPageChange -> setPage(1)), then changes the plain "From" date input, and asserts the
    // captured useListAuditLogEntries params carry page = 0. Today that assertion FAILS.
    auditLogQuery = {
      data: { data: { items: [createEntry()], totalElements: 75 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    render(<AuditLogPage />, { wrapper: Wrapper });
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(latestParams?.page).toBe(1);
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-08-01" } });
    expect(latestParams?.page).toBe(0);
  });

  it.skip("[P1] renders a skeleton while audit entries are loading", () => {
    // Acceptance lock (T-2.9-P1-07): while isLoading the page shows skeleton rows and neither
    // the table nor the state panels. Skipped scaffold — expected to pass once activated.
    auditLogQuery = {
      data: { data: { items: [], totalElements: 0 } },
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    };
    const { container } = render(<AuditLogPage />, { wrapper: Wrapper });
    expect(container.querySelector('[data-slot="skeleton"]')).not.toBeNull();
    expect(screen.queryByText(/No audit entries yet/)).not.toBeInTheDocument();
  });

  it.skip("[P1] renders the error state and refetches when Retry is clicked", () => {
    // Acceptance lock (T-2.9-P1-07): isError shows the failure panel and Retry triggers refetch.
    auditLogQuery = {
      data: { data: { items: [], totalElements: 0 } },
      isLoading: false,
      isError: true,
      refetch: vi.fn(),
    };
    render(<AuditLogPage />, { wrapper: Wrapper });
    expect(screen.getByText("Audit log could not be loaded")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(auditLogQuery.refetch).toHaveBeenCalledTimes(1);
  });

  it.skip("[P1] renders the empty state when there are no entries and no filters", () => {
    // Acceptance lock (T-2.9-P1-07): default empty list shows "No audit entries yet" and no table.
    render(<AuditLogPage />, { wrapper: Wrapper });
    expect(screen.getByText("No audit entries yet")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it.skip("[P1] renders filtered-empty state and Reset filters clears filters and page", () => {
    // Acceptance lock (T-2.9-P1-07): with filters set and no matches the page shows
    // "No matching entries" plus Reset filters, which clears every filter and resets page to 0.
    auditLogQuery = {
      data: { data: { items: [], totalElements: 60 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    render(<AuditLogPage />, { wrapper: Wrapper });
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(latestParams?.page).toBe(1);
    fireEvent.change(screen.getByLabelText("Actor"), { target: { value: "alice@syncro.dev" } });
    expect(screen.getByText("No matching entries")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Reset filters" })[0]);
    expect(screen.getByText("No audit entries yet")).toBeInTheDocument();
    expect(screen.getByLabelText("Actor")).toHaveValue("");
    expect(latestParams?.page).toBe(0);
    expect(latestParams?.actor).toBeUndefined();
  });

  it.skip("[P1] renders the desktop dense table with sortable headers and expandable before/after detail", () => {
    // Acceptance lock (T-2.9-P1-08). jsdom renders both breakpoint containers (no real CSS
    // media queries), so queries are scoped to the desktop wrapper `.hidden.md\:block` to
    // avoid duplicate matches from the mobile cards.
    auditLogQuery = {
      data: { data: { items: [updateEntry(), createEntry()], totalElements: 2 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    const { container } = render(<AuditLogPage />, { wrapper: Wrapper });
    const desktop = container.querySelector(".hidden.md\\:block");
    if (!desktop) throw new Error("desktop table container not found");
    const desktopView = within(desktop as HTMLElement);
    expect(desktopView.getByRole("table")).toBeInTheDocument();
    for (const header of ["Timestamp", "Actor", "Action", "Entity"]) {
      expect(desktopView.getByRole("button", { name: header })).toBeInTheDocument();
    }
    expect(desktopView.getByText("Pump-01")).toBeInTheDocument();
    expect(desktopView.getByText("Bearing-6205")).toBeInTheDocument();
    fireEvent.click(desktopView.getAllByRole("button", { name: "Toggle change detail" })[0]);
    expect(desktopView.getByText("Field")).toBeInTheDocument();
    expect(desktopView.getByText("Before")).toBeInTheDocument();
    expect(desktopView.getByText("After")).toBeInTheDocument();
    expect(desktopView.getByText("Pump One Renamed")).toBeInTheDocument();
  });

  it.skip("[P1] renders mobile cards grouped by date with expandable detail", () => {
    // Acceptance lock (T-2.9-P1-08). Scoped to the mobile wrapper `.md\:hidden`; the two
    // entries sit on different raw dates (noon UTC keeps the groups distinct in every
    // timezone), so exactly two date-group headings render.
    auditLogQuery = {
      data: { data: { items: [updateEntry(), createEntry()], totalElements: 2 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    const { container } = render(<AuditLogPage />, { wrapper: Wrapper });
    const mobile = container.querySelector(".md\\:hidden");
    if (!mobile) throw new Error("mobile cards container not found");
    const mobileView = within(mobile as HTMLElement);
    expect(mobileView.getAllByRole("heading")).toHaveLength(2);
    expect(mobileView.getByText("Pump-01")).toBeInTheDocument();
    expect(mobileView.getByText("Bearing-6205")).toBeInTheDocument();
    fireEvent.click(mobileView.getAllByRole("button", { name: "Toggle change detail" })[0]);
    expect(mobileView.getByText("Field")).toBeInTheDocument();
    expect(mobileView.getByText("Before")).toBeInTheDocument();
    expect(mobileView.getByText("After")).toBeInTheDocument();
  });
});

function updateEntry(): AuditLogEntryView {
  return {
    id: "entry-1",
    actorId: "actor-1",
    actorName: "alice@syncro.dev",
    action: "UPDATE",
    entityType: "MACHINE",
    entityId: "machine-1",
    entityLabel: "Pump-01",
    plantId: "plant-1",
    createdAt: "2026-08-01T12:00:00.000Z",
    previousValue: { name: "Pump One" },
    newValue: { name: "Pump One Renamed" },
  };
}

function createEntry(): AuditLogEntryView {
  return {
    id: "entry-2",
    actorId: "actor-2",
    actorName: "bob@syncro.dev",
    action: "CREATE",
    entityType: "SPAREPART",
    entityId: "sparepart-1",
    entityLabel: "Bearing-6205",
    plantId: undefined,
    createdAt: "2026-08-03T12:00:00.000Z",
    previousValue: undefined,
    newValue: { code: "BRG-6205", name: "Bearing 6205 ZZ" },
  };
}
