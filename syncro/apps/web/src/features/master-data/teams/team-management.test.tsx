import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";
import { SyncroApiError } from "@/lib/api/orval-mutator";

import { TeamManagement } from "./team-management";

async function selectFromCombobox(comboboxName: string, optionText: string) {
  const trigger = screen.getByRole("combobox", { name: new RegExp(comboboxName, "i") });
  fireEvent.pointerDown(trigger);
  fireEvent.pointerUp(trigger);
  fireEvent.click(trigger);
  const option = await screen.findByRole("option", { name: new RegExp(optionText) });
  fireEvent.click(option);
}

let mockListTeams = {
  data: { data: { items: [] as Array<Record<string, unknown>> } },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

let mockCreateTeam = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockUpdateTeam = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockDeleteTeam = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockGetTeam: {
  data: { data: Record<string, unknown> };
  isLoading: boolean;
  isError: boolean;
  refetch: ReturnType<typeof vi.fn>;
} = {
  data: {
    data: {
      id: "t-1",
      name: "Cross Repair",
      expiresAt: "2026-09-30T00:00:00Z",
      active: true,
      members: [],
      machines: [],
    },
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};
let mockAddTeamMember = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockRemoveTeamMember = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockLinkTeamMachine = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockUnlinkTeamMachine = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };

vi.mock("@/lib/api/generated/syncro", () => ({
  getListTeamsQueryKey: vi.fn(() => ["/mock-teams-key"]),
  useListTeams: vi.fn(() => mockListTeams),
  useCreateTeam: vi.fn(() => mockCreateTeam),
  useUpdateTeam: vi.fn(() => mockUpdateTeam),
  useDeleteTeam: vi.fn(() => mockDeleteTeam),
  useGetTeam: vi.fn(() => mockGetTeam),
  useListUsers: vi.fn(() => ({
    data: {
      data: [
        { id: "u-1", loginIdentifier: "technician@syncro.dev", applicationRole: "VIEWER" },
        { id: "u-2", loginIdentifier: "manager@syncro.dev", applicationRole: "MANAGE" },
      ],
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })),
  useListMachines: vi.fn(() => ({
    data: {
      data: {
        items: [
          {
            id: "m-1",
            code: "PK-0001",
            name: "PackLine",
            plantId: "p-2",
            plantCode: "SM2",
            machineGroupId: "g-2",
            machineGroupName: "Packaging",
          },
          {
            id: "m-2",
            code: "BF-08410",
            name: "JBF19",
            plantId: "p-1",
            plantCode: "GM1",
            machineGroupId: "g-1",
            machineGroupName: "Forming",
          },
        ],
      },
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })),
  useListPlants: vi.fn(() => ({
    data: {
      data: {
        items: [
          { id: "p-1", code: "GM1", name: "Plant GM1", createdAt: "", updatedAt: "" },
          { id: "p-2", code: "SM2", name: "Sinar Mas 2", createdAt: "", updatedAt: "" },
        ],
      },
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })),
  useAddTeamMember: vi.fn(() => mockAddTeamMember),
  useRemoveTeamMember: vi.fn(() => mockRemoveTeamMember),
  useLinkTeamMachine: vi.fn(() => mockLinkTeamMachine),
  useUnlinkTeamMachine: vi.fn(() => mockUnlinkTeamMachine),
}));

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockAuthUser,
}));

let mockAuthUser: { applicationRole: string } = { applicationRole: "SUPER_ADMIN" };

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient();
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{children}</TooltipProvider>
    </QueryClientProvider>
  );
}

describe("TeamManagement", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuthUser = { applicationRole: "SUPER_ADMIN" };
    mockListTeams = {
      data: { data: { items: [] } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockCreateTeam = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockUpdateTeam = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockDeleteTeam = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockGetTeam = {
      data: {
        data: {
          id: "t-1",
          name: "Cross Repair",
          expiresAt: "2026-09-30T00:00:00Z",
          active: true,
          members: [],
          machines: [],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockAddTeamMember = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockRemoveTeamMember = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockLinkTeamMachine = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockUnlinkTeamMachine = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
  });

  it("shows empty state when no teams exist", () => {
    render(<TeamManagement />, { wrapper: Wrapper });
    expect(screen.getByText("No teams yet")).toBeInTheDocument();
  });

  it("renders teams with status badge and member/machine counts for SUPER_ADMIN", () => {
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Cross Repair",
              expiresAt: "2026-09-30T00:00:00Z",
              active: true,
              memberCount: 2,
              machineCount: 1,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    expect(screen.getByText("Cross Repair")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /members/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /machines/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /edit/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /delete/i })).toBeInTheDocument();
  });

  it("shows an Expired badge for a past-expiry team", () => {
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Old Help",
              expiresAt: "2026-01-01T00:00:00Z",
              active: false,
              memberCount: 0,
              machineCount: 0,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    expect(screen.getByText("Expired")).toBeInTheDocument();
  });

  it("creates a team through the dialog", async () => {
    render(<TeamManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /create team/i }));
    expect(screen.getByRole("heading", { name: /create team/i })).toBeInTheDocument();

    await fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Cross Repair" } });
    await fireEvent.change(screen.getByLabelText("Expires at"), {
      target: { value: "2026-09-30T10:00" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save team/i }));

    await waitFor(() => {
      expect(mockCreateTeam.mutateAsync).toHaveBeenCalledOnce();
    });
  });

  it("edits a team through the dialog (prefilled form)", async () => {
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Editable Team",
              expiresAt: "2026-09-30T10:00:07Z",
              active: true,
              memberCount: 0,
              machineCount: 0,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /edit/i }));
    expect(screen.getByRole("heading", { name: /edit team/i })).toBeInTheDocument();

    await fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Renamed Team" } });
    fireEvent.click(screen.getByRole("button", { name: /save team/i }));

    await waitFor(() => {
      expect(mockUpdateTeam.mutateAsync).toHaveBeenCalledOnce();
    });
    // Second precision survives the datetime-local round-trip.
    const payload = mockUpdateTeam.mutateAsync.mock.calls[0][0];
    expect(payload.data.name).toBe("Renamed Team");
    expect(new Date(payload.data.expiresAt).toISOString()).toBe("2026-09-30T10:00:07.000Z");
  });

  it("renders a read-only table without mutation actions for VIEWER", () => {
    mockAuthUser = { applicationRole: "VIEWER" };
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Viewer Team",
              expiresAt: "2026-09-30T00:00:00Z",
              active: true,
              memberCount: 1,
              machineCount: 1,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    expect(screen.getByText("Read-only")).toBeInTheDocument();
    expect(screen.getByText("View only")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /create team/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /members/i })).not.toBeInTheDocument();
  });

  it("surfaces the expiry error on create", async () => {
    mockCreateTeam = {
      mutateAsync: vi.fn().mockRejectedValue(
        new SyncroApiError(400, {
          code: "TEAM_EXPIRY_IN_PAST",
          message: "Team expiry must be strictly in the future.",
        }),
      ),
      mutate: vi.fn(),
      isPending: false,
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /create team/i }));
    await fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Past Team" } });
    await fireEvent.change(screen.getByLabelText("Expires at"), {
      target: { value: "2026-01-01T10:00" },
    });
    fireEvent.click(screen.getByRole("button", { name: /save team/i }));

    await waitFor(() => {
      expect(screen.getByText("Team expiry must be strictly in the future.")).toBeInTheDocument();
    });
  });

  it("deletes a team through the confirm dialog", async () => {
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Delete Me",
              expiresAt: "2026-09-30T00:00:00Z",
              active: true,
              memberCount: 0,
              machineCount: 0,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /delete/i }));
    expect(screen.getByText(/Delete team/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));

    await waitFor(() => {
      expect(mockDeleteTeam.mutateAsync).toHaveBeenCalledOnce();
    });
  });

  it("adds and removes a member through the members dialog", async () => {
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Member Team",
              expiresAt: "2026-09-30T00:00:00Z",
              active: true,
              memberCount: 1,
              machineCount: 0,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockGetTeam = {
      data: {
        data: {
          id: "t-1",
          name: "Member Team",
          expiresAt: "2026-09-30T00:00:00Z",
          active: true,
          members: [{ userId: "u-1", loginIdentifier: "technician@syncro.dev" }],
          machines: [],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /members/i }));
    expect(screen.getByText("technician@syncro.dev")).toBeInTheDocument();

    await selectFromCombobox("add member", "manager@syncro.dev");
    fireEvent.click(screen.getByRole("button", { name: /^add$/i }));
    await waitFor(() => {
      expect(mockAddTeamMember.mutateAsync).toHaveBeenCalledOnce();
    });

    fireEvent.click(screen.getByRole("button", { name: /^remove$/i }));
    await waitFor(() => {
      expect(mockRemoveTeamMember.mutateAsync).toHaveBeenCalledOnce();
    });
  });

  it("links and unlinks a machine through the machines dialog", async () => {
    mockListTeams = {
      data: {
        data: {
          items: [
            {
              id: "t-1",
              name: "Machine Team",
              expiresAt: "2026-09-30T00:00:00Z",
              active: true,
              memberCount: 0,
              machineCount: 1,
              createdAt: "2026-08-25T00:00:00Z",
              updatedAt: "2026-08-25T00:00:00Z",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockGetTeam = {
      data: {
        data: {
          id: "t-1",
          name: "Machine Team",
          expiresAt: "2026-09-30T00:00:00Z",
          active: true,
          members: [],
          machines: [
            {
              machineId: "m-1",
              code: "PK-0001",
              name: "PackLine",
              plantId: "p-2",
              plantCode: "SM2",
              machineGroupId: "g-2",
              machineGroupName: "Packaging",
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<TeamManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /machines/i }));
    expect(screen.getByText("PK-0001 — PackLine")).toBeInTheDocument();

    await selectFromCombobox("link machine", "BF-08410");
    fireEvent.click(screen.getByRole("button", { name: /^link$/i }));
    await waitFor(() => {
      expect(mockLinkTeamMachine.mutateAsync).toHaveBeenCalledOnce();
    });

    fireEvent.click(screen.getByRole("button", { name: /^unlink$/i }));
    await waitFor(() => {
      expect(mockUnlinkTeamMachine.mutateAsync).toHaveBeenCalledOnce();
    });
  });
});
