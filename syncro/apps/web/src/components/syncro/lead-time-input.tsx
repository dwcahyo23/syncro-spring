import React, { useState } from "react";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

export type LeadTimeUnit = "hours" | "days";

export interface LeadTimeInputProps {
  /** Decimal hours — the persisted unit. Empty string means unset. */
  value: string;
  onChange: (hoursValue: string) => void;
  error?: string;
  readOnly?: boolean;
}

function hoursToDisplay(hours: string, unit: LeadTimeUnit): string {
  if (hours === "") {
    return "";
  }
  const numeric = Number(hours);
  if (!Number.isFinite(numeric)) {
    return hours;
  }
  return unit === "hours" ? trimNumeric(hours) : trimNumeric(String(numeric / 24));
}

function displayToHours(display: string, unit: LeadTimeUnit): string {
  if (display === "") {
    return "";
  }
  const numeric = Number(display);
  if (!Number.isFinite(numeric)) {
    return display;
  }
  return unit === "hours" ? display : String(Number((numeric * 24).toFixed(4)));
}

/** Rounds away floating-point noise for display only; submitted values stay as typed. */
function trimNumeric(text: string): string {
  return String(Number(text));
}

/**
 * Controlled lead-time input (Story 8-2). The value contract is decimal HOURS; the unit
 * selector is a presentation convenience (7.5 days = 180 h). Backend validation remains
 * authoritative.
 *
 * While the user types, their raw text is kept verbatim (so trailing dots like "7." survive);
 * remount the component per edited entity (e.g. via a `key` prop) to reset it between dialogs.
 */
export function LeadTimeInput({ value, onChange, error, readOnly = false }: LeadTimeInputProps) {
  const [unit, setUnit] = useState<LeadTimeUnit>("hours");
  const [rawDisplay, setRawDisplay] = useState<string | null>(null);
  const daysHint = hoursToDaysHint(value);

  return (
    <div className="grid gap-2">
      <Label htmlFor="lead-time">Lead time</Label>
      <div className="flex gap-2">
        <Input
          id="lead-time"
          className="min-w-0 flex-1"
          inputMode="decimal"
          value={rawDisplay ?? hoursToDisplay(value, unit)}
          placeholder="Optional"
          aria-invalid={Boolean(error)}
          disabled={readOnly}
          data-testid="lead-time-input"
          onChange={(event) => {
            const text = event.target.value.trim();
            setRawDisplay(text);
            onChange(displayToHours(text, unit));
          }}
        />
        <Select
          value={unit}
          onValueChange={(next) => {
            setUnit(next as LeadTimeUnit);
            setRawDisplay(null);
          }}
          disabled={readOnly}
        >
          <SelectTrigger className="w-24 shrink-0" aria-label="Lead time unit">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="hours">hours</SelectItem>
            <SelectItem value="days">days</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <p className="text-muted-foreground text-xs">
        {daysHint ? `Equivalent to ${daysHint}. ` : null}
        Used to project procurement timing before sparepart depletion.
      </p>
      {error ? (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function hoursToDaysHint(hours: string): string | null {
  if (hours === "") {
    return null;
  }
  const numeric = Number(hours);
  if (!Number.isFinite(numeric) || numeric <= 0) {
    return null;
  }
  return `${trimNumeric(String(numeric / 24))} days`;
}
