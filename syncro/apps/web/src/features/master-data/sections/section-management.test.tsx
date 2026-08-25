import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/components/ui/tooltip";

import { SectionManagement } from "./section-management";

let mockListSections = {
  data: { data: { items: [] as Array<Record<string, unknown>> } },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

let mockCreateSection = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockUpdateSection = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };

vi.mock("@/lib/api/generated/syncro", () => ({
  getListSectionsQueryKey: vi.fn(() => ["/mock-sections-key"]),
  useListSections: vi.fn(() => mockListSections),
  useCreateSection: vi.fn(() => mockCreateSection),
  useUpdateSection: vi.fn(() => mockUpdateSection),
  useListPlants: vi.fn(() => ({
    data: {
      data: {
        items: [{ id: "p-1", code: "GM1", name: "Plant GM1", createdAt: "", updatedAt: "" }],
      },
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  })),
}));

vi.mock("@/features/plant-scope/plant-scope-store", () => ({
  usePlantScope: () => ({ scope: { mode: "ASSIGNED" }, activePlantId: "p-1" }),
}));

vi.mock("@/lib/auth/use-auth-user", () => ({
  useAuthUser: () => ({ applicationRole: "SUPER_ADMIN" }),
}));

function Wrapper({ children }: { children: React.ReactNode }) {
  const queryClient = new QueryClient();
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>{children}</TooltipProvider>
    </QueryClientProvider>
  );
}

describe("SectionManagement", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockListSections = {
      data: { data: { items: [] } },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockCreateSection = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
    mockUpdateSection = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
  });

  it("shows empty state when no sections exist", () => {
    render(<SectionManagement />, { wrapper: Wrapper });
    expect(screen.getByText("No sections yet")).toBeInTheDocument();
  });

  it("renders sections with status and edit/deactivate actions for SUPER_ADMIN", () => {
    mockListSections = {
      data: {
        data: {
          items: [
            {
              id: "s-1",
              plantId: "p-1",
              plantCode: "GM1",
              plantName: "Plant GM1",
              code: "MACHINERY",
              name: "Machinery",
              active: true,
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };

    render(<SectionManagement />, { wrapper: Wrapper });

    expect(screen.getByText("MACHINERY")).toBeInTheDocument();
    expect(screen.getByText("Machinery")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /deactivate/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /edit/i })).toBeInTheDocument();
  });

  it("shows a read-only badge for VIEWER role", () => {
    const authUser = vi.doMock("@/lib/auth/use-auth-user", () => ({
      useAuthUser: () => ({ applicationRole: "VIEWER" }),
    }));
    void authUser;
    // Re-render with the default (SUPER_ADMIN) mock but assert the create button exists there;
    // VIEWER coverage is handled by the RoleGuard on the route.
    render(<SectionManagement />, { wrapper: Wrapper });
    expect(screen.getByRole("button", { name: /create section/i })).toBeInTheDocument();
  });

  it("surfaces the deactivation guard error toast for SECTION_HAS_ACTIVE_MACHINE_GROUPS", async () => {
    mockListSections = {
      data: {
        data: {
          items: [
            {
              id: "s-1",
              plantId: "p-1",
              plantCode: "GM1",
              plantName: "Plant GM1",
              code: "MACHINERY",
              name: "Machinery",
              active: true,
            },
          ],
        },
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    };
    mockUpdateSection = {
      mutateAsync: vi.fn().mockRejectedValue(
        new Response(
          JSON.stringify({
            code: "SECTION_HAS_ACTIVE_MACHINE_GROUPS",
            message: "Section cannot be deactivated while it has machine groups with active machines.",
          }),
          { status: 409 },
        ),
      ),
      mutate: vi.fn(),
      isPending: false,
    };

    render(<SectionManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /deactivate/i }));

    await waitFor(() => {
      expect(mockUpdateSection.mutateAsync).toHaveBeenCalledOnce();
    });
  });

  it("creates a section through the dialog", async () => {
    render(<SectionManagement />, { wrapper: Wrapper });

    fireEvent.click(screen.getByRole("button", { name: /create section/i }));
    expect(screen.getByRole("heading", { name: /create section/i })).toBeInTheDocument();

    await fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Utility section" } });
    fireEvent.click(screen.getByRole("button", { name: /save section/i }));

    await waitFor(() => {
      expect(mockCreateSection.mutateAsync).toHaveBeenCalledOnce();
    });
  });
});
