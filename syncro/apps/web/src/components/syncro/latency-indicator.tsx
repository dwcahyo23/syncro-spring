import { CircleCheck, CircleX, Gauge, Minus, TriangleAlert } from "lucide-react";

import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import type { LatencyState } from "@/features/system-health/types";

const STATE_CONFIG: Record<LatencyState, { className: string; Icon: typeof CircleCheck; label: string }> = {
  NO_DATA: {
    className: "border-muted/60 bg-muted/40 text-muted-foreground",
    Icon: Minus,
    label: "No data",
  },
  NORMAL: {
    className: "status-badge-healthy",
    Icon: CircleCheck,
    label: "Normal",
  },
  ELEVATED: {
    className: "status-badge-warning",
    Icon: TriangleAlert,
    label: "Elevated",
  },
  CRITICAL: {
    className: "border-destructive/40 bg-destructive/10 text-destructive",
    Icon: CircleX,
    label: "Critical",
  },
};

/** Formats a latency sample deterministically: milliseconds below 1s, seconds with one decimal above. */
export function formatLatencyLabel(latencyMs: number | null): string {
  if (latencyMs === null || !Number.isFinite(latencyMs)) {
    return "No data";
  }
  if (latencyMs < 1000) {
    return `${Math.round(latencyMs)} ms`;
  }
  return `${(latencyMs / 1000).toFixed(1)}s`;
}

/**
 * Compact end-to-end telemetry latency indicator (page-spec 4.1 header): publish (payload
 * timestamp) to dashboard-visible (backend latest write). The state (normal &lt;5s, elevated
 * 5–15s, critical &gt;15s, or no data before the first sample) is backend-computed and
 * rendered verbatim; elevated/critical are visually distinct AND text-labeled, never
 * color-only.
 */
export function LatencyIndicator({
  latencyState,
  lastLatencyMs,
  isLoading = false,
  isError = false,
}: {
  readonly latencyState: LatencyState | undefined;
  readonly lastLatencyMs: number | null | undefined;
  readonly isLoading?: boolean;
  readonly isError?: boolean;
}) {
  let value: string;
  if (isError && latencyState === undefined) {
    // The static "Latency" label prefixes this, so the word is not repeated here.
    value = "unavailable";
  } else if (latencyState === undefined) {
    value = formatLatencyLabel(null);
  } else {
    value = formatLatencyLabel(lastLatencyMs ?? null);
  }

  if (isLoading) {
    return (
      <div className="flex items-center gap-2" role="status" aria-label="Telemetry latency">
        <Gauge aria-hidden="true" className="h-4 w-4 text-muted-foreground" />
        <span className="text-muted-foreground text-xs">Latency</span>
        <Skeleton className="h-5 w-24" />
      </div>
    );
  }

  const config = latencyState !== undefined && latencyState in STATE_CONFIG
    ? STATE_CONFIG[latencyState]
    : STATE_CONFIG.NO_DATA;
  // The trailing state label is hidden when it would duplicate the value ("No data · No
  // data") or assert a state we do not know (fetch failed before any data: "unavailable"
  // must not be paired with a "No data" verdict).
  const showLabel = config.label !== value && !(isError && latencyState === undefined);
  const accessibleName = showLabel
    ? `Telemetry latency: ${value}, ${config.label}`
    : `Telemetry latency: ${value}`;

  return (
    <div
      role="status"
      aria-label={accessibleName}
      className={cn("flex items-center gap-2 rounded-lg border px-2.5 py-1.5 text-xs", config.className)}
    >
      <config.Icon aria-hidden="true" className="h-4 w-4 shrink-0" />
      <span className="font-medium">Latency {value}</span>
      {showLabel ? (
        <>
          <span aria-hidden="true">·</span>
          <span>{config.label}</span>
        </>
      ) : null}
      {isError && latencyState !== undefined ? (
        <span className="font-medium">(last known — refresh failed)</span>
      ) : null}
    </div>
  );
}
