"use client";

import { useFormatter, useTranslations } from "next-intl";
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";

import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart";
import { Skeleton } from "@/components/ui/skeleton";
import { useWorkorderDashboard } from "@/features/workorders/hooks/use-workorder-dashboard";

/**
 * Monthly progress strip above the workorder table (leader feedback 2026-09-14):
 * hundreds of WOs a month means the table alone hides the trend — this compact
 * Open-vs-Close bar lets a leader see monthly progress without leaving the table.
 * Mirrors the table's status + category filters (QA round 2) so the chart always
 * describes the rows the leader is looking at. Month/search are not supported by
 * the dashboard endpoint — status + category cover the common "did this category
 * improve?" question without inventing backend params.
 */
export function WorkorderTrendStrip({ status, categoryCode }: { status: string; categoryCode: string }) {
  const t = useTranslations("workOrders");
  const format = useFormatter();
  const { data, isLoading } = useWorkorderDashboard(
    { status: status || undefined, categoryCode: categoryCode || undefined },
    true,
  );

  if (isLoading) {
    return <Skeleton className="h-36 w-full" />;
  }
  if (!data || data.byMonth.length === 0) return null;

  const monthLabel = (month: number) =>
    format.dateTime(new Date(new Date().getFullYear(), month - 1, 1), { month: "short" });

  const config = {
    open: { label: t("dashboard.open"), color: "var(--chart-4)" },
    close: { label: t("dashboard.close"), color: "var(--chart-3)" },
  } as const;

  return (
    <div className="space-y-1 rounded-lg border p-3">
      <p className="text-muted-foreground text-xs font-medium">{t("table.trendTitle")}</p>
      <ChartContainer config={config} className="h-28 w-full">
        <BarChart
          data={data.byMonth.map((m) => ({ month: monthLabel(m.month), open: m.openCount, close: m.closeCount }))}
        >
          <CartesianGrid vertical={false} />
          <XAxis
            dataKey="month"
            tickLine={false}
            axisLine={false}
            tickMargin={4}
            interval={0}
            tick={{ fontSize: 11 }}
          />
          <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={28} tick={{ fontSize: 11 }} />
          <ChartTooltip cursor={false} content={<ChartTooltipContent />} />
          <ChartLegend content={<ChartLegendContent />} />
          <Bar dataKey="open" fill="var(--color-open)" radius={[3, 3, 0, 0]} />
          <Bar dataKey="close" fill="var(--color-close)" radius={[3, 3, 0, 0]} />
        </BarChart>
      </ChartContainer>
    </div>
  );
}
