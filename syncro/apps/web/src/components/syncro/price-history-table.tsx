"use client";

import { useFormatter, useTranslations } from "next-intl";

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
  const t = useTranslations("spareparts.shared.priceHistory");
  const tc = useTranslations("common");
  const format = useFormatter();

  const formatMoney = (value: number | undefined, currency: string | undefined) => {
    if (value == null) {
      return "-";
    }
    try {
      return format.number(value, { style: "currency", currency: currency ?? "IDR" });
    } catch {
      return `${currency ?? ""} ${formatNumber(value)}`.trim();
    }
  };

  const formatNumber = (value: number | undefined) => {
    if (value == null) {
      return "-";
    }
    return format.number(value);
  };

  const formatDateTime = (value: string | undefined) => {
    if (!value) {
      return "-";
    }
    return format.dateTime(new Date(value), { dateStyle: "medium", timeStyle: "short" });
  };

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
        {t("empty")}
      </p>
    );
  }

  return (
    <div className="overflow-x-auto rounded-lg border" data-testid="price-history-table">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="whitespace-nowrap">{t("amount")}</TableHead>
            <TableHead className="whitespace-nowrap">{t("kurs")}</TableHead>
            <TableHead className="whitespace-nowrap">{t("idrValue")}</TableHead>
            <TableHead className="whitespace-nowrap">{t("enteredBy")}</TableHead>
            <TableHead>{t("enteredAt")}</TableHead>
            {onReuse ? <TableHead className="text-right">{tc("actions")}</TableHead> : null}
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
                    {t("reuse")}
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
