import { CircleCheck, CircleX, History, Minus, TriangleAlert } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { TelemetryDataQualityStatus } from "@/features/system-health/types";

// In-page anchor of the quarantine log section on the System Health page (page-spec 4.8:
// the quarantine log is accessible from the DataQualityPanel "View Quarantine Log" link).
const QUARANTINE_LOG_ANCHOR = "#telemetry-quarantine-log";

type Severity = "SUCCESS" | "WARNING" | "CRITICAL" | "NEUTRAL";

const SEVERITY_BADGES: Record<Severity, { className: string; label: string; Icon: typeof CircleCheck }> = {
  SUCCESS: {
    className: "border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400",
    label: "OK",
    Icon: CircleCheck,
  },
  WARNING: {
    className: "border-transparent bg-amber-500/15 text-amber-700 dark:text-amber-400",
    label: "Warning",
    Icon: TriangleAlert,
  },
  CRITICAL: {
    className: "border-transparent bg-destructive/15 text-destructive",
    label: "Critical",
    Icon: CircleX,
  },
  NEUTRAL: {
    className: "border-transparent bg-muted/60 text-muted-foreground",
    label: "Neutral",
    Icon: Minus,
  },
};

function severityBadge(severity: string) {
  // Object.hasOwn, not `in`: the `in` operator walks the prototype chain, so keys like
  // "toString" would resolve to Object.prototype members and crash on the missing Icon.
  const config = Object.hasOwn(SEVERITY_BADGES, severity)
    ? SEVERITY_BADGES[severity as Severity]
    : SEVERITY_BADGES.NEUTRAL;
  return (
    <Badge aria-label={config.label} className={config.className} variant="outline">
      <config.Icon aria-hidden="true" className="shrink-0" />
      {config.label}
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

function headerBadge(status: TelemetryDataQualityStatus | undefined, isLoading: boolean) {
  if (isLoading) {
    return <Skeleton className="h-5 w-20" />;
  }
  if (status) {
    return (
      <div className="flex items-center gap-2">
        <span className="text-muted-foreground text-xs">{status.statusLabel}</span>
        {severityBadge(status.statusSeverity)}
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
  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="font-medium text-sm">Data Quality</CardTitle>
          {headerBadge(status, isLoading)}
        </div>
        <CardDescription className="text-xs">
          Telemetry rejection, anomaly, and dead-letter metrics over the reporting window.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        {isLoading ? <Skeleton className="h-24 w-full" /> : null}
        {!isLoading && isError ? (
          <p className="text-destructive text-xs">
            {status ? "Unable to refresh data quality. Showing last known metrics." : "Unable to load data quality."}
          </p>
        ) : null}
        {!isLoading && !isError && !status ? (
          <p className="text-muted-foreground text-xs">No data quality reported.</p>
        ) : null}
        {status ? (
          <>
            <MetricRow label="Quarantined" value={String(status.quarantinedCount)} severity={status.quarantinedSeverity} />
            <MetricRow
              label="Rejection rate"
              value={`${status.rejectionRatePct.toFixed(2)}%`}
              severity={status.rejectionRateSeverity}
            />
            <MetricRow label="Anomalies" value={String(status.anomalyCount)} severity={status.anomalySeverity} />
            <MetricRow label="Dead-letter" value={String(status.deadLetterCount)} severity={status.deadLetterSeverity} />
            <div className="flex items-baseline justify-between gap-2 text-xs">
              <span className="shrink-0 text-muted-foreground">Window</span>
              <span className="min-w-0 break-words text-right font-medium">
                {formatWindowLabel(status.windowSeconds)}
              </span>
            </div>
            {status.statusReason ? (
              <p className="text-muted-foreground text-xs">{status.statusReason}</p>
            ) : null}
            <a
              className="inline-flex items-center gap-1 text-primary text-xs underline-offset-4 hover:underline"
              href={QUARANTINE_LOG_ANCHOR}
            >
              <History aria-hidden="true" className="h-3 w-3" />
              View Quarantine Log
            </a>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function MetricRow({ label, value, severity }: { readonly label: string; readonly value: string; readonly severity: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-muted-foreground text-xs">{label}</span>
      <span className="flex items-center gap-2">
        <span className="font-medium text-xs">{value}</span>
        {severityBadge(severity)}
      </span>
    </div>
  );
}
