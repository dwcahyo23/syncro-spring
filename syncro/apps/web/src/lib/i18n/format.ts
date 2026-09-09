import { useFormatter, useLocale, useTranslations } from "next-intl";
import { enUS, id } from "react-day-picker/locale";

/**
 * Story 23-2: shared display helpers on top of next-intl's `useFormatter()` so
 * every locale renders id-ID / en-US grouping while the wire keeps ISO/UTC
 * values (AD-23). Relative-time copy lives in `common` catalogs with ICU
 * plurals — never `Intl.RelativeTimeFormat` guessing.
 */

type DateTimeInput = string | number | Date;

const RDP_LOCALES: Record<string, typeof id> = { en: enUS, id };

/** react-day-picker locale matching the active message locale (P6, story 23-2). */
export function useCalendarLocale() {
  return RDP_LOCALES[useLocale()] ?? enUS;
}

function toDate(value: DateTimeInput): Date | null {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Relative freshness label ("5 minutes ago") in the active locale. Buckets
 * mirror the previous English-only "Xs/Xm/Xh/Xd ago" output.
 */
export function useRelativeTime() {
  const t = useTranslations("common");
  return (value: DateTimeInput, now: Date = new Date()): string => {
    const date = toDate(value);
    if (!date) return t("notAvailable");
    const diffSec = Math.floor((now.getTime() - date.getTime()) / 1000);
    if (diffSec < 60) return t("justNow");
    if (diffSec < 3600) return t("minutesAgo", { count: Math.floor(diffSec / 60) });
    if (diffSec < 86400) return t("hoursAgo", { count: Math.floor(diffSec / 3600) });
    return t("daysAgo", { count: Math.floor(diffSec / 86400) });
  };
}

/** Formatter pair for sites that must keep UTC calendar semantics (health, audit). */
export function useUtcFormatters() {
  const format = useFormatter();

  function dateTimeUtc(value: DateTimeInput): string {
    const date = toDate(value);
    if (!date) return String(value ?? "") || "—";
    return `${format.dateTime(date, { dateStyle: "medium", timeStyle: "short", timeZone: "UTC" })} UTC`;
  }

  function dateOnlyUtc(value: DateTimeInput): string {
    const date = toDate(value);
    if (!date) return String(value ?? "") || "—";
    return format.dateTime(date, { dateStyle: "medium", timeZone: "UTC" });
  }

  return { dateTimeUtc, dateOnlyUtc };
}

/** Locale-aware date + short time (browser calendar), replacing `Intl.DateTimeFormat("en", …)`. */
export function useDateTimeFormatter() {
  const format = useFormatter();
  return {
    dateTime: (value: DateTimeInput, options?: { timeZone?: string }) => {
      const date = toDate(value);
      return date ? format.dateTime(date, { dateStyle: "medium", timeStyle: "short", ...options }) : "—";
    },
    date: (value: DateTimeInput) => {
      const date = toDate(value);
      return date ? format.dateTime(date, { dateStyle: "medium" }) : "—";
    },
  };
}

/** Locale-aware number / currency formatting (id-ID → "1.500" / "Rp 1.500"). */
export function useNumberFormatter() {
  const format = useFormatter();
  return {
    number: (value: number, options?: Parameters<typeof format.number>[1]) => format.number(value, options),
    currency: (value: number, currency: string) =>
      format.number(value, { style: "currency", currency, maximumFractionDigits: 0 }),
  };
}
