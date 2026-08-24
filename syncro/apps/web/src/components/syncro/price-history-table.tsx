"use client";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { SparepartPriceEntryView } from "@/lib/api/generated/model";

export interface PriceHistoryTableProps {
  entries: SparepartPriceEntryView[];
  isLoading?: boolean;
  onReuse?: (entry: SparepartPriceEntryView) => void;
}

export function PriceHistoryTable({ entries, isLoading = false, onReuse }: PriceHistoryTableProps) {
  if (isLoading) {
    return (
      <div className="space-y-2" data-testid="price-history-loading">
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-full" />
        <Skeleton className="h-9 w-full" />
      </div>
    );
  }

  if (entries.length === 0) {
    return (
      <p className="py-2 text-muted-foreground text-sm" data-testid="price-history-empty">
        No price entries recorded yet.
      </p>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border" data-testid="price-history-table">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="whitespace-nowrap">Amount</TableHead>
            <TableHead className="whitespace-nowrap">Kurs</TableHead>
            <TableHead className="whitespace-nowrap">IDR Value</TableHead>
            <TableHead className="whitespace-nowrap">Entered By</TableHead>
            <TableHead>Entered At</TableHead>
            {onReuse ? <TableHead className="text-right">Actions</TableHead> : null}
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((entry) => (
            <TableRow key={entry.id ?? `${entry.enteredAt}-${entry.amount}`}>
              <TableCell>{formatMoney(entry.amount, entry.currency)}</TableCell>
              <TableCell>{formatNumber(entry.kursToIdr)}</TableCell>
              <TableCell>{formatMoney(entry.idrAmount, "IDR")}</TableCell>
              <TableCell>{entry.enteredByName ?? "-"}</TableCell>
              <TableCell className="whitespace-nowrap">{formatDateTime(entry.enteredAt)}</TableCell>
              {onReuse ? (
                <TableCell className="text-right">
                  <Button variant="outline" size="sm" onClick={() => onReuse(entry)}>
                    Reuse
                  </Button>
                </TableCell>
              ) : null}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function formatMoney(value: number | undefined, currency: string | undefined) {
  if (value == null) {
    return "-";
  }
  try {
    return new Intl.NumberFormat("en", { style: "currency", currency: currency ?? "IDR" }).format(value);
  } catch {
    return `${currency ?? ""} ${formatNumber(value)}`.trim();
  }
}

function formatNumber(value: number | undefined) {
  if (value == null) {
    return "-";
  }
  return new Intl.NumberFormat("en").format(value);
}

function formatDateTime(value: string | undefined) {
  if (!value) {
    return "-";
  }
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
