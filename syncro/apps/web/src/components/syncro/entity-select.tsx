"use client";

import * as React from "react";
import { useTranslations } from "next-intl";

import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/**
 * Searchable single-select for entity pickers (Odoo many2one style).
 * Same interaction model as ui/select but with an embedded search box —
 * use whenever the option list is user/machine/plant/section-scale.
 */
export function EntitySelect({
  label,
  value,
  options,
  onValueChange,
  placeholder,
  disabled,
  searchPlaceholder,
}: {
  label?: string;
  value?: string;
  options: { id: string; label: string; disabled?: boolean }[];
  onValueChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  searchPlaceholder?: string;
}) {
  const t = useTranslations("common");
  const [open, setOpen] = React.useState(false);
  const [search, setSearch] = React.useState("");
  const term = search.trim().toLowerCase();
  const filtered = term ? options.filter((o) => o.label.toLowerCase().includes(term)) : options;
  const selected = options.find((o) => o.id === value);

  return (
    <Select
      value={value}
      onValueChange={(v) => {
        onValueChange(v);
        setSearch("");
        setOpen(false);
      }}
      open={open}
      onOpenChange={(o) => {
        setOpen(o);
        if (!o) setSearch("");
      }}
      disabled={disabled}
    >
      <SelectTrigger className="w-full min-w-0" aria-label={label}>
        <SelectValue placeholder={placeholder ?? t("selectPlaceholder")}>
          {selected ? selected.label : undefined}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {options.length > 8 ? (
          <div className="p-2">
            <Input
              autoFocus
              value={search}
              placeholder={searchPlaceholder ?? t("search")}
              onChange={(e) => setSearch(e.target.value)}
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => e.stopPropagation()}
            />
          </div>
        ) : null}
        {filtered.map((o) => (
          <SelectItem key={o.id} value={o.id} disabled={o.disabled}>
            {o.label}
          </SelectItem>
        ))}
        {filtered.length === 0 ? (
          <p className="px-2 py-3 text-center text-sm text-muted-foreground">{t("noResults")}</p>
        ) : null}
      </SelectContent>
    </Select>
  );
}
