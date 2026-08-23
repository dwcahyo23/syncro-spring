import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SparepartManagement } from "./sparepart-management";

// ---------------------------------------------------------------------------
// Module-level mocks (hoisted by Vitest — cannot reference outer variables)
// ---------------------------------------------------------------------------

let mockUser = {
  id: "user-1",
  loginIdentifier: "admin@syncro.dev",
  applicationRole: "SUPER_ADMIN" as string,
};

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockUser,
}));

type MockItem = Record<string, unknown>;

const mockListTaxonomies = {
  data: {
    data: {
      items: [
        { id: "cat-1", code: "ELECTRIC", name: "Electric", dimension: "CATEGORY" },
        { id: "brand-1", code: "WECON", name: "Wecon", dimension: "BRAND", categoryId: "cat-1" },
        { id: "kind-1", code: "PLC", name: "PLC", dimension: "KIND", categoryId: "cat-1" },
        { id: "type-1", code: "LX5", name: "LX5", dimension: "TYPE", categoryId: "cat-1" },
      ],
    },
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

const mockListMachines = {
  data: {
    data: {
      items: [
        {
          id: "machine-1",
          code: "MCH-1",
          name: "Machine 1",
          plantId: "plant-1",
          plantCode: "P1",
          plantName: "Plant 1",
        },
      ],
    },
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

const mockListSpareparts = {
  data: {
    data: {
      items: [
        {
          id: "sp-1",
          code: "MCH1P1ELEPLCWEC000",
          machine: {
            id: "machine-1",
            code: "MCH-1",
            name: "Machine 1",
            plantId: "plant-1",
            plantCode: "P1",
            plantName: "Plant 1",
          },
          category: { id: "cat-1", code: "ELECTRIC", name: "Electric" },
          brand: { id: "brand-1", code: "WECON", name: "Wecon" },
          kind: { id: "kind-1", code: "PLC", name: "PLC" },
          type: { id: "type-1", code: "LX5", name: "LX5" },
          materialCode: "MC-001",
          leadTimeHours: 36,
          createdAt: "2026-08-24T00:00:00Z",
          updatedAt: "2026-08-24T00:00:00Z",
        },
        {
          id: "sp-2",
          code: "MCH1P1ELEPLCWEC001",
          machine: {
            id: "machine-1",
            code: "MCH-1",
            name: "Machine 1",
            plantId: "plant-1",
            plantCode: "P1",
            plantName: "Plant 1",
          },
          category: { id: "cat-1", code: "ELECTRIC", name: "Electric" },
          brand: { id: "brand-1", code: "WECON", name: "Wecon" },
          kind: { id: "kind-1", code: "PLC", name: "PLC" },
          type: { id: "type-1", code: "LX5", name: "LX5" },
          materialCode: undefined,
          leadTimeHours: undefined,
          createdAt: "2026-08-24T00:00:00Z",
          updatedAt: "2026-08-24T00:00:00Z",
        },
      ] as MockItem[],
      totalElements: 2,
    },
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

let mockUpdateSparepart = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockPatchProcurement = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };

vi.mock("@/lib/api/generated/syncro", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  useListSparepartTaxonomies: vi.fn(() => mockListTaxonomies),
  useListMachines: vi.fn(() => mockListMachines),
  useListSpareparts: vi.fn(() => mockListSpareparts),
  useCreateSparepart: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useCreateSparepartTaxonomy: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useUpdateSparepart: vi.fn(() => mockUpdateSparepart),
  usePatchSparepartProcurement: vi.fn(() => mockPatchProcurement),
  useDeleteSparepart: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  getListSparepartsQueryKey: vi.fn(() => ["/mock-spareparts-key"]),
  getListSparepartTaxonomiesQueryKey: vi.fn(() => ["/mock-taxonomies-key"]),
}));

// ---------------------------------------------------------------------------
// Test wrapper
// ---------------------------------------------------------------------------

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

const Wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

function resetMocks() {
  mockUser = { id: "user-1", loginIdentifier: "admin@syncro.dev", applicationRole: "SUPER_ADMIN" };
  mockUpdateSparepart = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
  mockPatchProcurement = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
}

describe("SparepartManagement procurement readiness (Story 8-2)", () => {
  beforeEach(() => {
    resetMocks();
  });

  it("renders material code and lead time columns, showing dashes when unset", () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    expect(screen.getByText("Material code")).toBeInTheDocument();
    expect(screen.getByText("Lead time")).toBeInTheDocument();
    expect(screen.getByText("MC-001")).toBeInTheDocument();
    expect(screen.getByText("36 h")).toBeInTheDocument();
    // Second sparepart has neither value set.
    const dashes = screen.getAllByText("-");
    expect(dashes.length).toBeGreaterThan(0);
  });

  it("sends PATCH after PUT only when procurement values changed", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    const materialCodeInput = await screen.findByTestId("material-code-input");
    expect(materialCodeInput).toHaveValue("MC-001");

    fireEvent.change(materialCodeInput, { target: { value: "MC-002" } });
    fireEvent.change(screen.getByTestId("lead-time-input"), { target: { value: "180" } });

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    fireEvent.click(screen.getByRole("button", { name: "Save sparepart" }));

    await waitFor(() => {
      expect(mockUpdateSparepart.mutateAsync).toHaveBeenCalledTimes(1);
      expect(mockPatchProcurement.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockPatchProcurement.mutateAsync).toHaveBeenCalledWith({
      sparepartId: "sp-1",
      data: { materialCode: "MC-002", leadTimeHours: 180 },
    });
  });

  it("skips PATCH when procurement values are untouched", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    await screen.findByTestId("material-code-input");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    fireEvent.click(screen.getByRole("button", { name: "Save sparepart" }));

    await waitFor(() => {
      expect(mockUpdateSparepart.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockPatchProcurement.mutateAsync).not.toHaveBeenCalled();
  });

  it("surfaces backend job-scope denial messages in the form", async () => {
    mockPatchProcurement = {
      mutateAsync: vi.fn().mockRejectedValue(
        new (await import("@/lib/api/orval-mutator")).SyncroApiError(403, {
          code: "JOB_SCOPE_REQUIRED",
          message: "This action requires job scope LEADER or above.",
        }),
      ),
      mutate: vi.fn(),
      isPending: false,
    };

    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    fireEvent.change(await screen.findByTestId("material-code-input"), { target: { value: "MC-NEW" } });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    fireEvent.click(screen.getByRole("button", { name: "Save sparepart" }));

    await waitFor(() => {
      expect(screen.getByText(/job scope LEADER or above/)).toBeInTheDocument();
    });
  });
});
