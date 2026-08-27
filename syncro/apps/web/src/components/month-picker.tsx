"use client";

import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * Month quick picker (workorder-table polish): a single month with prev/next arrows —
 * no calendar date clicking. Emits a 0-based month and full year, matching the PRISMA
 * reference period filter ({@code month}/{@code year}).
 */
const MONTHS = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

interface MonthPickerProps {
  value: { month: number; year: number };
  onChange: (value: { month: number; year: number }) => void;
}

export function MonthPicker({ value, onChange }: MonthPickerProps) {
  const label = `${MONTHS[value.month]} ${value.year}`;

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
        aria-label="Previous month"
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
        aria-label="Next month"
        onClick={() => shift(1)}
      >
        <ChevronRightIcon className="size-4" />
      </Button>
    </div>
  );
}
