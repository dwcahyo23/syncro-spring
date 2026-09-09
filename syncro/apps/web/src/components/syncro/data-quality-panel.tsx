"use client";

import { CircleCheck, CircleX, History, Minus, TriangleAlert } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { TelemetryDataQualityStatus } from "@/features/system-health/types";

// In-page anchor of the quarantine log section on the System Health page (page-spec 4.8:
// the quarantine log is accessible from the DataQualityPanel "View Quarantine Log" link).
const QUARANTINE_LOG_ANCHOR = "#telemetry-quarantine-log";

type Severity = "SUCCESS" | "WARNING" | "CRITICAL" | "NEUTRAL";

// Styling map keyed on the raw severity code; labels resolve via t(`severity.${code}`).
const SEVERITY_BADGES: Record<Severity, { className: string; Icon: typeof CircleCheck }> = {
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

function severityBadge(severity: string, t: ReturnType<typeof useTranslations>) {
  // Object.hasOwn, not `in`: the `in` operator walks the prototype chain, so keys like
  // "toString" would resolve to Object.prototype members and crash on the missing Icon.
  const code: Severity = Object.hasOwn(SEVERITY_BADGES, severity) ? (severity as Severity) : "NEUTRAL";
  const config = SEVERITY_BADGES[code];
  const label = t(`severity.${code}`);
  return (
    <Badge aria-label={label} className={config.className} variant="outline">
      <config.Icon aria-hidden="true" className="shrink-0" />
      {label}
    </Badge>
  );
}

/**
 * Formats the metrics window per the page-spec contract: the default 1-hour window renders
 * as "last 1 hour"; every other window renders in minutes.
 */
export function formatWindowLabel(windowSeconds: number): string {
  if (windowSeconds === 3600) {
    return "last 1 hour";
  }
  const minutes = Math.max(1, Math.round(windowSeconds / 60));
  return `last ${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}

function headerBadge(
  status: TelemetryDataQualityStatus | undefined,
  isLoading: boolean,
  t: ReturnType<typeof useTranslations>,
) {
  if (isLoading) {
    return <Skeleton className="h-5 w-20" />;
  }
  if (status) {
    return (
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground text-xs">{status.statusLabel}</span>
        {severityBadge(status.statusSeverity, t)}
      </div>
    );
  }
  return null;
}

/**
 * Read-only panel with the windowed telemetry data-quality metrics (page-spec 4.4):
 * quarantined count, rejection rate, anomaly count, dead-letter count, and the window.
 * All values and severities are backend-computed (SUPER_ADMIN-only endpoint) and rendered
 * verbatim; severity is communicated with text labels, never color alone. The "View
 * Quarantine Log" link drills down to the quarantine log section on the same page.
 */
export function DataQualityPanel({
  status,
  isLoading,
  isError,
}: {
  readonly status: TelemetryDataQualityStatus | undefined;
  readonly isLoading: boolean;
  readonly isError: boolean;
}) {
  const t = useTranslations("systemHealth.dataQuality");

  // Localized rendering of the page-spec window contract; the exported
  // formatWindowLabel stays as the canonical English helper for callers/tests.
  const windowLabel = (windowSeconds: number) => {
    if (windowSeconds === 3600) return t("windowLastHour");
    const minutes = Math.max(1, Math.round(windowSeconds / 60));
    return t("windowMinutes", { count: minutes, unit: minutes === 1 ? t("minute") : t("minutes") });
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="font-medium text-sm">{t("title")}</CardTitle>
          {headerBadge(status, isLoading, t)}
        </div>
        <CardDescription className="text-xs">{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        {isLoading ? <Skeleton className="h-24 w-full" /> : null}
        {!isLoading && isError ? (
          <p className="text-destructive text-xs">{status ? t("unableRefresh") : t("unableLoad")}</p>
        ) : null}
        {!isLoading && !isError && !status ? <p className="text-muted-foreground text-xs">{t("noData")}</p> : null}
        {status ? (
          <>
            <MetricRow
              t={t}
              label={t("quarantined")}
              value={String(status.quarantinedCount)}
              severity={status.quarantinedSeverity}
            />
            <MetricRow
              t={t}
              label={t("rejectionRate")}
              value={`${status.rejectionRatePct.toFixed(2)}%`}
              severity={status.rejectionRateSeverity}
            />
            <MetricRow
              t={t}
              label={t("anomalies")}
              value={String(status.anomalyCount)}
              severity={status.anomalySeverity}
            />
            <MetricRow
              t={t}
              label={t("deadLetter")}
              value={String(status.deadLetterCount)}
              severity={status.deadLetterSeverity}
            />
            <div className="flex items-baseline justify-between gap-2 text-xs">
              <span className="shrink-0 text-muted-foreground">{t("window")}</span>
              <span className="min-w-0 break-words text-right font-medium">{windowLabel(status.windowSeconds)}</span>
            </div>
            {status.statusReason ? <p className="text-muted-foreground text-xs">{status.statusReason}</p> : null}
            <a
              className="inline-flex items-center gap-1 text-primary text-xs underline-offset-4 hover:underline"
              href={QUARANTINE_LOG_ANCHOR}
            >
              <History aria-hidden="true" className="h-3 w-3" />
              {t("viewQuarantineLog")}
            </a>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function MetricRow({
  t,
  label,
  value,
  severity,
}: {
  readonly t: ReturnType<typeof useTranslations>;
  readonly label: string;
  readonly value: string;
  readonly severity: string;
}) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-muted-foreground text-xs">{label}</span>
      <span className="flex items-center gap-2">
        <span className="font-medium text-xs">{value}</span>
        {severityBadge(severity, t)}
      </span>
    </div>
  );
}
