import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { SyncroApiError } from "@/lib/api/orval-mutator";

import { MachineHubPageContent } from "./machine-hub-page-content";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

import { toast } from "sonner";

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

let mockMachineQuery = {
  data: {
    data: {
      id: "m-1",
      code: "BF-08410",
      name: "Forming 1",
      status: "ACTIVE",
      plantName: "Plant 1",
      machineGroupName: "Forming",
    },
  },
  isLoading: false,
  isFetching: false,
  isError: false,
  refetch: vi.fn(),
};

// Hub tabs other than the Shift and projection sections are out of scope here.
vi.mock("./alerts-tab", () => ({ AlertsTab: () => <div data-testid="alerts-stub" /> }));
vi.mock("./audit-log-tab", () => ({ AuditLogTab: () => <div data-testid="audit-stub" /> }));
vi.mock("./machine-header", () => ({ MachineHeader: () => <div data-testid="header-stub" /> }));
vi.mock("./overview-tab", () => ({ OverviewTab: () => <div data-testid="overview-stub" /> }));
vi.mock("./spareparts-tab", () => ({ SparepartsTab: () => <div data-testid="spareparts-stub" /> }));
vi.mock("./telemetry-tab", () => ({ TelemetryTab: () => <div data-testid="telemetry-stub" /> }));
vi.mock("@/components/syncro/counter-rate-projection-card", () => ({
  CounterRateProjectionCard: ({ machineId }: { machineId?: string | null }) => (
    <div data-testid="projection-card-stub" data-machine-id={machineId ?? ""} />
  ),
}));

let mockShiftConfig: {
  data: {
    data: {
      source: string;
      inheritedFromGroup: boolean;
      shifts: { shiftNumber: number; startTime?: string; endTime?: string }[];
    };
  } | null;
  isLoading: boolean;
  isError: boolean;
  status: string;
} = {
  data: {
    data: {
      source: "NONE" as string,
      inheritedFromGroup: false,
      shifts: [] as { shiftNumber: number; startTime?: string; endTime?: string }[],
    },
  },
  isLoading: false,
  isError: false,
  status: "success",
};

let mockUpdateMachineShiftConfig = {
  mutateAsync: vi.fn(),
  mutate: vi.fn(),
  isPending: false,
};

let mockDeleteMachineShiftConfig = {
  mutateAsync: vi.fn(),
  mutate: vi.fn(),
  isPending: false,
};

vi.mock("@/lib/api/generated/syncro", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/generated/syncro")>();
  return {
    ...original,
    getGetMachineShiftConfigQueryKey: vi.fn(() => ["/mock-machine-shift-key"]),
    useGetMachineByCode: vi.fn(() => mockMachineQuery),
    useGetMachine: vi.fn(() => mockMachineQuery),
    useGetMachineShiftConfig: vi.fn(() => mockShiftConfig),
    useUpdateMachineShiftConfig: vi.fn(() => mockUpdateMachineShiftConfig),
    useDeleteMachineShiftConfig: vi.fn(() => mockDeleteMachineShiftConfig),
  };
});

// ---------------------------------------------------------------------------
// Test wrapper
// ---------------------------------------------------------------------------

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

const Wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
);

function renderHub() {
  return render(<MachineHubPageContent machineCode="BF-08410" />, { wrapper: Wrapper });
}

function renderHubById() {
  return render(<MachineHubPageContent machineId="m-1" />, { wrapper: Wrapper });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("MachineHubPageContent shift section (Story 8-5)", () => {
  beforeEach(() => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "admin@syncro.dev",
      applicationRole: "SUPER_ADMIN",
    };
    mockMachineQuery = {
      data: {
        data: {
          id: "m-1",
          code: "BF-08410",
          name: "Forming 1",
          status: "ACTIVE",
          plantName: "Plant 1",
          machineGroupName: "Forming",
        },
      },
      isLoading: false,
      isFetching: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockShiftConfig = {
      data: { data: { source: "NONE", inheritedFromGroup: false, shifts: [] } },
      isLoading: false,
      isError: false,
      status: "success",
    };
    mockUpdateMachineShiftConfig = {
      mutateAsync: vi.fn(),
      mutate: vi.fn(),
      isPending: false,
    };
    mockDeleteMachineShiftConfig = {
      mutateAsync: vi.fn(),
      mutate: vi.fn(),
      isPending: false,
    };
    queryClient.clear();
  });

  it("shows the inherited-from-group badge when source is MACHINE_GROUP", async () => {
    mockShiftConfig = {
      data: {
        data: {
          source: "MACHINE_GROUP",
          inheritedFromGroup: true,
          shifts: [
            { shiftNumber: 1, startTime: "07:00", endTime: "15:00" },
            { shiftNumber: 2, startTime: "23:00", endTime: "06:00" },
          ],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };

    renderHub();

    expect(await screen.findByText("Inherited from group")).toBeTruthy();
    const startInput = screen.getByLabelText("Shift 1 start") as HTMLInputElement;
    expect(startInput.value).toBe("07:00");
    expect(screen.getByLabelText("Shift 2 end")).toHaveValue("06:00");
  });

  it("shows no badge when the machine has its own MACHINE override with clear button", async () => {
    mockShiftConfig = {
      data: {
        data: {
          source: "MACHINE",
          inheritedFromGroup: false,
          shifts: [{ shiftNumber: 1, startTime: "09:00", endTime: "17:00" }],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };

    renderHub();

    expect(await screen.findByLabelText("Shift 1 start")).toBeTruthy();
    expect(screen.queryByText("Inherited from group")).toBeNull();
    expect(screen.queryByText(/no shift schedule configured/i)).toBeNull();
    expect(screen.getByRole("button", { name: /save shift override/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: /clear shift override/i })).toBeTruthy();
  });

  it("resolves by machineId when provided (DW-71)", async () => {
    mockShiftConfig = { data: null, isLoading: false, isError: false, status: "success" };
    renderHubById();
    expect(await screen.findByTestId("header-stub")).toBeTruthy();
  });

  it("shows an empty state when neither machine nor group define shifts", async () => {
    renderHub();

    expect(await screen.findByText(/no shift schedule configured for this machine or its group/i)).toBeTruthy();
    expect(screen.queryByText("Inherited from group")).toBeNull();
  });

  it("PUTs the loaded windows once on Save shift override", async () => {
    mockShiftConfig = {
      data: {
        data: {
          source: "MACHINE_GROUP",
          inheritedFromGroup: true,
          shifts: [{ shiftNumber: 1, startTime: "07:00", endTime: "15:00" }],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };
    mockUser = {
      id: "user-1",
      loginIdentifier: "leader@syncro.dev",
      applicationRole: "MANAGER_MAINTENANCE",
    };
    mockUpdateMachineShiftConfig.mutateAsync.mockResolvedValue({
      data: { source: "MACHINE", inheritedFromGroup: false, shifts: [] },
    });

    renderHub();

    fireEvent.click(await screen.findByRole("button", { name: /save shift override/i }));

    await waitFor(() => {
      expect(mockUpdateMachineShiftConfig.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockUpdateMachineShiftConfig.mutateAsync).toHaveBeenCalledWith({
      machineId: "m-1",
      data: { shifts: [{ startTime: "07:00", endTime: "15:00" }] },
    });
    expect(mockDeleteMachineShiftConfig.mutateAsync).not.toHaveBeenCalled();
  });

  it("DELETEs the override once on Clear shift override", async () => {
    mockShiftConfig = {
      data: {
        data: {
          source: "MACHINE",
          inheritedFromGroup: false,
          shifts: [{ shiftNumber: 1, startTime: "09:00", endTime: "17:00" }],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };
    mockDeleteMachineShiftConfig.mutateAsync.mockResolvedValue({ data: undefined });

    renderHub();

    fireEvent.click(await screen.findByRole("button", { name: /clear shift override/i }));

    await waitFor(() => {
      expect(mockDeleteMachineShiftConfig.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockDeleteMachineShiftConfig.mutateAsync).toHaveBeenCalledWith({ machineId: "m-1" });
    expect(mockUpdateMachineShiftConfig.mutateAsync).not.toHaveBeenCalled();
  });

  it("surfaces verbatim denial when the override PUT is rejected", async () => {
    const denialMessage = "This action requires job scope LEADER or above.";
    mockUpdateMachineShiftConfig = {
      mutateAsync: vi.fn().mockRejectedValue(
        new SyncroApiError(403, {
          code: "JOB_SCOPE_REQUIRED",
          message: denialMessage,
        }),
      ),
      mutate: vi.fn(),
      isPending: false,
    };

    renderHub();

    fireEvent.click(await screen.findByRole("button", { name: /save shift override/i }));

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith(denialMessage);
    });
    expect(screen.getByRole("alert")).toHaveTextContent(denialMessage);
  });

  it("renders a read-only editor without action buttons for AUDITOR role", async () => {
    mockUser = {
      id: "user-1",
      loginIdentifier: "viewer@syncro.dev",
      applicationRole: "AUDITOR",
    };
    mockShiftConfig = {
      data: {
        data: {
          source: "MACHINE_GROUP",
          inheritedFromGroup: true,
          shifts: [{ shiftNumber: 1, startTime: "07:00", endTime: "15:00" }],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };

    renderHub();

    expect(await screen.findByText("Inherited from group")).toBeTruthy();
    expect(screen.getByLabelText("Shift 1 start")).toHaveAttribute("disabled");
    expect(screen.queryByRole("button", { name: /save shift override/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /clear shift override/i })).toBeNull();
    expect(screen.getByText(/requires job scope leader or above/i)).toBeTruthy();
  });

  it("renders the counter-rate projection card keyed to the machine id below the shift section (Story 8-6)", async () => {
    mockShiftConfig = {
      data: {
        data: {
          source: "MACHINE",
          inheritedFromGroup: false,
          shifts: [{ shiftNumber: 1, startTime: "07:00", endTime: "15:00" }],
        },
      },
      isLoading: false,
      isError: false,
      status: "success",
    };

    renderHub();

    const projectionCard = await screen.findByTestId("projection-card-stub");
    expect(projectionCard.getAttribute("data-machine-id")).toBe("m-1");

    const shiftCard = screen.getByLabelText("Shift 1 start").closest("[data-slot='card']");
    // A missing card must fail loudly here, not silently skip the ordering assertion below.
    expect(shiftCard).not.toBeNull();
    if (shiftCard) {
      expect(shiftCard.compareDocumentPosition(projectionCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }
  });
});
