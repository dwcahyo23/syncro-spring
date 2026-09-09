"use client";

import { useState } from "react";

import { useQuery } from "@tanstack/react-query";
import { Wrench } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { Empty, EmptyDescription, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";
import {
  useWorkorderDashboard,
  type WorkorderDashboardParams,
} from "@/features/workorders/hooks/use-workorder-dashboard";
import { useListSections } from "@/lib/api/generated/syncro";
import { syncroFetch } from "@/lib/api/orval-mutator";

interface CategoryOption {
  code: string;
  label: string;
}

const STATUS_OPTIONS = [
  "DRAFT",
  "OPEN",
  "ASSIGNED",
  "IN_PROGRESS",
  "ON_PROCUREMENT",
  "DONE",
  "CLOSED",
  "CANCELLED",
] as const;

/**
 * Workorder dashboard (story 14-1, FR-171): KPI cards (total, by status) + status and
 * category breakdowns, filterable by plant/section/status/category. All counts are
 * backend-computed; this page only renders. UX-DR-019 states: loading, error, empty
 * and plant-scope guard. The KPI grid wraps responsively with 4+ statuses.
 */
export function WorkorderDashboardPageContent() {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const format = useFormatter();
  const { scope, activePlantId, loadError } = usePlantScope();
  const plantId = activePlantId && activePlantId !== "all" ? activePlantId : undefined;

  const [sectionId, setSectionId] = useState("");
  const [status, setStatus] = useState("");
  const [categoryCode, setCategoryCode] = useState("");

  const isEnabled = Boolean(scope);
  const params: WorkorderDashboardParams = {
    plantId,
    sectionId: sectionId || undefined,
    status: status || undefined,
    categoryCode: categoryCode || undefined,
  };
  const query = useWorkorderDashboard(params, isEnabled);

  const { data: sectionsRes } = useListSections(plantId ? { plantId } : undefined, {
    query: { enabled: isEnabled && Boolean(plantId), staleTime: 60_000 },
  });
  const sections = sectionsRes?.data?.items ?? [];

  const { data: categoriesRes } = useQuery<CategoryOption[]>({
    queryKey: ["/api/v1/work-order-categories"],
    queryFn: async () => {
      const res = await syncroFetch<{ data: CategoryOption[] }>("/api/v1/work-order-categories", { method: "GET" });
      return res.data;
    },
    enabled: isEnabled,
    staleTime: 60_000,
  });

  if (loadError) {
    return <WorkorderDashboardShell>{t("dashboard.plantScopeError")}</WorkorderDashboardShell>;
  }

  if (!isEnabled) {
    return (
      <WorkorderDashboardShell>
        <Skeleton className="h-32 w-full" />
      </WorkorderDashboardShell>
    );
  }

  if (scope?.mode === "EMPTY") {
    return <WorkorderDashboardShell>{t("dashboard.noPlantsAssigned")}</WorkorderDashboardShell>;
  }

  const data = query.data;

  const monthlyChartConfig = {
    open: { label: t("dashboard.open"), color: "var(--chart-4)" },
    close: { label: t("dashboard.close"), color: "var(--chart-3)" },
  } as const;

  const statusChartConfig = {
    count: { label: t("dashboard.workorders"), color: "var(--chart-1)" },
  } as const;

  const categoryChartConfig = {
    count: { label: t("dashboard.workorders"), color: "var(--chart-2)" },
  } as const;

  const monthLabel = (month: number) =>
    format.dateTime(new Date(new Date().getFullYear(), month - 1, 1), { month: "short" });

  return (
    <WorkorderDashboardShell>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap items-end gap-3">
          <div className="grid min-w-0 gap-2">
            <Label htmlFor="wo-dash-section" className="text-muted-foreground text-xs">
              {t("dashboard.section")}
            </Label>
            <Select value={sectionId} onValueChange={setSectionId}>
              <SelectTrigger id="wo-dash-section" className="w-44" aria-label={t("dashboard.section")}>
                <SelectValue placeholder={t("dashboard.allSections")} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all-sections">{t("dashboard.allSections")}</SelectItem>
                {sections.map((section) => (
                  <SelectItem key={section.id} value={section.id ?? ""}>
                    {section.code} · {section.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid min-w-0 gap-2">
            <Label htmlFor="wo-dash-status" className="text-muted-foreground text-xs">
              {tc("status")}
            </Label>
            <Select value={status} onValueChange={setStatus}>
              <SelectTrigger id="wo-dash-status" className="w-44" aria-label={tc("status")}>
                <SelectValue placeholder={t("dashboard.allStatuses")} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all-statuses">{t("dashboard.allStatuses")}</SelectItem>
                {STATUS_OPTIONS.map((s) => (
                  <SelectItem key={s} value={s}>
                    {t.has(`status.${s}`) ? t(`status.${s}`) : s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="grid min-w-0 gap-2">
            <Label htmlFor="wo-dash-category" className="text-muted-foreground text-xs">
              {t("category")}
            </Label>
            <Select value={categoryCode} onValueChange={setCategoryCode}>
              <SelectTrigger id="wo-dash-category" className="w-44" aria-label={t("category")}>
                <SelectValue placeholder={t("dashboard.allCategories")} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all-categories">{t("dashboard.allCategories")}</SelectItem>
                {(categoriesRes ?? []).map((category) => (
                  <SelectItem key={category.code} value={category.code}>
                    {category.code} · {category.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isLoading}>
            <Wrench aria-hidden="true" className={query.isFetching ? "animate-spin" : undefined} />
            {tc("refresh")}
          </Button>
        </div>
        <p className="text-muted-foreground text-sm">{t("dashboard.computedNote")}</p>
      </div>

      {query.isLoading && (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
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

      {!query.isLoading && !query.isError && data && data.total === 0 && (
        <Empty className="min-h-40">
          <EmptyMedia variant="icon">
            <Wrench aria-hidden="true" />
          </EmptyMedia>
          <EmptyTitle>{t("dashboard.emptyTitle")}</EmptyTitle>
          <EmptyDescription>{t("dashboard.emptyDescription")}</EmptyDescription>
        </Empty>
      )}

      {!query.isLoading && !query.isError && data && data.total > 0 && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            <KpiCard label={t("dashboard.totalWorkorders")} value={data.total} tone="status-icon-info" />
            {data.byStatus.map((statusItem) => (
              <KpiCard
                key={statusItem.status}
                label={t("dashboard.statusKpi", {
                  status: t.has(`status.${statusItem.status}`) ? t(`status.${statusItem.status}`) : statusItem.status,
                })}
                value={statusItem.count}
                tone={kpiTone(statusItem.status)}
              />
            ))}
          </div>

          <Card>
            <CardHeader>
              <CardTitle>{t("dashboard.monthlyTitle")}</CardTitle>
              <CardDescription>{t("dashboard.monthlyDescription")}</CardDescription>
            </CardHeader>
            <CardContent>
              {data.byMonth.length === 0 ? (
                <p className="text-muted-foreground text-sm">{t("dashboard.noMonthlyCounts")}</p>
              ) : (
                <ChartContainer config={monthlyChartConfig} className="h-80">
                  <BarChart
                    data={data.byMonth.map((m) => ({
                      month: monthLabel(m.month),
                      open: m.openCount,
                      close: m.closeCount,
                    }))}
                    margin={{ top: 16, right: 8, left: 0, bottom: 8 }}
                  >
                    <CartesianGrid vertical={false} />
                    <XAxis dataKey="month" tickLine={false} axisLine={false} tickMargin={8} interval={0} />
                    <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={36} />
                    <ChartTooltip cursor={false} content={<ChartTooltipContent />} />
                    <ChartLegend content={<ChartLegendContent />} />
                    <Bar dataKey="open" fill="var(--color-open)" radius={[4, 4, 0, 0]} />
                    <Bar dataKey="close" fill="var(--color-close)" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ChartContainer>
              )}
            </CardContent>
          </Card>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle>{t("dashboard.byStatusTitle")}</CardTitle>
                <CardDescription>{t("dashboard.byStatusDescription")}</CardDescription>
              </CardHeader>
              <CardContent>
                {data.byStatus.length === 0 ? (
                  <p className="text-muted-foreground text-sm">{t("dashboard.noStatusCounts")}</p>
                ) : (
                  <ChartContainer config={statusChartConfig} className="h-64">
                    <BarChart data={data.byStatus}>
                      <CartesianGrid vertical={false} />
                      <XAxis dataKey="status" tickLine={false} axisLine={false} tickMargin={8} />
                      <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={32} />
                      <ChartTooltip cursor={false} content={<ChartTooltipContent hideLabel />} />
                      <Bar dataKey="count" fill="var(--color-count)" radius={[6, 6, 0, 0]} />
                    </BarChart>
                  </ChartContainer>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>{t("dashboard.byCategoryTitle")}</CardTitle>
                <CardDescription>{t("dashboard.byCategoryDescription")}</CardDescription>
              </CardHeader>
              <CardContent>
                {data.byCategory.length === 0 ? (
                  <p className="text-muted-foreground text-sm">{t("dashboard.noCategoryCounts")}</p>
                ) : (
                  <ChartContainer config={categoryChartConfig} className="h-64">
                    <BarChart
                      data={data.byCategory.map((category) => ({
                        name: category.categoryLabel ?? category.categoryCode ?? t("dashboard.uncategorized"),
                        count: category.count,
                      }))}
                    >
                      <CartesianGrid vertical={false} />
                      <XAxis dataKey="name" tickLine={false} axisLine={false} tickMargin={8} />
                      <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={32} />
                      <ChartTooltip cursor={false} content={<ChartTooltipContent hideLabel />} />
                      <Bar dataKey="count" fill="var(--color-count)" radius={[6, 6, 0, 0]} />
                    </BarChart>
                  </ChartContainer>
                )}
              </CardContent>
            </Card>
          </div>
        </div>
      )}
    </WorkorderDashboardShell>
  );
}

function KpiCard({ label, value, tone = "neutral" }: { label: string; value: number; tone?: string }) {
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

function kpiTone(status: string): string {
  switch (status) {
    case "OPEN":
      return "status-icon-critical";
    case "ASSIGNED":
    case "IN_PROGRESS":
      return "status-icon-warning";
    case "ON_PROCUREMENT":
      return "status-icon-info";
    case "DONE":
    case "CLOSED":
      return "status-icon-healthy";
    case "CANCELLED":
      return "status-icon-neutral";
    default:
      return "status-icon-neutral";
  }
}

function WorkorderDashboardShell({ children }: { children: React.ReactNode }) {
  const t = useTranslations("workOrders");
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
