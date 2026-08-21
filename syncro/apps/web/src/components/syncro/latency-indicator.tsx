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
    className: "border-emerald-600/40 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
    Icon: CircleCheck,
    label: "Normal",
  },
  ELEVATED: {
    className: "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-400",
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
    value = "Latency unavailable";
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

  return (
    <div
      role="status"
      aria-label={`Telemetry latency: ${value}, ${config.label}`}
      className={cn("flex items-center gap-2 rounded-lg border px-2.5 py-1.5 text-xs", config.className)}
    >
      <config.Icon aria-hidden="true" className="h-4 w-4 shrink-0" />
      <span className="font-medium">Latency {value}</span>
      <span className="sr-only">({config.label})</span>
      <span aria-hidden="true">·</span>
      <span>{config.label}</span>
    </div>
  );
}
