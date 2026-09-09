"use client";

import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";

/**
 * Month quick picker (workorder-table polish): a single month with prev/next arrows —
 * no calendar date clicking. Emits a 0-based month and full year, matching the PRISMA
 * reference period filter ({@code month}/{@code year}).
 */
interface MonthPickerProps {
  value: { month: number; year: number };
  onChange: (value: { month: number; year: number }) => void;
}

export function MonthPicker({ value, onChange }: MonthPickerProps) {
  const t = useTranslations("common");
  const format = useFormatter();

  // next-intl formats with the active locale's calendar; the month index is the
  // data value (0-based), so filtering never depends on the label.
  const label = format.dateTime(new Date(value.year, value.month, 1), {
    month: "long",
    year: "numeric",
  });

  const shift = (delta: number) => {
    const next = new Date(value.year, value.month + delta, 1);
    onChange({ month: next.getMonth(), year: next.getFullYear() });
  };

  return (
    <div className="flex items-center gap-1">
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="h-7 w-7"
        aria-label={t("previousMonth")}
        onClick={() => shift(-1)}
      >
        <ChevronLeftIcon className="size-4" />
      </Button>
      <span className="min-w-32 text-center text-sm font-medium">{label}</span>
      <Button
        type="button"
        variant="outline"
        size="icon"
        className="h-7 w-7"
        aria-label={t("nextMonth")}
        onClick={() => shift(1)}
      >
        <ChevronRightIcon className="size-4" />
      </Button>
    </div>
  );
}
