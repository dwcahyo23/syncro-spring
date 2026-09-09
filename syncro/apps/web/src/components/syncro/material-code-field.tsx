"use client";

import React from "react";

import { useTranslations } from "next-intl";

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
  const t = useTranslations("spareparts.shared.materialCode");
  return (
    <div className="grid gap-2">
      <Label htmlFor="material-code">{t("label")}</Label>
      <Input
        id="material-code"
        value={value}
        placeholder={t("optionalEg")}
        maxLength={64}
        aria-invalid={Boolean(error)}
        disabled={readOnly}
        data-testid="material-code-input"
        onChange={(event) => onChange(event.target.value)}
      />
      <p className="text-muted-foreground text-xs">{readOnly && disabledReason ? disabledReason : t("description")}</p>
      {error ? (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      ) : null}
    </div>
  );
}
