"use client";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { InstallationProjection, MachineSparepartProjectionsView } from "@/lib/api/generated/model";
import { useGetMachineSparepartProjections } from "@/lib/api/generated/syncro";

import { formatDateTimeUtc } from "./health-card";

type InsufficientReason = NonNullable<MachineSparepartProjectionsView["insufficientReason"]>;
type CalculationBasis = NonNullable<MachineSparepartProjectionsView["calculationBasis"]>;

const INSUFFICIENT_REASON_TEXT: Record<InsufficientReason, string> = {
  NO_TELEMETRY: "No accepted telemetry in the estimation window.",
  INSUFFICIENT_SAMPLES: "Not enough telemetry samples to estimate a rate.",
  STALE_DATA: "Latest telemetry is too old to estimate a current rate.",
  NO_OPERATING_TIME: "No shift schedule configured, so operating hours are zero.",
  NO_PRODUCTION_DELTA: "Counters did not advance during the estimation window.",
};

/** Handles unknown backend enum values defensively instead of crashing on a future contract. */
function insufficientReasonText(reason: InsufficientReason | undefined): string {
  if (reason && reason in INSUFFICIENT_REASON_TEXT) {
    return INSUFFICIENT_REASON_TEXT[reason];
  }
  return "Counter-rate estimate is unavailable.";
}

const BASIS_LABELS: Record<CalculationBasis, string> = {
  ROLLING_30_DAY: "Rolling 30 days",
  FULL_HISTORY: "Full history",
};

function basisLabel(basis: CalculationBasis | undefined): string {
  return (basis && BASIS_LABELS[basis]) || "Unknown basis";
}

const numberFormat = new Intl.NumberFormat("en", { maximumFractionDigits: 2 });

/**
 * Presentational view of the counter-rate estimate and per-installation depletion
 * projections (story 8-6). The backend owns every calculation; this card only renders
 * returned values and explicit insufficient-data reasons.
 */
export function CounterRateProjectionView({
  data,
  isLoading = false,
  error = false,
}: {
  readonly data?: MachineSparepartProjectionsView;
  readonly isLoading?: boolean;
  readonly error?: boolean;
}) {
  const hasKnownData = Boolean(data);
  const rateAvailable = data?.rateAvailable === true;
  const projections = data?.projections ?? [];

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="font-medium text-base">Counter Rate &amp; Depletion</CardTitle>
        <CardDescription>
          Estimated counting speed per operating hour with shift-aware depletion projections.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading ? (
          <div aria-hidden="true" className="space-y-2">
            <Skeleton className="h-10 w-40" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-full" />
          </div>
        ) : null}
        {!isLoading && error ? (
          <p className="text-destructive text-xs">
            {hasKnownData
              ? "Unable to refresh projections. Showing last known estimates."
              : "Unable to load projections."}
          </p>
        ) : null}
        {!isLoading && !error && !hasKnownData ? (
          <p className="text-muted-foreground text-xs">No projection data available.</p>
        ) : null}
        {!isLoading && hasKnownData ? (
          <>
            {!rateAvailable ? (
              <p className="text-amber-700 text-xs dark:text-amber-400" role="status">
                {insufficientReasonText(data?.insufficientReason)}
              </p>
            ) : (
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="font-semibold text-3xl tabular-nums">
                  {numberFormat.format(data?.ratePerOperatingHour ?? 0)}
                </span>
                <span className="text-muted-foreground text-sm">counters/op-hour</span>
                <Badge variant="outline">{basisLabel(data?.calculationBasis)}</Badge>
              </div>
            )}
            <div className="space-y-1 text-xs">
              {data?.windowStartAt ? (
                <EvidenceRow label="Window start" value={formatDateTimeUtc(data.windowStartAt)} />
              ) : null}
              {data?.windowEndAt ? (
                <EvidenceRow label="Window end" value={formatDateTimeUtc(data.windowEndAt)} />
              ) : null}
              {data?.firstSampleAt ? (
                <EvidenceRow label="First sample" value={formatDateTimeUtc(data.firstSampleAt)} />
              ) : null}
              {data?.lastSampleAt ? (
                <EvidenceRow label="Last sample" value={formatDateTimeUtc(data.lastSampleAt)} />
              ) : null}
              {data?.dailyOperatingHours != null ? (
                <EvidenceRow
                  label="Operating time"
                  value={`${numberFormat.format(data.dailyOperatingHours)} h/day (${data.shiftSource ?? "unknown"} schedule)`}
                />
              ) : null}
            </div>
            {projections.length > 0 ? (
              <ul className="divide-y rounded-md border">
                {projections.map((projection) => (
                  <InstallationRow key={projection.installationId} projection={projection} />
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground text-xs">No sparepart installations registered.</p>
            )}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function EvidenceRow({ label, value }: { readonly label: string; readonly value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <span className="min-w-0 break-words text-right font-medium">{value}</span>
    </div>
  );
}

function InstallationRow({ projection }: { readonly projection: InstallationProjection }) {
  return (
    <li className="space-y-1 p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium text-sm">{projection.functionName ?? "Installation"}</span>
        {projection.available ? (
          <Badge
            variant="outline"
            className="border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400"
          >
            Projected
          </Badge>
        ) : (
          <Badge variant="outline" className="border-transparent bg-muted/60 text-muted-foreground">
            Unavailable
          </Badge>
        )}
      </div>
      {projection.available ? (
        <div className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 text-xs">
          <span className="text-muted-foreground">Remaining counters</span>
          <span className="text-right font-medium tabular-nums">
            {numberFormat.format(projection.remainingCounters ?? 0)}
          </span>
          <span className="text-muted-foreground">Projected depletion</span>
          <span className="text-right font-medium">
            {projection.projectedDepletionAt ? formatDateTimeUtc(projection.projectedDepletionAt) : "—"}
          </span>
          {projection.consumptionDuringLeadTime != null ? (
            <>
              <span className="text-muted-foreground">Lead-time consumption</span>
              <span className="text-right font-medium tabular-nums">
                ≈{numberFormat.format(projection.consumptionDuringLeadTime)} counters during{" "}
                {numberFormat.format(projection.leadTimeHours ?? 0)} op-hour lead time
              </span>
            </>
          ) : null}
        </div>
      ) : (
        <p className="text-muted-foreground text-xs">{insufficientReasonText(projection.reason)}</p>
      )}
    </li>
  );
}

/**
 * Self-fetching wrapper used by the machine hub overview; the presentational
 * {@link CounterRateProjectionView} stays fixture-testable without network mocks.
 */
export function CounterRateProjectionCard({ machineId }: { readonly machineId?: string | null }) {
  const query = useGetMachineSparepartProjections(machineId ?? "", {
    query: { enabled: Boolean(machineId), staleTime: 60_000 },
  });

  return <CounterRateProjectionView data={query.data?.data} isLoading={query.isLoading} error={query.isError} />;
}
