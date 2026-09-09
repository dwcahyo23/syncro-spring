"use client";

import { CalendarCheck } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import { usePreventiveDashboard } from "@/features/preventive/hooks/use-preventive-dashboard";

/**
 * Preventive dashboard (story 14-1, FR-172): due/overdue KPI cards and upcoming schedule
 * list. Due/overdue derive from the server clock (never client time). Overdue items are
 * visibly distinct via label + featured badge, not color alone. Upcoming rows show the
 * machine name/code and program title (no truncated UUIDs). UX-DR-019 states.
 */
export function PreventiveDashboardPageContent() {
  const t = useTranslations("preventive");
  const tc = useTranslations("common");
  const { scope, activePlantId, loadError } = usePlantScope();
  const plantId = activePlantId && activePlantId !== "all" ? activePlantId : undefined;
  const isEnabled = Boolean(scope);
  const query = usePreventiveDashboard(plantId, isEnabled);

  if (loadError) {
    return <PreventiveDashboardShell>{t("dashboard.plantScopeError")}</PreventiveDashboardShell>;
  }

  if (!isEnabled) {
    return (
      <PreventiveDashboardShell>
        <Skeleton className="h-32 w-full" />
      </PreventiveDashboardShell>
    );
  }

  if (scope?.mode === "EMPTY") {
    return <PreventiveDashboardShell>{t("dashboard.noPlantsAssigned")}</PreventiveDashboardShell>;
  }

  const data = query.data;

  return (
    <PreventiveDashboardShell>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground text-sm">{t("dashboard.introLabel")}</p>
        <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isLoading}>
          <CalendarCheck aria-hidden="true" className={query.isFetching ? "animate-spin" : undefined} />
          {tc("refresh")}
        </Button>
      </div>

      {query.isLoading && (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-28 w-full" />
          ))}
        </div>
      )}

      {query.isError && (
        <Card>
          <CardContent className="flex flex-col items-center gap-3 py-8">
            <p className="text-muted-foreground text-sm">{t("dashboard.loadFailed")}</p>
            <Button variant="outline" size="sm" onClick={() => void query.refetch()}>
              {tc("retry")}
            </Button>
          </CardContent>
        </Card>
      )}

      {!query.isLoading && !query.isError && data && data.upcoming.length === 0 && (
        <Empty className="min-h-40">
          <EmptyMedia variant="icon">
            <CalendarCheck aria-hidden="true" />
          </EmptyMedia>
          <EmptyTitle>{t("dashboard.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("dashboard.emptyDescription")}</EmptyDescription>
        </Empty>
      )}

      {!query.isLoading && !query.isError && data && data.upcoming.length > 0 && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <KpiCard label={t("due")} value={data.dueCount} tone="status-icon-info" />
            <KpiCard label={t("overdue")} value={data.overdueCount} tone="status-icon-critical" />
            <KpiCard label={t("dashboard.totalSchedules")} value={data.upcoming.length} tone="status-icon-neutral" />
          </div>

          <Card>
            <CardHeader>
              <CardTitle>{t("dashboard.upcomingTitle")}</CardTitle>
              <CardDescription>{t("dashboard.upcomingDescription")}</CardDescription>
            </CardHeader>
            <CardContent>
              {data.upcoming.length === 0 ? (
                <p className="text-muted-foreground text-sm">{t("dashboard.noSchedules")}</p>
              ) : (
                <ul className="divide-y">
                  {data.upcoming.map((schedule) => {
                    const isOverdue = schedule.derivedStatus === "OVERDUE";
                    return (
                      <li
                        key={schedule.scheduleId}
                        className={`flex items-center justify-between gap-3 border-l-2 py-3 pl-3 ${
                          isOverdue ? "border-destructive/60 bg-destructive/5" : "border-transparent"
                        }`}
                      >
                        <div className="flex min-w-0 flex-col gap-1">
                          <div className="flex items-center gap-2">
                            <span className="font-medium text-sm">{schedule.dueDate}</span>
                            <Badge variant="outline">{schedule.category}</Badge>
                            <Badge variant="outline">{schedule.scheduleType}</Badge>
                          </div>
                          <p className="truncate text-muted-foreground text-xs">
                            <span className="font-medium">{schedule.machineName ?? schedule.machineCode}</span>
                            <span className="font-mono"> ({schedule.machineCode})</span>
                            {" · "}
                            {schedule.programTitle}
                          </p>
                        </div>
                        <div className="flex items-center gap-2">
                          {isOverdue ? (
                            <Badge variant="destructive" aria-label={t("dashboard.overdueAria")}>
                              {t("overdue")}
                            </Badge>
                          ) : schedule.derivedStatus === "SCHEDULED" ? (
                            <Badge className="status-badge-warning">{t("due")}</Badge>
                          ) : (
                            <Badge variant="outline">{schedule.derivedStatus}</Badge>
                          )}
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </CardContent>
          </Card>
        </div>
      )}
    </PreventiveDashboardShell>
  );
}

function KpiCard({ label, value, tone = "status-icon-neutral" }: { label: string; value: number; tone?: string }) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2 font-medium text-muted-foreground text-sm">
          <span aria-hidden="true" className={`size-2 rounded-full ${tone}`} />
          {label}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="font-bold text-3xl tabular-nums">{value}</p>
      </CardContent>
    </Card>
  );
}

function PreventiveDashboardShell({ children }: { children: React.ReactNode }) {
  const t = useTranslations("preventive");
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      <header className="space-y-1">
        <p className="font-medium text-muted-foreground text-sm">Syncro</p>
        <h1 className="font-semibold text-3xl tracking-tight">{t("dashboard.title")}</h1>
        <p className="text-muted-foreground">{t("dashboard.subtitle")}</p>
      </header>
      {children}
    </main>
  );
}
