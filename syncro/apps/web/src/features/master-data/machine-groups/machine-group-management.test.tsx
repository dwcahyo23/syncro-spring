import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SyncroApiError } from "@/lib/api/orval-mutator";

import { MachineGroupManagement } from "./machine-group-management";

// ---------------------------------------------------------------------------
// Module-level mocks (hoisted by Vitest — cannot reference outer variables)
// ---------------------------------------------------------------------------

let mockPlantScope = {
  activePlantId: "plant-1" as string | null,
  scope: { mode: "UNRESTRICTED" } as { mode: string } | null,
  loadError: false,
};

vi.mock("@/features/plant-scope/plant-scope-store", () => ({
  usePlantScope: () => mockPlantScope,
}));

let mockUser = {
  id: "user-1",
  loginIdentifier: "admin@syncro.dev",
  applicationRole: "SUPER_ADMIN" as string,
};

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockUser,
}));

// Mutable mock state for API hooks — reassigned per test in beforeEach
let mockListPlants = {
  data: { data: { items: [{ id: "plant-1", code: "P1", name: "Plant 1" }] } },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

let mockListMachineGroups = {
  data: {
    data: {
      items: [] as {
        id: string;
        name: string;
        plantId: string;
        plantCode: string;
        plantName: string;
        createdAt: string;
      }[],
      totalElements: 0,
    },
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

let mockCreateMachineGroup = {
  mutateAsync: vi.fn(),
  mutate: vi.fn(),
  isPending: false,
};

vi.mock("@/lib/api/generated/syncro", () => ({
  useListPlants: vi.fn(() => mockListPlants),
  useListMachineGroups: vi.fn(() => mockListMachineGroups),
  useCreateMachineGroup: vi.fn(() => mockCreateMachineGroup),
  useUpdateMachineGroup: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useDeleteMachineGroup: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  getListMachineGroupsQueryKey: vi.fn(() => ["/mock-machine-groups-key"]),
  getListPlantsQueryKey: vi.fn(() => ["/mock-plants-key"]),
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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("MachineGroupManagement UI states", () => {
  beforeEach(() => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "admin@syncro.dev",
      applicationRole: "SUPER_ADMIN",
    };
    mockPlantScope = {
      activePlantId: "plant-1",
      scope: { mode: "UNRESTRICTED" },
      loadError: false,
    };
    mockListPlants = {
      data: { data: { items: [{ id: "plant-1", code: "P1", name: "Plant 1" }] } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockListMachineGroups = {
      data: { data: { items: [], totalElements: 0 } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockCreateMachineGroup = {
      mutateAsync: vi.fn(),
      mutate: vi.fn(),
      isPending: false,
    };
    queryClient.clear();
  });

  // -------------------------------------------------------------------------
  // 1. Empty state
  // -------------------------------------------------------------------------
  it("shows empty state when there are no machine groups for the selected plant", () => {
    render(<MachineGroupManagement />, { wrapper: Wrapper });

    expect(screen.getByRole("heading", { name: /no machine groups yet/i })).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // 2. Loading state
  // -------------------------------------------------------------------------
  it("shows skeleton while machine groups are loading", () => {
    mockListMachineGroups = {
      ...mockListMachineGroups,
      isLoading: true,
      data: { data: { items: [], totalElements: 0 } },
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    // Normal content states must not be present
    expect(screen.queryByRole("heading", { name: /no machine groups yet/i })).toBeNull();
    expect(screen.queryByRole("heading", { name: /machine groups could not be loaded/i })).toBeNull();
    // Skeleton renders 3 × <Skeleton className="h-10 w-full" />
    const skeletons = document.querySelectorAll(".h-10.w-full");
    expect(skeletons.length).toBeGreaterThanOrEqual(3);
  });

  // -------------------------------------------------------------------------
  // 3. Error state
  // -------------------------------------------------------------------------
  it("shows error state when machine groups fail to load", () => {
    mockListMachineGroups = {
      ...mockListMachineGroups,
      isError: true,
      isLoading: false,
      data: { data: { items: [], totalElements: 0 } },
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    expect(
      screen.getByRole("heading", { name: /machine groups could not be loaded/i }),
    ).toBeTruthy();
    expect(screen.getByRole("button", { name: /retry/i })).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // 4. Read-only state (VIEWER role)
  // -------------------------------------------------------------------------
  it("shows read-only badge instead of create button for VIEWER role", () => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "viewer@syncro.dev",
      applicationRole: "VIEWER",
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    expect(screen.getByText(/read-only/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /create machine group/i })).toBeNull();
  });

  // -------------------------------------------------------------------------
  // 5. Forbidden / empty-scope state
  // -------------------------------------------------------------------------
  it("shows no-plant-assignment state when scope mode is EMPTY", () => {
    mockPlantScope = {
      activePlantId: null,
      scope: { mode: "EMPTY" },
      loadError: false,
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    expect(screen.getByRole("heading", { name: /no plant assignment/i })).toBeTruthy();
    expect(screen.getByText(/your account has no assigned plant scope/i)).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // 6. Validation state
  // -------------------------------------------------------------------------
  it("shows field error after create mutateAsync rejects with a validation SyncroApiError", async () => {
    const validationError = new SyncroApiError(422, {
      code: "VALIDATION_ERROR",
      message: "Name is required",
      fieldErrors: { name: "Name is required" },
    });
    mockCreateMachineGroup = {
      mutateAsync: vi.fn().mockRejectedValue(validationError),
      mutate: vi.fn(),
      isPending: false,
    };

    const { baseElement } = render(<MachineGroupManagement />, { wrapper: Wrapper });

    // Open the create dialog
    const createButton = screen.getByRole("button", { name: /create machine group/i });
    fireEvent.click(createButton);

    // Dialog title must be visible
    expect(await screen.findByRole("heading", { name: /create machine group/i })).toBeTruthy();

    // Submit the form — plantId is pre-filled from effectivePlantId = "plant-1"
    const form = baseElement.querySelector("form");
    expect(form).not.toBeNull();
    fireEvent.submit(form!);

    // Field error from the rejected SyncroApiError must appear.
    // The component renders the message twice: once as a form-level banner
    // and once as a per-field inline error, so use getAllByText.
    await waitFor(() => {
      expect(screen.getAllByText("Name is required").length).toBeGreaterThanOrEqual(1);
    });
  });
});
