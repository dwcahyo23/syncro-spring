"use client";

import type { ReactNode } from "react";

import { CircleCheck, CircleX, Minus, TriangleAlert } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

export type HealthSeverity = "SUCCESS" | "WARNING" | "CRITICAL" | "NEUTRAL";

type HealthCardProps = {
  readonly title: string;
  readonly description?: string;
  readonly statusLabel?: string;
  readonly statusSeverity?: HealthSeverity;
  readonly statusReason?: string | null;
  readonly timestamp?: string | null;
  readonly loading?: boolean;
  readonly error?: boolean;
  readonly empty?: boolean;
  readonly children?: ReactNode;
};

const SEVERITY_CONFIG: Record<HealthSeverity, { className: string; Icon: typeof CircleCheck }> = {
  SUCCESS: {
    className: "status-badge-healthy",
    Icon: CircleCheck,
  },
  WARNING: {
    className: "status-badge-warning",
    Icon: TriangleAlert,
  },
  CRITICAL: {
    className: "border-transparent bg-destructive/15 text-destructive",
    Icon: CircleX,
  },
  NEUTRAL: {
    className: "border-transparent bg-muted/60 text-muted-foreground",
    Icon: Minus,
  },
};

/** Derives a display severity from a status label when no explicit severity is available. */
export function deriveSeverity(statusLabel: string | undefined): HealthSeverity {
  const value = (statusLabel ?? "").toLowerCase();
  if (
    /\bnot\s+(down|stopped|failed|offline|error|unavailable|degraded|stale|warning|disconnected|delayed|reduced|critical|out of service)\b/.test(
      value,
    )
  ) {
    return "NEUTRAL";
  }
  if (/\bnot\s+(up|running|ok|online|ready|subscribed|active|healthy|connected|available|success)\b/.test(value)) {
    return "WARNING";
  }
  if (/\b(down|stopped|failed|offline|error|unavailable|out of service|critical)\b/.test(value)) {
    return "CRITICAL";
  }
  if (/\b(degraded|stale|warning|disconnected|delayed|reduced)\b/.test(value)) {
    return "WARNING";
  }
  if (/\b(up|running|ok|online|healthy|ready|subscribed|active|success)\b/.test(value)) {
    return "SUCCESS";
  }
  return "NEUTRAL";
}

/**
 * Formats an ISO timestamp as an absolute UTC string for display, or returns the raw value
 * when unparseable. Locale defaults to "en" so direct (non-component) callers keep the
 * pre-23-2 output; components pass `useLocale()` to localize the calendar fields while the
 * timeZone:"UTC" semantics stay data-accurate (AC3).
 */
export function formatDateTimeUtc(value: string, locale = "en"): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value || "—";
  }
  const formatted = new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(date);
  return `${formatted} UTC`;
}

/** Single read-only key/value row rendered inside a {@link HealthCard}. */
export function HealthMetricRow({ label, value }: { readonly label: string; readonly value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2 text-xs">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <span className="min-w-0 break-words text-right font-medium">{value}</span>
    </div>
  );
}

/**
 * Presentational card describing the health of a single dependency or worker.
 * Read-only by design: it renders no mutation or navigation controls.
 */
export function HealthCard({
  title,
  description,
  statusLabel,
  statusSeverity,
  statusReason,
  timestamp,
  loading = false,
  error = false,
  empty = false,
  children,
}: HealthCardProps) {
  const t = useTranslations("systemHealth.healthCard");
  const locale = useLocale();
  const severity: HealthSeverity = normalizeSeverity(statusSeverity) ?? deriveSeverity(statusLabel);
  // biome-ignore lint/nursery/useNullishCoalescing: intentionally use || so empty-string labels do not count as known data
  const hasKnownData = Boolean(statusLabel || statusReason || timestamp);

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="font-medium text-sm">{title}</CardTitle>
          {loading ? (
            <Skeleton className="h-5 w-20" />
          ) : (
            <HealthStatusBadge label={statusLabel ?? t("unknownStatus")} severity={severity} />
          )}
        </div>
        {description ? <CardDescription className="text-xs">{description}</CardDescription> : null}
      </CardHeader>
      <CardContent className="space-y-1">
        {loading ? <Skeleton className="h-16 w-full" /> : null}
        {!loading && error ? (
          <p className="text-destructive text-xs">
            {hasKnownData ? t("unableRefresh", { title }) : t("unableCheck", { title })}
          </p>
        ) : null}
        {!loading && !error && empty ? <p className="text-muted-foreground text-xs">{t("noData")}</p> : null}
        {!loading && (hasKnownData || (!error && !empty)) ? (
          <>
            <HealthMetricRow
              label={t("severityLabel")}
              value={t.has(`severity.${severity}`) ? t(`severity.${severity}`) : severity}
            />
            {statusReason ? <HealthMetricRow label={t("reasonLabel")} value={statusReason} /> : null}
            {timestamp ? (
              <HealthMetricRow label={t("reportedAt")} value={formatDateTimeUtc(timestamp, locale)} />
            ) : null}
            {children}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function HealthStatusBadge({ label, severity }: { readonly label: string; readonly severity: HealthSeverity }) {
  const t = useTranslations("systemHealth.healthCard");
  const config = SEVERITY_CONFIG[severity] ?? SEVERITY_CONFIG.NEUTRAL;
  return (
    <Badge aria-label={t("statusAria", { label })} className={config.className} variant="outline">
      <config.Icon aria-hidden="true" className="shrink-0" />
      {label}
    </Badge>
  );
}

/** Guards a runtime severity value, falling back to NEUTRAL for anything unexpected. */
function normalizeSeverity(value: string | undefined): HealthSeverity | undefined {
  if (value === "SUCCESS" || value === "WARNING" || value === "CRITICAL" || value === "NEUTRAL") {
    return value;
  }
  return undefined;
}
