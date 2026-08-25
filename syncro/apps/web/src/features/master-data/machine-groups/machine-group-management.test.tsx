import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SyncroApiError } from "@/lib/api/orval-mutator";

import { MachineGroupManagement } from "./machine-group-management";

// ---------------------------------------------------------------------------
// Module-level mocks (hoisted by Vitest — cannot reference outer variables)
// ---------------------------------------------------------------------------

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

import { toast } from "sonner";

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

let mockGetGroupShiftConfig = {
  data: {
    data: {
      shifts: [{ shiftNumber: 1, startTime: "07:00", endTime: "15:00" }] as {
        shiftNumber: number;
        startTime?: string;
        endTime?: string;
      }[],
    },
  },
  isLoading: false,
  isError: false,
  status: "success",
};

let mockUpdateGroupShiftConfig = {
  mutateAsync: vi.fn(),
  mutate: vi.fn(),
  isPending: false,
};

vi.mock("@/lib/api/generated/syncro", () => ({
  useListPlants: vi.fn(() => mockListPlants),
  useListMachineGroups: vi.fn(() => mockListMachineGroups),
  useListSections: vi.fn(() => ({ data: { data: { items: [] } }, isLoading: false, isError: false, refetch: vi.fn() })),
  useCreateMachineGroup: vi.fn(() => mockCreateMachineGroup),
  useUpdateMachineGroup: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useDeleteMachineGroup: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useAssignMachineGroupSection: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useClearMachineGroupSection: vi.fn(() => ({ mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false })),
  useGetMachineGroupShiftConfig: vi.fn(() => mockGetGroupShiftConfig),
  useUpdateMachineGroupShiftConfig: vi.fn(() => mockUpdateGroupShiftConfig),
  getGetMachineGroupShiftConfigQueryKey: vi.fn(() => ["/mock-group-shift-key"]),
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
    mockGetGroupShiftConfig = {
      data: {
        data: {
          shifts: [{ shiftNumber: 1, startTime: "07:00", endTime: "15:00" }],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };
    mockUpdateGroupShiftConfig = {
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

    expect(screen.getByRole("heading", { name: /machine groups could not be loaded/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: /retry/i })).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // 4. Read-only state (AUDITOR role)
  // -------------------------------------------------------------------------
  it("shows read-only badge instead of create button for AUDITOR role", () => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "viewer@syncro.dev",
      applicationRole: "AUDITOR",
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

  // -------------------------------------------------------------------------
  // 7. Error-state Retry actually refetches both lists (DW-2 hardening)
  // -------------------------------------------------------------------------
  it("retry button refetches machine groups and plants", async () => {
    mockListMachineGroups = {
      ...mockListMachineGroups,
      isError: true,
      isLoading: false,
      data: { data: { items: [], totalElements: 0 } },
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /retry/i }));
    await waitFor(() => {
      expect(mockListMachineGroups.refetch).toHaveBeenCalled();
      expect(mockListPlants.refetch).toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // 8. Read-only rows expose per-row "View only" badge (DW-2 hardening)
  // -------------------------------------------------------------------------
  it("read-only role renders View only badges on populated rows", () => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "viewer@syncro.dev",
      applicationRole: "AUDITOR",
    };
    mockListMachineGroups = {
      ...mockListMachineGroups,
      data: {
        data: {
          items: [
            {
              id: "g-1",
              name: "Forming",
              plantId: "plant-1",
              plantCode: "P1",
              plantName: "Plant 1",
              createdAt: "2026-08-01T00:00:00Z",
            },
          ],
          totalElements: 1,
        },
      },
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    expect(screen.getByText("View only")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /edit/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /delete/i })).toBeNull();
  });

  // -------------------------------------------------------------------------
  // 9. Shift Configuration section hidden in create dialog (Story 8-5)
  // -------------------------------------------------------------------------
  it("hides the Shift configuration section in the create dialog", async () => {
    render(<MachineGroupManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /create machine group/i }));

    expect(await screen.findByRole("heading", { name: /create machine group/i })).toBeTruthy();
    expect(screen.queryByText("Shift configuration")).toBeNull();
    expect(screen.queryByLabelText("Shift 1 start")).toBeNull();
  });

  // -------------------------------------------------------------------------
  // 10. Edit dialog shows shift section with fetched windows and PUTs once (8-5)
  // -------------------------------------------------------------------------
  it("edit dialog loads group shifts and PUTs them once on Save shift schedule", async () => {
    mockListMachineGroups = {
      ...mockListMachineGroups,
      data: {
        data: {
          items: [
            {
              id: "g-1",
              name: "Forming",
              plantId: "plant-1",
              plantCode: "P1",
              plantName: "Plant 1",
              createdAt: "2026-08-01T00:00:00Z",
            },
          ],
          totalElements: 1,
        },
      },
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    expect(await screen.findByText("Shift configuration")).toBeTruthy();

    const startInput = screen.getByLabelText("Shift 1 start") as HTMLInputElement;
    expect(startInput.value).toBe("07:00");
    expect(screen.getByText(/requires job scope leader or above/i)).toBeTruthy();

    mockUpdateGroupShiftConfig.mutateAsync.mockResolvedValue({
      data: { shifts: [{ shiftNumber: 1, startTime: "07:00", endTime: "15:00" }] },
    });
    fireEvent.click(screen.getByRole("button", { name: /save shift schedule/i }));

    await waitFor(() => {
      expect(mockUpdateGroupShiftConfig.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockUpdateGroupShiftConfig.mutateAsync).toHaveBeenCalledWith({
      machineGroupId: "g-1",
      data: { shifts: [{ startTime: "07:00", endTime: "15:00" }] },
    });
  });

  // -------------------------------------------------------------------------
  // 11. AUDITOR never reaches the shift editor from the groups table (8-5)
  // -------------------------------------------------------------------------
  it("gives AUDITOR no edit entry point so the shift editor stays unreachable", () => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "viewer@syncro.dev",
      applicationRole: "AUDITOR",
    };
    mockListMachineGroups = {
      ...mockListMachineGroups,
      data: {
        data: {
          items: [
            {
              id: "g-1",
              name: "Forming",
              plantId: "plant-1",
              plantCode: "P1",
              plantName: "Plant 1",
              createdAt: "2026-08-01T00:00:00Z",
            },
          ],
          totalElements: 1,
        },
      },
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    expect(screen.getByText("View only")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^edit$/i })).toBeNull();
    expect(screen.queryByText("Shift configuration")).toBeNull();
    expect(screen.queryByLabelText("Shift 1 start")).toBeNull();
  });

  // -------------------------------------------------------------------------
  // 12. Shift schedule denial surfaces verbatim message (8-5)
  // -------------------------------------------------------------------------
  it("surfaces verbatim denial when shift schedule PUT is rejected", async () => {
    mockListMachineGroups = {
      ...mockListMachineGroups,
      data: {
        data: {
          items: [
            {
              id: "g-1",
              name: "Forming",
              plantId: "plant-1",
              plantCode: "P1",
              plantName: "Plant 1",
              createdAt: "2026-08-01T00:00:00Z",
            },
          ],
          totalElements: 1,
        },
      },
    };
    const denialMessage = "This action requires job scope LEADER or above.";
    mockUpdateGroupShiftConfig = {
      mutateAsync: vi.fn().mockRejectedValue(
        new SyncroApiError(403, {
          code: "JOB_SCOPE_REQUIRED",
          message: denialMessage,
        }),
      ),
      mutate: vi.fn(),
      isPending: false,
    };

    render(<MachineGroupManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    expect(await screen.findByText("Shift configuration")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /save shift schedule/i }));

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(denialMessage);
    });
    expect(screen.getByRole("alert")).toHaveTextContent(denialMessage);
  });
});
