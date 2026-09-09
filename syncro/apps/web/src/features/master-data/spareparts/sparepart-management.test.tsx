import type { ReactNode } from "react";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { I18nProvider } from "@/test/i18n-wrapper";

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

const mockPriceEntries = {
  data: {
    data: [
      {
        id: "entry-1",
        sparepartId: "sp-1",
        amount: 1000,
        currency: "USD",
        kursToIdr: 15500,
        idrAmount: 15500000,
        enteredBy: "user-9",
        enteredByName: "writer@syncro.dev",
        enteredAt: "2026-08-01T10:15:00Z",
      },
    ],
  },
  isLoading: false,
  isError: false,
  refetch: vi.fn(),
};

let mockCreatePriceEntry = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };

type MockImageQuery = {
  data: { data: { sparepartId: string; objectKey?: string; presignedUrl?: string } };
  isLoading: boolean;
  isError: boolean;
  error?: unknown;
  refetch: ReturnType<typeof vi.fn>;
};

let mockImageQuery: MockImageQuery = {
  data: { data: { sparepartId: "sp-1", objectKey: "spareparts/sp-1/a.png", presignedUrl: "https://presigned/a.png" } },
  isLoading: false,
  isError: false,
  error: undefined,
  refetch: vi.fn(),
};

let mockCreateImage = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };
let mockDeleteImage = { mutateAsync: vi.fn(), mutate: vi.fn(), isPending: false };

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
  useListSparepartPriceEntries: vi.fn(() => mockPriceEntries),
  useCreateSparepartPriceEntries: vi.fn(() => mockCreatePriceEntry),
  useGetSparepartImage: vi.fn(() => mockImageQuery),
  useCreateSparepartImage: vi.fn(() => mockCreateImage),
  useDeleteSparepartImage: vi.fn(() => mockDeleteImage),
  getListSparepartsQueryKey: vi.fn(() => ["/mock-spareparts-key"]),
  getListSparepartTaxonomiesQueryKey: vi.fn(() => ["/mock-taxonomies-key"]),
  getListSparepartPriceEntriesQueryKey: vi.fn((sparepartId: string) => ["mock-price-entries", sparepartId]),
  getGetSparepartImageQueryKey: vi.fn((sparepartId: string) => ["mock-sparepart-image", sparepartId]),
}));

// ---------------------------------------------------------------------------
// Test wrapper
// ---------------------------------------------------------------------------

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false } },
});

const Wrapper = ({ children }: { children: ReactNode }) => (
  <I18nProvider>
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  </I18nProvider>
);

function resetMocks() {
  mockUser = { id: "user-1", loginIdentifier: "admin@syncro.dev", applicationRole: "SUPER_ADMIN" };
  mockUpdateSparepart = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
  mockPatchProcurement = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
  mockCreatePriceEntry = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
  mockPriceEntries.data.data[0].enteredByName = "writer@syncro.dev";
  mockPriceEntries.refetch = vi.fn();
  mockImageQuery = {
    data: {
      data: { sparepartId: "sp-1", objectKey: "spareparts/sp-1/a.png", presignedUrl: "https://presigned/a.png" },
    },
    isLoading: false,
    isError: false,
    error: undefined,
    refetch: vi.fn(),
  };
  mockCreateImage = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
  mockDeleteImage = { mutateAsync: vi.fn().mockResolvedValue({ data: {} }), mutate: vi.fn(), isPending: false };
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
      expect(mockUpdateSparepart.mutateAsync).toHaveBeenCalledTimes(1);
    });
    // Story 8-3: the denial must persist inline (not toast-only), alongside the
    // static "Requires job scope LEADER or above." hints rendered by both sections.
    expect(
      await screen.findByText(/Sparepart updated, but procurement was not saved.*LEADER or above/),
    ).toBeInTheDocument();
  });
});

describe("SparepartManagement price history (Story 8-3)", () => {
  beforeEach(() => {
    resetMocks();
  });

  it("hides the Price History section in create mode", () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Create sparepart" }));

    expect(screen.getByText("1. Details")).toBeInTheDocument();
    expect(screen.queryByText("Price History")).not.toBeInTheDocument();
  });

  it("shows the Price History section with rows only when editing", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);

    expect(await screen.findByText("Price History")).toBeInTheDocument();
    expect(screen.getByTestId("price-history-table")).toBeInTheDocument();
    expect(screen.getByText("writer@syncro.dev")).toBeInTheDocument();

    // Create mode stays clean even after an edit dialog was used before.
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "Create sparepart" }));
    expect(screen.queryByText("Price History")).not.toBeInTheDocument();
  });

  it("appends an entry once through the create hook and resets the draft", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    fireEvent.change(await screen.findByTestId("price-amount-input"), { target: { value: "1500000" } });
    fireEvent.click(screen.getByRole("button", { name: "Append entry" }));

    await waitFor(() => {
      expect(mockCreatePriceEntry.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockCreatePriceEntry.mutateAsync).toHaveBeenCalledWith({
      sparepartId: "sp-1",
      data: { amount: 1500000, currency: "IDR", kursToIdr: undefined },
    });
    await waitFor(() => {
      expect(screen.getByTestId("price-amount-input")).toHaveValue("");
    });
  });

  it("surfaces a JOB_SCOPE_REQUIRED denial verbatim without resetting the draft", async () => {
    mockCreatePriceEntry = {
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
    fireEvent.change(await screen.findByTestId("price-amount-input"), { target: { value: "1500000" } });
    fireEvent.click(screen.getByRole("button", { name: "Append entry" }));

    await waitFor(() => {
      expect(mockCreatePriceEntry.mutateAsync).toHaveBeenCalledTimes(1);
    });
    const alerts = await screen.findAllByText(/job scope LEADER or above/);
    expect(alerts.length).toBeGreaterThan(0);
    expect(screen.getByTestId("price-amount-input")).toHaveValue("1500000");
  });

  it("copies a history row into the draft via Reuse, including switching to non-IDR kurs", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    await screen.findByText("Price History");
    // IDR default hides the kurs field until the USD row is reused.
    expect(screen.queryByTestId("price-kurs-input")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reuse" }));

    expect(screen.getByTestId("price-amount-input")).toHaveValue("1000");
    expect(screen.getByTestId("price-kurs-input")).toHaveValue("15500");
  });
});

describe("SparepartManagement sparepart image (Story 8-4)", () => {
  beforeEach(() => {
    resetMocks();
  });

  it("hides the Image section in create mode", () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Create sparepart" }));

    expect(screen.getByText("1. Details")).toBeInTheDocument();
    expect(screen.queryByText("Image")).not.toBeInTheDocument();
  });

  it("shows the Image section with a presigned preview when editing", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);

    expect(await screen.findByTestId("sparepart-image-section")).toBeInTheDocument();
    expect(screen.getByTestId("sparepart-image-preview")).toHaveAttribute("src", "https://presigned/a.png");
    expect(screen.getByRole("button", { name: "Replace image" })).toBeInTheDocument();
  });

  it("uploads the selected file once through the create hook", async () => {
    mockImageQuery = {
      ...mockImageQuery,
      data: { data: { sparepartId: "sp-1", objectKey: undefined, presignedUrl: undefined } },
    };

    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    await screen.findByTestId("sparepart-image-section");

    const file = new File(["image"], "part.png", { type: "image/png" });
    fireEvent.change(screen.getByTestId("sparepart-image-input"), { target: { files: [file] } });

    await waitFor(() => {
      expect(mockCreateImage.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockCreateImage.mutateAsync).toHaveBeenCalledWith({
      sparepartId: "sp-1",
      params: { filename: "part.png", contentType: "image/png" },
      data: { data: file },
    });
  });

  it("removes the image once through the delete hook", async () => {
    render(
      <Wrapper>
        <SparepartManagement />
      </Wrapper>,
    );

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    await screen.findByTestId("sparepart-image-section");
    fireEvent.click(screen.getByRole("button", { name: "Remove" }));

    await waitFor(() => {
      expect(mockDeleteImage.mutateAsync).toHaveBeenCalledTimes(1);
    });
    expect(mockDeleteImage.mutateAsync).toHaveBeenCalledWith({ sparepartId: "sp-1" });
  });

  it("surfaces a JOB_SCOPE_REQUIRED denial verbatim from the upload", async () => {
    mockCreateImage = {
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
    await screen.findByTestId("sparepart-image-section");

    const file = new File(["image"], "part.png", { type: "image/png" });
    fireEvent.change(screen.getByTestId("sparepart-image-input"), { target: { files: [file] } });

    expect(await screen.findByText("This action requires job scope LEADER or above.")).toBeInTheDocument();
  });
});
