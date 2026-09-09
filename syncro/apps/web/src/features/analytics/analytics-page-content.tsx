"use client";

import { useState } from "react";

import { Activity, Gauge, RefreshCw, UserRound } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { MonthPicker } from "@/components/month-picker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { KpiTargetDialog } from "@/features/analytics/components/kpi-target-dialog";
import {
  type KpiMaterializedResponse,
  type KpiMaterializedType,
  type KpiTargetStatus,
  useKpiMaterialized,
} from "@/features/analytics/hooks/use-kpi-materialized";
import { useMtbfMttr } from "@/features/analytics/hooks/use-mtbf-mttr";
import { useTechnicianKpi } from "@/features/analytics/hooks/use-technician-kpi";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";

/** Maps each materialized KPI type to its message-key fragment under analytics.monthly. */
const MONTHLY_TYPE_KEYS = {
  mtbf: "mtbf",
  mttr: "mttr",
  mar: "mar",
  "pm-completion": "pmCompletion",
  technician: "technician",
  breakdown: "breakdown",
} as const satisfies Record<KpiMaterializedType, string>;

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

function formatDays(value: number | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "—";
  }
  return `${value.toFixed(2)} d`;
}

function formatMinutes(value: number | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "—";
  }
  return `${value.toFixed(0)} min`;
}

function formatCount(value: number | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "—";
  }
  return String(value);
}

/**
 * Analytics dashboards (story 14-2, FR-173/FR-174): MTBF/MTTR reliability cards and the
 * technician KPI table. Story 20-2 adds the "Monthly KPI" tab consuming the materialized
 * monthly rows with the backend-computed actual-vs-target verdict. Every value is
 * backend-computed; this page only renders. UX-DR-019 states: loading, error, empty,
 * insufficient-data (explicit badge, never a fabricated value), and stale (non-color-only
 * text label). Freshness window and computedAt render from the backend payload.
 */
export function AnalyticsPageContent() {
  const t = useTranslations("analytics");
  const tc = useTranslations("common");
  const format = useFormatter();
  const { scope, activePlantId, loadError } = usePlantScope();
  const plantId = activePlantId && activePlantId !== "all" ? activePlantId : undefined;
  const isEnabled = Boolean(scope);

  const mtbfMttrQuery = useMtbfMttr(plantId, isEnabled);
  const kpiQuery = useTechnicianKpi(plantId, isEnabled);

  const [activeTab, setActiveTab] = useState<"mtbf" | "kpi" | "monthly">("mtbf");

  // Monthly KPI tab (story 20-2): calendar month, normalized to first-of-month for the
  // materialized endpoint. Default = current month (client component — no SSR mismatch).
  const [monthValue, setMonthValue] = useState(() => {
    const now = new Date();
    return { month: now.getMonth(), year: now.getFullYear() };
  });
  const monthKey = `${monthValue.year}-${String(monthValue.month + 1).padStart(2, "0")}-01`;
  const monthlyEnabled = isEnabled && activeTab === "monthly";

  const mtbfMonthly = useKpiMaterialized("mtbf", monthKey, plantId, monthlyEnabled);
  const mttrMonthly = useKpiMaterialized("mttr", monthKey, plantId, monthlyEnabled);
  const marMonthly = useKpiMaterialized("mar", monthKey, plantId, monthlyEnabled);
  const pmMonthly = useKpiMaterialized("pm-completion", monthKey, plantId, monthlyEnabled);
  const technicianMonthly = useKpiMaterialized("technician", monthKey, plantId, monthlyEnabled);
  const breakdownMonthly = useKpiMaterialized("breakdown", monthKey, plantId, monthlyEnabled);
  const monthlyQueries = [mtbfMonthly, mttrMonthly, marMonthly, pmMonthly, technicianMonthly, breakdownMonthly];

  // Backend timestamps render in the active locale with the previous en-US shape
  // ("Aug 29, 2026, 08:00"); an unparseable value falls back to the raw string.
  function formatDateTime(iso: string): string {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return iso;
    }
    return format.dateTime(date, {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  }

  if (loadError) {
    return <AnalyticsShell>{t("scopeUnavailable")}</AnalyticsShell>;
  }

  if (!isEnabled) {
    return (
      <AnalyticsShell>
        <Skeleton className="h-32 w-full" />
      </AnalyticsShell>
    );
  }

  if (scope?.mode === "EMPTY") {
    return <AnalyticsShell>{t("scopeEmpty")}</AnalyticsShell>;
  }

  const mtbfData = mtbfMttrQuery.data;
  const kpiData = kpiQuery.data;
  const plantLabel = (id: string): string => {
    const plant = scope?.availablePlants.find((p) => p.id === id);
    return plant ? plant.code : `${id.slice(0, 8)}…`;
  };

  return (
    <AnalyticsShell>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-1 rounded-lg border bg-muted/40 p-1">
          <TabButton active={activeTab === "mtbf"} onClick={() => setActiveTab("mtbf")} label={t("tabs.mtbf")} />
          <TabButton active={activeTab === "kpi"} onClick={() => setActiveTab("kpi")} label={t("tabs.kpi")} />
          <TabButton
            active={activeTab === "monthly"}
            onClick={() => setActiveTab("monthly")}
            label={t("tabs.monthly")}
          />
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            void mtbfMttrQuery.refetch();
            void kpiQuery.refetch();
            for (const query of monthlyQueries) {
              void query.refetch();
            }
          }}
          disabled={mtbfMttrQuery.isLoading || kpiQuery.isLoading || monthlyQueries.some((q) => q.isLoading)}
        >
          <RefreshCw
            aria-hidden="true"
            className={
              mtbfMttrQuery.isFetching || kpiQuery.isFetching || monthlyQueries.some((q) => q.isFetching)
                ? "animate-spin"
                : undefined
            }
          />
          {tc("refresh")}
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
                <p className="text-muted-foreground text-sm">{t("loadFailedMtbfMttr")}</p>
                <Button variant="outline" size="sm" onClick={() => void mtbfMttrQuery.refetch()}>
                  {tc("retry")}
                </Button>
              </CardContent>
            </Card>
          )}

          {!mtbfMttrQuery.isLoading && !mtbfMttrQuery.isError && mtbfData && (
            <>
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                <KpiCard
                  label={t("mtbfLabel")}
                  value={formatHours(mtbfData.mtbf.valueHours)}
                  status={mtbfData.mtbf.status}
                  statusLabel={
                    mtbfData.mtbf.status === "AVAILABLE"
                      ? t("breakdownCount", { count: mtbfData.mtbf.workorderCount })
                      : t("targetStatus.INSUFFICIENT_DATA")
                  }
                  icon={<Gauge aria-hidden="true" />}
                />
                <KpiCard
                  label={t("mttrLabel")}
                  value={formatHours(mtbfData.mttr.valueHours)}
                  status={mtbfData.mttr.status}
                  statusLabel={
                    mtbfData.mttr.status === "AVAILABLE"
                      ? t("completedCount", { count: mtbfData.mttr.workorderCount })
                      : t("targetStatus.INSUFFICIENT_DATA")
                  }
                  icon={<Activity aria-hidden="true" />}
                />
              </div>

              <Card>
                <CardHeader>
                  <CardTitle>{t("reliabilityTitle")}</CardTitle>
                  <CardDescription>{t("reliabilityDescription")}</CardDescription>
                </CardHeader>
                <CardContent className="space-y-2 text-sm">
                  <p className="text-muted-foreground">
                    {t("windowLabel")}{" "}
                    <span className="font-medium text-foreground">{formatDateTime(mtbfData.windowFrom)}</span>{" "}
                    {t("windowTo")}{" "}
                    <span className="font-medium text-foreground">{formatDateTime(mtbfData.windowTo)}</span>
                  </p>
                  <p className="text-muted-foreground">
                    {t("computedLabel")}{" "}
                    <span className="font-medium text-foreground">{formatDateTime(mtbfData.computedAt)}</span>
                  </p>
                  {mtbfData.stale && (
                    <p className="status-icon-warning flex items-center gap-2" role="status">
                      <Badge variant="outline">{t("staleBadge")}</Badge>
                      {t("staleBody")}
                    </p>
                  )}
                  {!mtbfData.stale && mtbfData.cacheAgeMs !== null && mtbfData.cacheAgeMs !== undefined && (
                    <p className="text-muted-foreground">
                      {t("cacheServed", { seconds: String(Math.round(mtbfData.cacheAgeMs / 1000)) })}
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
                <p className="text-muted-foreground text-sm">{t("loadFailedTechnician")}</p>
                <Button variant="outline" size="sm" onClick={() => void kpiQuery.refetch()}>
                  {tc("retry")}
                </Button>
              </CardContent>
            </Card>
          )}

          {/* Stale indicator renders regardless of list emptiness — the freshness signal
              is independent of whether any technicians matched. */}
          {!kpiQuery.isLoading && !kpiQuery.isError && kpiData?.stale && (
            <p className="status-icon-warning flex items-center gap-2" role="status">
              <Badge variant="outline">{t("staleBadge")}</Badge>
              {t("staleBody")}
            </p>
          )}

          {!kpiQuery.isLoading && !kpiQuery.isError && kpiData && kpiData.technicians.length === 0 && (
            <Empty className="min-h-40">
              <EmptyMedia variant="icon">
                <UserRound aria-hidden="true" />
              </EmptyMedia>
              <EmptyTitle>{t("noTechniciansTitle")}</EmptyTitle>
              <EmptyDescription>{t("noTechniciansDescription")}</EmptyDescription>
            </Empty>
          )}

          {!kpiQuery.isLoading && !kpiQuery.isError && kpiData && kpiData.technicians.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle>{t("technicianTableTitle")}</CardTitle>
                <CardDescription>{t("technicianTableDescription")}</CardDescription>
              </CardHeader>
              <CardContent>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("table.technician")}</TableHead>
                      <TableHead>{t("table.completed")}</TableHead>
                      <TableHead>{t("table.avgMttr")}</TableHead>
                      <TableHead>{t("table.onTimePct")}</TableHead>
                      <TableHead>{t("table.ratings")}</TableHead>
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
                            <span className="text-muted-foreground">{t("noRatings")}</span>
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

      {activeTab === "monthly" && (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <span className="text-muted-foreground text-sm">{t("monthly.monthLabel")}</span>
              <MonthPicker value={monthValue} onChange={setMonthValue} />
            </div>
            <KpiTargetDialog month={monthKey} />
          </div>

          <MonthlyKpiSection
            titleKey={MONTHLY_TYPE_KEYS.mtbf}
            entityHeaderKey="machine"
            query={mtbfMonthly}
            rows={(data) =>
              data.mtbfRows.map((row) => ({
                key: row.machineId,
                entity: `${plantLabel(row.plantId)} / ${row.machineId.slice(0, 8)}…`,
                actual: formatDays(row.mtbfDays),
                target: formatDays(row.targetValue),
                status: row.targetStatus,
              }))
            }
          />
          <MonthlyKpiSection
            titleKey={MONTHLY_TYPE_KEYS.mttr}
            entityHeaderKey="plant"
            query={mttrMonthly}
            rows={(data) =>
              data.mttrRows.map((row) => ({
                key: row.plantId,
                entity: plantLabel(row.plantId),
                actual: formatMinutes(row.actualWorkingMinutes ?? row.wallClockMinutes),
                target: formatMinutes(row.targetValue),
                status: row.targetStatus,
              }))
            }
          />
          <MonthlyKpiSection
            titleKey={MONTHLY_TYPE_KEYS.breakdown}
            entityHeaderKey="plant"
            query={breakdownMonthly}
            rows={(data) =>
              data.breakdownRows.map((row) => ({
                key: row.plantId,
                entity: plantLabel(row.plantId),
                actual: formatCount(row.count),
                target: formatCount(row.targetValue),
                status: row.targetStatus,
              }))
            }
          />
          <MonthlyKpiSection
            titleKey={MONTHLY_TYPE_KEYS.mar}
            entityHeaderKey="plant"
            query={marMonthly}
            rows={(data) =>
              data.marRows.map((row) => ({
                key: row.plantId,
                entity: plantLabel(row.plantId),
                actual: formatPct(row.marPercent),
                target: formatPct(row.targetValue),
                status: row.targetStatus,
              }))
            }
          />
          <MonthlyKpiSection
            titleKey={MONTHLY_TYPE_KEYS["pm-completion"]}
            entityHeaderKey="plant"
            query={pmMonthly}
            rows={(data) =>
              data.pmCompletionRows.map((row) => ({
                key: row.plantId,
                entity: plantLabel(row.plantId),
                actual: formatPct(row.completionRate),
                target: formatPct(row.targetValue),
                status: row.targetStatus,
              }))
            }
          />
          <MonthlyKpiSection
            titleKey={MONTHLY_TYPE_KEYS.technician}
            entityHeaderKey="technician"
            query={technicianMonthly}
            rows={(data) =>
              data.technicianRows.map((row) => ({
                key: row.technicianId,
                entity: `${plantLabel(row.plantId)} / ${row.technicianId.slice(0, 8)}…`,
                actual: `${formatStars(row.averageRating)} · ${row.totalWo} WO · ${formatPct(row.firstTimeFixRate)}`,
                target: "—",
                status: row.targetStatus,
              }))
            }
          />
        </div>
      )}
    </AnalyticsShell>
  );
}

/**
 * One actual-vs-target section of the Monthly KPI tab (story 20-2). Renders only:
 * verdicts, insufficient-data and FAILED-refresh evidence come from the backend
 * response; no KPI math here. Copy resolves from analytics.monthly.<type>.*.
 */
function MonthlyKpiSection({
  titleKey,
  entityHeaderKey,
  query,
  rows,
}: {
  titleKey: string;
  entityHeaderKey: "machine" | "plant" | "technician";
  query: ReturnType<typeof useKpiMaterialized>;
  rows: (data: KpiMaterializedResponse) => MonthlyRowView[];
}) {
  const t = useTranslations("analytics");
  const tc = useTranslations("common");
  const title = t.has(`monthly.${titleKey}.title`) ? t(`monthly.${titleKey}.title`) : titleKey;
  const description = t.has(`monthly.${titleKey}.description`) ? t(`monthly.${titleKey}.description`) : "";
  const data = query.data;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading && <Skeleton className="h-24 w-full" />}

        {query.isError && (
          <div className="flex flex-col items-center gap-3 py-6">
            <p className="text-muted-foreground text-sm">{t("loadFailedSection", { title })}</p>
            <Button variant="outline" size="sm" onClick={() => void query.refetch()}>
              {tc("retry")}
            </Button>
          </div>
        )}

        {!query.isLoading && !query.isError && data && (
          <div className="space-y-3">
            {data.refresh?.status === "FAILED" && (
              <p className="status-icon-warning flex flex-wrap items-center gap-2" role="status">
                <Badge variant="destructive">{t("refreshFailedBadge")}</Badge>
                {t("refreshFailedBody")}
                {data.refresh.message ? ` (${data.refresh.message})` : ""}
              </p>
            )}

            {data.status === "INSUFFICIENT_DATA" ? (
              <div className="flex flex-col items-center gap-2 py-6">
                <Badge variant="outline">{t("targetStatus.INSUFFICIENT_DATA")}</Badge>
                <p className="text-muted-foreground text-sm">{t("insufficientBody", { title })}</p>
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>
                      {t.has(`monthly.entity.${entityHeaderKey}`)
                        ? t(`monthly.entity.${entityHeaderKey}`)
                        : entityHeaderKey}
                    </TableHead>
                    <TableHead>{t("monthly.actual")}</TableHead>
                    <TableHead>{t("monthly.target")}</TableHead>
                    <TableHead>{tc("status")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows(data).map((row) => (
                    <TableRow key={row.key}>
                      <TableCell className="font-medium">{row.entity}</TableCell>
                      <TableCell className="tabular-nums">{row.actual}</TableCell>
                      <TableCell className="tabular-nums">{row.target}</TableCell>
                      <TableCell>
                        <TargetStatusBadge status={row.status} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

interface MonthlyRowView {
  key: string;
  entity: string;
  actual: string;
  target: string;
  status: KpiTargetStatus;
}

function TargetStatusBadge({ status }: { status: KpiTargetStatus }) {
  const t = useTranslations("analytics");
  // Text label always renders — color/variant is decoration only (WCAG, epic-20 UX rule).
  // Verdict codes are the backend contract; display copy is keyed by code (rule 5).
  return (
    <Badge variant={status === "ON_TARGET" ? "default" : "secondary"}>
      {t.has(`targetStatus.${status}`) ? t(`targetStatus.${status}`) : status}
    </Badge>
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
          <Badge variant="secondary" aria-label={statusLabel}>
            {statusLabel}
          </Badge>
        ) : (
          <p className="text-muted-foreground text-xs">{statusLabel}</p>
        )}
      </CardContent>
    </Card>
  );
}

function AnalyticsShell({ children }: { children: React.ReactNode }) {
  const t = useTranslations("analytics");
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <header className="space-y-1">
        <p className="font-medium text-muted-foreground text-sm">{t("shellBrand")}</p>
        <h1 className="font-semibold text-3xl tracking-tight">{t("shellTitle")}</h1>
        <p className="text-muted-foreground">{t("shellSubtitle")}</p>
      </header>
      {children}
    </main>
  );
}
