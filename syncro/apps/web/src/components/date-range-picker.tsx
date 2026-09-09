"use client";

import * as React from "react";

import { useFormatter, useTranslations } from "next-intl";
import type { DateRange } from "react-day-picker";

import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useCalendarLocale } from "@/lib/i18n/format";

interface DateRangePickerProps {
  value?: DateRange;
  onChange?: (value: DateRange | undefined) => void;
}

export function DateRangePicker({ value, onChange }: DateRangePickerProps) {
  const t = useTranslations("common");
  const format = useFormatter();
  const calendarLocale = useCalendarLocale();
  const [open, setOpen] = React.useState(false);
  const [internalDateRange, setInternalDateRange] = React.useState<DateRange | undefined>(() => {
    const to = new Date();
    const from = new Date(to);
    from.setDate(from.getDate() - 29);
    return { from, to };
  });
  const dateRange = value ?? internalDateRange;

  const formatDay = (date: Date) => format.dateTime(date, { day: "numeric", month: "short", year: "numeric" });

  const handleDateChange = (nextValue: DateRange | undefined) => {
    if (!value) {
      setInternalDateRange(nextValue);
    }
    onChange?.(nextValue);
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="outline" id="date" className="font-normal">
          {dateRange?.from
            ? dateRange.to
              ? `${formatDay(dateRange.from)} - ${formatDay(dateRange.to)}`
              : formatDay(dateRange.from)
            : t("selectDate")}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto overflow-hidden p-0" align="end">
        <Calendar
          mode="range"
          defaultMonth={dateRange?.from}
          selected={dateRange}
          onSelect={handleDateChange}
          numberOfMonths={2}
          locale={calendarLocale}
        />
      </PopoverContent>
    </Popover>
  );
}
