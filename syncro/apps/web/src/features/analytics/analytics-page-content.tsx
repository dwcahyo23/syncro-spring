"use client";

import { useState } from "react";

import { Activity, Gauge, RefreshCw, UserRound } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useMtbfMttr } from "@/features/analytics/hooks/use-mtbf-mttr";
import { useTechnicianKpi } from "@/features/analytics/hooks/use-technician-kpi";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";

function formatHours(value: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  return `${value.toFixed(1)} h`;
}

function formatPct(value: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  // Sentinel fallback: Infinity (division by zero) or out-of-range values are never
  // rendered as a percentage — show "—" instead of a fabricated number.
  if (!Number.isFinite(value) || value < 0 || value > 100) {
    return "—";
  }
  return `${value.toFixed(1)}%`;
}

function formatStars(value: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  return `${value.toFixed(1)} / 5`;
}

/**
 * Analytics dashboards (story 14-2, FR-173/FR-174): MTBF/MTTR reliability cards and the
 * technician KPI table. Every value is backend-computed; this page only renders.
 * UX-DR-019 states: loading, error, empty, insufficient-data (explicit badge, never a
 * fabricated value), and stale (non-color-only text label). Freshness window and
 * computedAt render from the backend payload.
 */
export function AnalyticsPageContent() {
  const { scope, activePlantId, loadError } = usePlantScope();
  const plantId = activePlantId && activePlantId !== "all" ? activePlantId : undefined;
  const isEnabled = Boolean(scope);

  const mtbfMttrQuery = useMtbfMttr(plantId, isEnabled);
  const kpiQuery = useTechnicianKpi(plantId, isEnabled);

  const [activeTab, setActiveTab] = useState<"mtbf" | "kpi">("mtbf");

  if (loadError) {
    return <AnalyticsShell>Plant scope unavailable. Try again or contact your administrator.</AnalyticsShell>;
  }

  if (!isEnabled) {
    return (
      <AnalyticsShell>
        <Skeleton className="h-32 w-full" />
      </AnalyticsShell>
    );
  }

  if (scope?.mode === "EMPTY") {
    return <AnalyticsShell>No plants assigned to your account. Contact your administrator.</AnalyticsShell>;
  }

  const mtbfData = mtbfMttrQuery.data;
  const kpiData = kpiQuery.data;

  return (
    <AnalyticsShell>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-1 rounded-lg border bg-muted/40 p-1">
          <TabButton active={activeTab === "mtbf"} onClick={() => setActiveTab("mtbf")} label="MTBF / MTTR" />
          <TabButton active={activeTab === "kpi"} onClick={() => setActiveTab("kpi")} label="Technician KPI" />
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            void mtbfMttrQuery.refetch();
            void kpiQuery.refetch();
          }}
          disabled={mtbfMttrQuery.isLoading || kpiQuery.isLoading}
        >
          <RefreshCw
            aria-hidden="true"
            className={mtbfMttrQuery.isFetching || kpiQuery.isFetching ? "animate-spin" : undefined}
          />
          Refresh
        </Button>
      </div>

      {activeTab === "mtbf" && (
        <div className="space-y-6">
          {mtbfMttrQuery.isLoading && (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} className="h-28 w-full" />
              ))}
            </div>
          )}

          {mtbfMttrQuery.isError && (
            <Card>
              <CardContent className="flex flex-col items-center gap-3 py-8">
                <p className="text-muted-foreground text-sm">Failed to load the MTBF/MTTR analytics.</p>
                <Button variant="outline" size="sm" onClick={() => void mtbfMttrQuery.refetch()}>
                  Retry
                </Button>
              </CardContent>
            </Card>
          )}

          {!mtbfMttrQuery.isLoading && !mtbfMttrQuery.isError && mtbfData && (
            <>
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                <KpiCard
                  label="MTBF"
                  value={formatHours(mtbfData.mtbf.valueHours)}
                  status={mtbfData.mtbf.status}
                  statusLabel={
                    mtbfData.mtbf.status === "AVAILABLE"
                      ? `${mtbfData.mtbf.workorderCount} breakdowns`
                      : "Insufficient data"
                  }
                  icon={<Gauge aria-hidden="true" />}
                />
                <KpiCard
                  label="MTTR"
                  value={formatHours(mtbfData.mttr.valueHours)}
                  status={mtbfData.mttr.status}
                  statusLabel={
                    mtbfData.mttr.status === "AVAILABLE"
                      ? `${mtbfData.mttr.workorderCount} completed`
                      : "Insufficient data"
                  }
                  icon={<Activity aria-hidden="true" />}
                />
              </div>

              <Card>
                <CardHeader>
                  <CardTitle>Reliability window</CardTitle>
                  <CardDescription>Monthly rolling window keyed on the derived workorder stop time.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-2 text-sm">
                  <p className="text-muted-foreground">
                    Window: <span className="font-medium text-foreground">{formatDate(mtbfData.windowFrom)}</span> to{" "}
                    <span className="font-medium text-foreground">{formatDate(mtbfData.windowTo)}</span>
                  </p>
                  <p className="text-muted-foreground">
                    Computed: <span className="font-medium text-foreground">{formatDate(mtbfData.computedAt)}</span>
                  </p>
                  {mtbfData.stale && (
                    <p className="status-icon-warning flex items-center gap-2" role="status">
                      <Badge variant="outline">Stale</Badge>
                      Data may be out of date — refresh to recompute.
                    </p>
                  )}
                  {!mtbfData.stale && mtbfData.cacheAgeMs !== null && mtbfData.cacheAgeMs !== undefined && (
                    <p className="text-muted-foreground">
                      Served from cache ({Math.round(mtbfData.cacheAgeMs / 1000)}s old).
                    </p>
                  )}
                </CardContent>
              </Card>
            </>
          )}
        </div>
      )}

      {activeTab === "kpi" && (
        <div className="space-y-6">
          {kpiQuery.isLoading && <Skeleton className="h-64 w-full" />}

          {kpiQuery.isError && (
            <Card>
              <CardContent className="flex flex-col items-center gap-3 py-8">
                <p className="text-muted-foreground text-sm">Failed to load the technician KPI analytics.</p>
                <Button variant="outline" size="sm" onClick={() => void kpiQuery.refetch()}>
                  Retry
                </Button>
              </CardContent>
            </Card>
          )}

          {/* Stale indicator renders regardless of list emptiness — the freshness signal
              is independent of whether any technicians matched. */}
          {!kpiQuery.isLoading && !kpiQuery.isError && kpiData?.stale && (
            <p className="status-icon-warning flex items-center gap-2" role="status">
              <Badge variant="outline">Stale</Badge>
              Data may be out of date — refresh to recompute.
            </p>
          )}

          {!kpiQuery.isLoading && !kpiQuery.isError && kpiData && kpiData.technicians.length === 0 && (
            <Empty className="min-h-40">
              <EmptyMedia variant="icon">
                <UserRound aria-hidden="true" />
              </EmptyMedia>
              <EmptyTitle>No technicians in scope</EmptyTitle>
              <EmptyDescription>
                No workorders with an assigned or session technician exist in the current scope.
              </EmptyDescription>
            </Empty>
          )}

          {!kpiQuery.isLoading && !kpiQuery.isError && kpiData && kpiData.technicians.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>Technician KPI</CardTitle>
                <CardDescription>
                  Objective KPIs from completed workorders plus per-dimension average ratings (1–5 stars).
                </CardDescription>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Technician</TableHead>
                      <TableHead>Completed</TableHead>
                      <TableHead>Avg MTTR</TableHead>
                      <TableHead>On-time %</TableHead>
                      <TableHead>Ratings</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {kpiData.technicians.map((tech) => (
                      <TableRow key={tech.technicianId}>
                        <TableCell className="font-medium">{tech.technicianName}</TableCell>
                        <TableCell className="tabular-nums">{tech.completedCount}</TableCell>
                        <TableCell className="tabular-nums">{formatHours(tech.averageMttrHours)}</TableCell>
                        <TableCell className="tabular-nums">{formatPct(tech.onTimePercentage)}</TableCell>
                        <TableCell>
                          {tech.ratings.length === 0 ? (
                            <span className="text-muted-foreground">No ratings</span>
                          ) : (
                            <ul className="space-y-0.5">
                              {tech.ratings.map((r) => (
                                <li key={r.dimensionId} className="flex items-center gap-2 text-sm">
                                  <span className="text-muted-foreground">{r.dimensionLabel}:</span>
                                  <span className="font-medium tabular-nums">{formatStars(r.averageScore)}</span>
                                </li>
                              ))}
                            </ul>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </AnalyticsShell>
  );
}

function TabButton({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-md px-3 py-1.5 font-medium text-sm transition-colors ${
        active ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
      }`}
    >
      {label}
    </button>
  );
}

function KpiCard({
  label,
  value,
  status,
  statusLabel,
  icon,
}: {
  label: string;
  value: string;
  status: "AVAILABLE" | "INSUFFICIENT_DATA";
  statusLabel: string;
  icon: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 font-medium text-muted-foreground text-sm">
          {icon}
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="font-bold text-3xl tabular-nums">{value}</p>
        {status === "INSUFFICIENT_DATA" ? (
          <Badge variant="secondary" aria-label="Insufficient data">
            Insufficient data
          </Badge>
        ) : (
          <p className="text-muted-foreground text-xs">{statusLabel}</p>
        )}
      </CardContent>
    </Card>
  );
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function AnalyticsShell({ children }: { children: React.ReactNode }) {
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <header className="space-y-1">
        <p className="font-medium text-muted-foreground text-sm">Syncro</p>
        <h1 className="font-semibold text-3xl tracking-tight">Analytics</h1>
        <p className="text-muted-foreground">MTBF/MTTR reliability and technician KPIs, computed on the server.</p>
      </header>
      {children}
    </main>
  );
}
