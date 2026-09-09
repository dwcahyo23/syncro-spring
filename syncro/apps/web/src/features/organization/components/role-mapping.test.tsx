import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { I18nProvider } from "@/test/i18n-wrapper";

import { RoleMapping } from "./role-mapping";

// ---------------------------------------------------------------------------
// Module-level mocks (hoisted by Vitest)
// ---------------------------------------------------------------------------

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

const mockUser = {
  id: "user-1",
  loginIdentifier: "admin@syncro.dev",
  applicationRole: "SUPER_ADMIN" as string,
};

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => mockUser,
}));

// Pre-existing tsc fix (story 20-2): the loading-state test below assigns
// `data: undefined`, so the mock's data field must be typed as optional.
let mockUsers: {
  data:
    | {
        data: Array<{ id: string; loginIdentifier: string; displayName: string; applicationRole: string }>;
      }
    | undefined;
  isLoading: boolean;
  isError: boolean;
  refetch: ReturnType<typeof vi.fn>;
} = {
  data: {
    data: [{ id: "u1", loginIdentifier: "tech@syncro.dev", displayName: "Technician", applicationRole: "TECHNICIAN" }],
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

const mockJobTitles = {
  data: { data: { items: [{ id: "jt1", code: "TECHNICIAN", name: "Technician" }] } },
  isLoading: false,
  isError: false,
};

const mockSystemRoles = {
  data: { data: { items: [{ id: "sr1", code: "TECHNICIAN", name: "Technician" }] } },
  isLoading: false,
  isError: false,
};

const mockBindings = {
  data: {
    data: {
      job: { id: "jb1", jobTitleId: "jt1" },
      roles: [{ id: "rb1", systemRoleId: "sr1", override: false }],
    },
  },
  isLoading: false,
  isError: false,
};

const mockSetJob = { mutateAsync: vi.fn(), isPending: false };
const mockAddRole = { mutateAsync: vi.fn(), isPending: false };
const mockRemoveRole = { mutateAsync: vi.fn(), isPending: false };

vi.mock("@/features/organization/hooks/use-users", () => ({
  useListUsersMaster: () => mockUsers,
  useListJobTitles: () => mockJobTitles,
}));

vi.mock("@/features/organization/hooks/use-user-bindings", () => ({
  useGetUserBindings: () => mockBindings,
  useSetUserJob: () => mockSetJob,
  useAddUserRole: () => mockAddRole,
  useRemoveUserRole: () => mockRemoveRole,
  useListSystemRoles: () => mockSystemRoles,
}));

function renderWithClient(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nProvider>{ui}</I18nProvider>
    </QueryClientProvider>,
  );
}

describe("RoleMapping", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUsers = {
      data: {
        data: [
          { id: "u1", loginIdentifier: "tech@syncro.dev", displayName: "Technician", applicationRole: "TECHNICIAN" },
        ],
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
  });

  it("renders the role mapping table with users", () => {
    renderWithClient(<RoleMapping />);

    expect(screen.getByText("Role Mapping")).toBeDefined();
    expect(screen.getAllByText("Technician").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole("button", { name: "Bind roles" })).toBeDefined();
  });

  it("renders the binding panel when a user is selected", () => {
    renderWithClient(<RoleMapping />);

    const bindButton = screen.getByRole("button", { name: "Bind roles" });
    // Click via fireEvent to select the user
    const { fireEvent } = require("@testing-library/react");
    fireEvent.click(bindButton);

    expect(screen.getByText(/Binding:/)).toBeDefined();
    expect(screen.getByText("Job Title")).toBeDefined();
    expect(screen.getByText("System Roles")).toBeDefined();
  });

  it("shows loading state without crashing when users are loading", () => {
    mockUsers = { ...mockUsers, isLoading: true, data: undefined };
    renderWithClient(<RoleMapping />);

    expect(screen.getByText("Role Mapping")).toBeDefined();
    expect(screen.queryByRole("button", { name: "Bind roles" })).toBeNull();
    mockUsers = { ...mockUsers, isLoading: false };
  });
});
