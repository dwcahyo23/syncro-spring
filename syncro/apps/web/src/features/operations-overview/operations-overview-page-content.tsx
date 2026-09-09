"use client";

import Link from "next/link";

import { AlertTriangle, Bell } from "lucide-react";
import { useTranslations } from "next-intl";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertStatusBadge } from "@/features/alerts/alert-status-badge";
import { NotificationStatePill } from "@/features/alerts/notification-state-pill";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { useListAlerts } from "@/lib/api/generated/syncro";

/** Short "Xm/Xh/Xd ago" buckets in the active locale; keys live in `operationsOverview`. */
function useTimeAgo() {
  const t = useTranslations("operationsOverview");
  return (dateStr?: string): string => {
    if (!dateStr) return t("noDate");
    const diffMs = Date.now() - new Date(dateStr).getTime();
    const mins = Math.floor(diffMs / 60_000);
    if (mins < 60) return t("minutesAgo", { count: mins });
    const hours = Math.floor(mins / 60);
    if (hours < 24) return t("hoursAgo", { count: hours });
    return t("daysAgo", { count: Math.floor(hours / 24) });
  };
}

export function OperationsOverviewPageContent() {
  const t = useTranslations("operationsOverview");
  const tc = useTranslations("common");
  const timeAgo = useTimeAgo();
  const { scope, activePlantId, loadError } = usePlantScope();

  const plantId = activePlantId && activePlantId !== "all" ? activePlantId : undefined;

  const openAlertsQuery = useListAlerts(
    { status: "OPEN", plantId, page: 0, size: 5, sort: "createdAt,asc" },
    { query: { enabled: !!scope, staleTime: 30_000 } },
  );

  // --- guard states ---

  if (loadError) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <header className="space-y-1">
          <p className="font-medium text-muted-foreground text-sm">Syncro</p>
          <h1 className="font-semibold text-3xl tracking-tight">{t("title")}</h1>
        </header>
        <Card>
          <CardHeader>
            <CardTitle>{t("plantScopeTitle")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-muted-foreground text-sm">{t("plantScopeDescription")}</p>
          </CardContent>
        </Card>
      </main>
    );
  }

  if (!scope) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <header className="space-y-1">
          <p className="font-medium text-muted-foreground text-sm">Syncro</p>
          <h1 className="font-semibold text-3xl tracking-tight">{t("title")}</h1>
        </header>
        <Skeleton className="h-32 w-full" />
      </main>
    );
  }

  if (scope.mode === "EMPTY") {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <header className="space-y-1">
          <p className="font-medium text-muted-foreground text-sm">Syncro</p>
          <h1 className="font-semibold text-3xl tracking-tight">{t("title")}</h1>
        </header>
        <Card>
          <CardHeader>
            <CardTitle>{t("noPlantsTitle")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-muted-foreground text-sm">{t("noPlantsDescription")}</p>
          </CardContent>
        </Card>
      </main>
    );
  }

  const openAlerts = openAlertsQuery.data?.data?.items ?? [];
  const openAlertsTotal = openAlertsQuery.data?.data?.totalElements ?? 0;
  const isLoadingAlerts = openAlertsQuery.isLoading;
  const isErrorAlerts = openAlertsQuery.isError;

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      {/* Page header */}
      <header className="space-y-1">
        <p className="font-medium text-muted-foreground text-sm">Syncro</p>
        <h1 className="font-semibold text-3xl tracking-tight">{t("title")}</h1>
        <p className="text-muted-foreground">{t("subtitle")}</p>
      </header>

      {/* Summary metric bar */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Link href="/alerts?status=OPEN" className="block">
          <Card className="transition-colors hover:border-foreground/20">
            <CardHeader className="pb-2">
              <CardTitle className="font-medium text-muted-foreground text-sm">{t("openAlerts")}</CardTitle>
            </CardHeader>
            <CardContent>
              {isLoadingAlerts ? (
                <Skeleton className="h-8 w-12" />
              ) : (
                <p className="font-bold text-2xl tabular-nums">{openAlertsTotal}</p>
              )}
            </CardContent>
          </Card>
        </Link>
        {/* Placeholder metrics — Epic 5+ will fill these */}
        <Card className="opacity-50">
          <CardHeader className="pb-2">
            <CardTitle className="font-medium text-muted-foreground text-sm">{t("activeMachines")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="font-bold text-2xl text-muted-foreground">—</p>
          </CardContent>
        </Card>
        <Card className="opacity-50">
          <CardHeader className="pb-2">
            <CardTitle className="font-medium text-muted-foreground text-sm">{t("staleTelemetry")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="font-bold text-2xl text-muted-foreground">—</p>
          </CardContent>
        </Card>
        <Card className="opacity-50">
          <CardHeader className="pb-2">
            <CardTitle className="font-medium text-muted-foreground text-sm">{t("healthStatus")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="font-bold text-2xl text-muted-foreground">—</p>
          </CardContent>
        </Card>
      </div>

      {/* Alerts Requiring Action */}
      <section aria-labelledby="alerts-section-heading">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="alerts-section-heading" className="flex items-center gap-2 font-semibold text-base">
            <AlertTriangle className="h-4 w-4 text-destructive" aria-hidden="true" />
            {t("alertsSection")}
          </h2>
          <Link href="/alerts" className="text-muted-foreground text-sm hover:text-foreground">
            {t("viewAllAlerts")}
          </Link>
        </div>

        {isLoadingAlerts && (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-14 w-full" />
            ))}
          </div>
        )}

        {isErrorAlerts && (
          <Card>
            <CardContent className="flex flex-col items-center gap-3 py-6">
              <p className="text-muted-foreground text-sm">{t("loadFailed")}</p>
              <button
                type="button"
                className="rounded-md bg-primary px-3 py-1.5 font-medium text-primary-foreground text-sm hover:bg-primary/90"
                onClick={() => void openAlertsQuery.refetch()}
              >
                {tc("retry")}
              </button>
            </CardContent>
          </Card>
        )}

        {!isLoadingAlerts && !isErrorAlerts && openAlerts.length === 0 && (
          <Card>
            <CardContent className="py-8 text-center">
              <Bell className="mx-auto mb-2 h-6 w-6 text-muted-foreground" aria-hidden="true" />
              <p className="font-medium text-sm">{t("emptyTitle")}</p>
              <p className="mt-1 text-muted-foreground text-xs">{t("emptyDescription")}</p>
            </CardContent>
          </Card>
        )}

        {!isLoadingAlerts && !isErrorAlerts && openAlerts.length > 0 && (
          <Card>
            <CardContent className="p-0">
              <ul className="divide-y">
                {openAlerts.map((item) => (
                  <li key={item.id} className={alertRowBorder(item.status ?? "")}>
                    <Link
                      href={`/alerts/${item.id}`}
                      className="flex flex-col gap-1 py-3 pr-4 pl-3 hover:bg-muted/50 sm:flex-row sm:items-center sm:justify-between"
                      aria-label={t("rowAria", {
                        sparepart: item.sparepartName ?? item.sparepartCode ?? "",
                        machine: item.machineCode ?? "",
                      })}
                    >
                      <div className="flex items-center gap-3">
                        <AlertStatusBadge status={item.status} />
                        <NotificationStatePill summary={item.notificationSummary} />
                        <div>
                          <p className="font-medium text-sm">
                            {item.machineCode}
                            {item.machineName ? ` — ${item.machineName}` : ""}
                          </p>
                          <p className="text-muted-foreground text-xs">
                            {item.sparepartName ?? item.sparepartCode} · {item.functionName}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-4 pl-9 text-muted-foreground text-xs sm:pl-0">
                        <span className="tabular-nums">
                          {item.consumedPercentageSnapshot != null
                            ? t("consumed", { percent: Number(item.consumedPercentageSnapshot).toFixed(1) })
                            : t("noSnapshot")}
                          {item.thresholdPercentage != null ? (
                            <>
                              <span className="mx-1">·</span>
                              {t("threshold", { percent: item.thresholdPercentage })}
                            </>
                          ) : null}
                        </span>
                        <span className="hidden sm:inline">{timeAgo(item.createdAt)}</span>
                      </div>
                    </Link>
                  </li>
                ))}
              </ul>
              {openAlertsTotal > 5 && (
                <div className="border-t px-4 py-3 text-center">
                  <Link href="/alerts?status=OPEN" className="text-muted-foreground text-sm hover:text-foreground">
                    {t("moreAlerts", { count: openAlertsTotal - 5 })}
                  </Link>
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </section>
    </main>
  );
}

/** Alert row left-border tint matching the status badge. */
function alertRowBorder(status: string): string {
  switch (status) {
    case "OPEN":
      return "border-l-2 border-l-[var(--syncro-status-warning-border)]";
    case "ACKNOWLEDGED":
      return "border-l-2 border-l-[var(--syncro-status-info-border)]";
    case "RESOLVED":
      return "border-l-2 border-l-[var(--syncro-status-healthy-border)]";
    default:
      return "border-l-2 border-l-transparent";
  }
}
