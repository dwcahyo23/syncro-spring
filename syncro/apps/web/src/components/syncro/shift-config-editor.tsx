"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export interface ShiftWindowInput {
  startTime: string;
  endTime: string;
}

export interface ShiftConfigEditorProps {
  value: ShiftWindowInput[];
  onChange: (value: ShiftWindowInput[]) => void;
  error?: string;
  readOnly?: boolean;
  disabledReason?: string;
}

const MAX_SHIFTS = 3;

/**
 * Controlled editor for up to three daily wall-clock shift windows (Story 8-5).
 * Ordering, numbering, and validation are backend-owned; this component only
 * shapes input as ordered { startTime, endTime } rows.
 */
export function ShiftConfigEditor({
  value,
  onChange,
  error,
  readOnly = false,
  disabledReason,
}: ShiftConfigEditorProps) {
  const t = useTranslations("machineHub.shiftConfig");

  function updateWindow(index: number, field: "startTime" | "endTime", next: string) {
    onChange(value.map((window, i) => (i === index ? { ...window, [field]: next } : window)));
  }

  // The group dialog wraps this editor in a <form>; Enter in a time input would otherwise
  // submit the machine-group form instead of the shift schedule.
  function preventEnterSubmit(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") {
      event.preventDefault();
    }
  }

  return (
    <div className="grid gap-2">
      {value.map((window, index) => (
        // biome-ignore lint/suspicious/noArrayIndexKey: rows are positional by contract (shift numbers are list order) and inputs are fully controlled
        <div key={index} className="grid grid-cols-[1fr_1fr_auto] items-end gap-2">
          <div className="grid gap-1">
            <Label htmlFor={`shift-${index + 1}-start`}>{t("startLabel", { index: index + 1 })}</Label>
            <Input
              id={`shift-${index + 1}-start`}
              type="time"
              value={window.startTime}
              aria-invalid={Boolean(error)}
              disabled={readOnly}
              data-testid={`shift-start-${index}`}
              onKeyDown={preventEnterSubmit}
              onChange={(event) => updateWindow(index, "startTime", event.target.value)}
            />
          </div>
          <div className="grid gap-1">
            <Label htmlFor={`shift-${index + 1}-end`}>{t("endLabel", { index: index + 1 })}</Label>
            <Input
              id={`shift-${index + 1}-end`}
              type="time"
              value={window.endTime}
              aria-invalid={Boolean(error)}
              disabled={readOnly}
              data-testid={`shift-end-${index}`}
              onKeyDown={preventEnterSubmit}
              onChange={(event) => updateWindow(index, "endTime", event.target.value)}
            />
          </div>
          {!readOnly ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              aria-label={t("removeAria", { index: index + 1 })}
              onClick={() => onChange(value.filter((_, i) => i !== index))}
            >
              {t("remove")}
            </Button>
          ) : null}
        </div>
      ))}
      {!readOnly && value.length < MAX_SHIFTS ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="w-fit"
          onClick={() => onChange([...value, { startTime: "", endTime: "" }])}
        >
          {t("add")}
        </Button>
      ) : null}
      <p className="text-muted-foreground text-xs">
        {readOnly && disabledReason ? disabledReason : t("hint", { max: MAX_SHIFTS })}
      </p>
      {error ? (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      ) : null}
    </div>
  );
}
