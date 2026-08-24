import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { SparepartPriceEntryView } from "@/lib/api/generated/model";

import { PriceHistoryTable } from "./price-history-table";

const ENTRIES: SparepartPriceEntryView[] = [
  {
    id: "entry-2",
    sparepartId: "sp-1",
    amount: 1500000,
    currency: "IDR",
    kursToIdr: 1,
    idrAmount: 1500000,
    enteredBy: "user-1",
    enteredByName: "leader@syncro.dev",
    enteredAt: "2026-08-24T08:30:00Z",
  },
  {
    id: "entry-1",
    sparepartId: "sp-1",
    amount: 1000,
    currency: "USD",
    kursToIdr: 15500,
    idrAmount: 15500000,
    enteredBy: "user-1",
    enteredByName: "admin@syncro.dev",
    enteredAt: "2026-08-01T10:15:00Z",
  },
];

describe("PriceHistoryTable", () => {
  it("renders one row per entry with currency, kurs, IDR value, actor, and timestamp", () => {
    render(<PriceHistoryTable entries={ENTRIES} />);

    expect(screen.getByText("$1,000.00")).toBeInTheDocument();
    // The IDR row's amount and its IDR value are both 1,500,000.
    expect(screen.getAllByText("IDR 1,500,000")).toHaveLength(2);
    expect(screen.getByText("15,500")).toBeInTheDocument();
    expect(screen.getByText("IDR 15,500,000")).toBeInTheDocument();
    expect(screen.getByText("leader@syncro.dev")).toBeInTheDocument();
    expect(screen.getByText("admin@syncro.dev")).toBeInTheDocument();
    expect(screen.getAllByText(/Aug \d+, 2026/)).toHaveLength(2);
  });

  it("shows the empty-state text when there are no entries", () => {
    render(<PriceHistoryTable entries={[]} />);

    expect(screen.getByTestId("price-history-empty")).toHaveTextContent("No price entries recorded yet.");
  });

  it("shows a loading state while the history is being fetched", () => {
    render(<PriceHistoryTable entries={[]} isLoading />);

    expect(screen.getByTestId("price-history-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("price-history-table")).not.toBeInTheDocument();
  });

  it("fires onReuse with the row entry and omits the actions column without onReuse", () => {
    const onReuse = vi.fn();
    const { rerender } = render(<PriceHistoryTable entries={ENTRIES} onReuse={onReuse} />);

    fireEvent.click(screen.getAllByRole("button", { name: "Reuse" })[1]);

    expect(onReuse).toHaveBeenCalledWith(ENTRIES[1]);

    rerender(<PriceHistoryTable entries={ENTRIES} />);
    expect(screen.queryByRole("button", { name: "Reuse" })).not.toBeInTheDocument();
  });
});
