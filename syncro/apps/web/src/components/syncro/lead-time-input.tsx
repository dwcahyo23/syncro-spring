import React, { useState } from "react";

import { useTranslations } from "next-intl";

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
  const t = useTranslations("spareparts.shared.leadTime");
  const [unit, setUnit] = useState<LeadTimeUnit>("hours");
  const [rawDisplay, setRawDisplay] = useState<string | null>(null);
  // The canonical English helper below is exported for tests and callers; this
  // render site localizes the same value through ICU so /id reads naturally.
  const daysHint = (() => {
    if (value === "") return null;
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric <= 0) return null;
    return t("daysHint", { value: trimNumeric(String(numeric / 24)) });
  })();

  return (
    <div className="grid gap-2">
      <Label htmlFor="lead-time">{t("label")}</Label>
      <div className="flex gap-2">
        <Input
          id="lead-time"
          className="min-w-0 flex-1"
          inputMode="decimal"
          value={rawDisplay ?? hoursToDisplay(value, unit)}
          placeholder={t("optional")}
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
          <SelectTrigger className="w-24 shrink-0" aria-label={t("unitAria")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="hours">{t("unitHours")}</SelectItem>
            <SelectItem value="days">{t("unitDays")}</SelectItem>
          </SelectContent>
        </Select>
      </div>
      <p className="text-muted-foreground text-xs">
        {daysHint ? t("equivalent", { hint: daysHint }) : null}
        {t("description")}
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
