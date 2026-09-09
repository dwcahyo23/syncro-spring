"use client";

import Link from "next/link";

import { Activity, AlertTriangle, Bell, CircleSlash, Gauge, Wrench } from "lucide-react";
import { useTranslations } from "next-intl";

import { StatusBadge } from "@/components/syncro/status-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { type MachineDashboardRow, useMachineDashboard } from "@/features/machines/hooks/use-machine-dashboard";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import type { TelemetryFreshnessState } from "@/features/telemetry/types";

function toFreshnessState(state: string | null | undefined): TelemetryFreshnessState | "UNKNOWN" {
  if (state === "ONLINE") {
    return "ONLINE";
  }
  if (state === "OFFLINE") {
    return "OFFLINE";
  }
  if (state === "STALE") {
    return "STALE";
  }
  return "UNKNOWN";
}

/**
 * Machine dashboard (story 14-1, FR-170): per-machine status, telemetry freshness
 * (stale-aware; null telemetry = "Unknown"), open workorder/alert counts and lifetime
 * risk. Every count is backend-computed; this page only renders. UX-DR-019 states:
 * loading, error, empty, stale and forbidden (plant-scope guard) are each represented.
 */
export function MachineDashboardPageContent() {
  const t = useTranslations("machineDashboard");
  const tc = useTranslations("common");
  const { scope, activePlantId, loadError } = usePlantScope();
  const plantId = activePlantId && activePlantId !== "all" ? activePlantId : undefined;
  const isEnabled = Boolean(scope);
  const query = useMachineDashboard(plantId, isEnabled);

  if (loadError) {
    return <MachineDashboardShell>{t("plantScopeError")}</MachineDashboardShell>;
  }

  if (!isEnabled) {
    return (
      <MachineDashboardShell>
        <Skeleton className="h-32 w-full" />
      </MachineDashboardShell>
    );
  }

  if (scope?.mode === "EMPTY") {
    return <MachineDashboardShell>{t("noPlantsAssigned")}</MachineDashboardShell>;
  }

  return (
    <MachineDashboardShell>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground text-sm">{t("hint")}</p>
        <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isLoading}>
          <Activity aria-hidden="true" className={query.isFetching ? "animate-spin" : undefined} />
          {tc("refresh")}
        </Button>
      </div>

      {query.isLoading && <MachineDashboardSkeleton />}

      {query.isError && (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-8">
            <p className="text-muted-foreground text-sm">{t("loadFailed")}</p>
            <Button variant="outline" size="sm" onClick={() => void query.refetch()}>
              {tc("retry")}
            </Button>
          </CardContent>
        </Card>
      )}

      {!query.isLoading && !query.isError && (query.data?.items ?? []).length === 0 && (
        <Empty className="min-h-40">
          <EmptyMedia variant="icon">
            <Gauge aria-hidden="true" />
          </EmptyMedia>
          <EmptyTitle>{t("emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("emptyDescription")}</EmptyDescription>
        </Empty>
      )}

      {!query.isLoading && !query.isError && (query.data?.items ?? []).length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {(query.data?.items ?? []).map((machine) => (
            <MachineCard key={machine.machineId} machine={machine} />
          ))}
        </div>
      )}
    </MachineDashboardShell>
  );
}

function MachineCard({ machine }: { machine: MachineDashboardRow }) {
  const t = useTranslations("machineDashboard");
  const tc = useTranslations("common");
  const isStale =
    machine.telemetryFreshness?.freshnessState === "STALE" ||
    machine.telemetryFreshness?.freshnessState === "OFFLINE" ||
    machine.telemetryFreshness == null;
  const atRisk = machine.lifetimeRisk.status === "AT_RISK";

  const freshness = machine.telemetryFreshness ? toFreshnessState(machine.telemetryFreshness.freshnessState) : null;

  return (
    <Card className="flex flex-col gap-3">
      <CardHeader className="pb-2">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <CardTitle className="truncate text-base" title={machine.name ?? machine.code}>
              <Link href={`/master-data/machines/${machine.code}`} className="hover:underline">
                {machine.name ?? machine.code}
              </Link>
            </CardTitle>
            <CardDescription className="truncate">
              {machine.code}
              {machine.machineGroupName ? ` · ${machine.machineGroupName}` : ""}
            </CardDescription>
            <CardDescription className="truncate">
              {machine.plantCode} · {machine.plantName}
            </CardDescription>
          </div>
          <Badge variant={machine.status === "ACTIVE" ? "secondary" : "outline"}>
            {t.has(`status.${machine.status}`) ? t(`status.${machine.status}`) : machine.status}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="grid grid-cols-2 gap-3">
        <div className="flex items-center justify-between gap-2 rounded-lg border p-2">
          <span className="flex items-center gap-1.5 text-muted-foreground text-xs">
            <Wrench aria-hidden="true" className="size-3.5" />
            {t("openWorkorders")}
          </span>
          <span className="font-semibold tabular-nums">{machine.openWorkOrderCount}</span>
        </div>
        <div className="flex items-center justify-between gap-2 rounded-lg border p-2">
          <span className="flex items-center gap-1.5 text-muted-foreground text-xs">
            <Bell aria-hidden="true" className="size-3.5" />
            {t("openAlerts")}
          </span>
          <span className="font-semibold tabular-nums">{machine.openAlertCount}</span>
        </div>

        <div className="col-span-2 flex items-center justify-between gap-2 rounded-lg border p-2">
          <span className="flex items-center gap-1.5 text-muted-foreground text-xs">
            <CircleSlash aria-hidden="true" className="size-3.5" />
            {t("telemetry")}
          </span>
          {freshness === "UNKNOWN" ? (
            <Badge variant="outline" aria-label={t("telemetryUnknownAria")}>
              <CircleSlash aria-hidden="true" />
              {tc("unknown")}
            </Badge>
          ) : freshness != null ? (
            <StatusBadge freshness={freshness as TelemetryFreshnessState} />
          ) : (
            <Badge variant="outline" aria-label={t("telemetryUnknownAria")}>
              <CircleSlash aria-hidden="true" />
              {tc("unknown")}
            </Badge>
          )}
        </div>

        <div
          className={`col-span-2 flex items-center justify-between gap-2 rounded-lg border p-2 ${
            atRisk ? "border-destructive/40 bg-destructive/5" : ""
          }`}
        >
          <span className="flex items-center gap-1.5 text-muted-foreground text-xs">
            <AlertTriangle aria-hidden="true" className="size-3.5" />
            {t("lifetimeRisk")}
          </span>
          {machine.lifetimeRisk.status === "NO_DATA" ? (
            <span className="text-muted-foreground text-xs">{t("noData")}</span>
          ) : (
            <span className="flex items-center gap-2">
              <span className="font-semibold tabular-nums">
                {machine.lifetimeRisk.maxConsumedPercentage != null
                  ? `${Number(machine.lifetimeRisk.maxConsumedPercentage).toFixed(1)}%`
                  : tc("notAvailable")}
              </span>
              {machine.lifetimeRisk.thresholdPercentage != null ? (
                <span className="text-muted-foreground text-xs">/ {machine.lifetimeRisk.thresholdPercentage}%</span>
              ) : null}
              {atRisk ? <Badge variant="destructive">{t("atRisk")}</Badge> : <Badge variant="outline">{t("ok")}</Badge>}
            </span>
          )}
        </div>

        {isStale && (
          <p className="col-span-2 flex items-center gap-1.5 text-muted-foreground text-xs" role="status">
            <CircleSlash aria-hidden="true" className="size-3.5" />
            {machine.status === "ACTIVE" ? t("staleActive") : t("staleInactive")}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function MachineDashboardSkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {["s1", "s2", "s3", "s4", "s5", "s6"].map((key) => (
        <Skeleton key={key} className="h-56 w-full" />
      ))}
    </div>
  );
}

function MachineDashboardShell({ children }: { children: React.ReactNode }) {
  const t = useTranslations("machineDashboard");
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <header className="space-y-1">
        <p className="font-medium text-muted-foreground text-sm">Syncro</p>
        <h1 className="font-semibold text-3xl tracking-tight">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>
      {children}
    </main>
  );
}
