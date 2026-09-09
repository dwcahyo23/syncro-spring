"use client";

import { useTranslations } from "next-intl";

import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/** Common-currency subset offered in the selector; the backend accepts any [A-Z]{3} code. */
export const PRICE_CURRENCIES = ["IDR", "USD", "EUR", "SGD", "MYR", "JPY"] as const;

export interface CurrencyPriceValue {
  amount: string;
  currency: string;
  kursToIdr: string;
}

export interface CurrencyPriceInputProps {
  value: CurrencyPriceValue;
  onChange: (value: CurrencyPriceValue) => void;
  errors?: { amount?: string; currency?: string; kursToIdr?: string };
  readOnly?: boolean;
}

/**
 * Controlled price input (Story 8-3). The kurs field only applies to non-IDR currencies;
 * the backend forces IDR kurs to 1 regardless of what is typed here.
 */
export function CurrencyPriceInput({ value, onChange, errors, readOnly = false }: CurrencyPriceInputProps) {
  const t = useTranslations("spareparts.shared.currencyPrice");
  const showKurs = value.currency !== "IDR";

  return (
    <div className="grid gap-2">
      <Label htmlFor="price-amount">{t("amount")}</Label>
      <div className="flex gap-2">
        <Input
          id="price-amount"
          className="min-w-0 flex-1"
          inputMode="decimal"
          value={value.amount}
          placeholder={t("amountPlaceholder")}
          aria-invalid={Boolean(errors?.amount)}
          disabled={readOnly}
          data-testid="price-amount-input"
          onChange={(event) => onChange({ ...value, amount: event.target.value })}
        />
        <Select
          value={value.currency}
          onValueChange={(currency) => onChange({ ...value, currency })}
          disabled={readOnly}
        >
          <SelectTrigger
            className="w-24 shrink-0"
            aria-label={t("currency")}
            aria-invalid={Boolean(errors?.currency)}
            data-testid="price-currency-select"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {PRICE_CURRENCIES.map((currency) => (
              <SelectItem key={currency} value={currency}>
                {currency}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {errors?.amount ? (
        <p role="alert" className="text-destructive text-sm">
          {errors.amount}
        </p>
      ) : null}
      {errors?.currency ? (
        <p role="alert" className="text-destructive text-sm">
          {errors.currency}
        </p>
      ) : null}
      {showKurs ? (
        <div className="grid gap-2">
          <Label htmlFor="price-kurs">{t("kursToIdr")}</Label>
          <Input
            id="price-kurs"
            inputMode="decimal"
            value={value.kursToIdr}
            placeholder={t("kursPlaceholder")}
            aria-invalid={Boolean(errors?.kursToIdr)}
            disabled={readOnly}
            data-testid="price-kurs-input"
            onChange={(event) => onChange({ ...value, kursToIdr: event.target.value })}
          />
          <p className="text-muted-foreground text-xs">{t("requiredForNonIdr")}</p>
          {errors?.kursToIdr ? (
            <p role="alert" className="text-destructive text-sm">
              {errors.kursToIdr}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
