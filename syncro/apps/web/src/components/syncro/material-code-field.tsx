import React from "react";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export interface MaterialCodeFieldProps {
  value: string;
  onChange: (value: string) => void;
  error?: string;
  readOnly?: boolean;
  disabledReason?: string;
}

/**
 * Controlled input for the globally unique sparepart material code (Story 8-2).
 * Uniqueness and format are backend-owned; this field only guides input.
 */
export function MaterialCodeField({
  value,
  onChange,
  error,
  readOnly = false,
  disabledReason,
}: MaterialCodeFieldProps) {
  return (
    <div className="grid gap-2">
      <Label htmlFor="material-code">Material code</Label>
      <Input
        id="material-code"
        value={value}
        placeholder="Optional, e.g. MC-001"
        maxLength={64}
        aria-invalid={Boolean(error)}
        disabled={readOnly}
        data-testid="material-code-input"
        onChange={(event) => onChange(event.target.value)}
      />
      <p className="text-muted-foreground text-xs">
        {readOnly && disabledReason
          ? disabledReason
          : "Optional global identifier shared by identical spareparts across machines."}
      </p>
      {error ? (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      ) : null}
    </div>
  );
}
