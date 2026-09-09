"use client";

import { useFormatter, useTranslations } from "next-intl";

import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { AlertStatusBadge } from "@/features/alerts/alert-status-badge";
import { AlertTypeBadge } from "@/features/alerts/alert-type-badge";
import { NotificationStatePill } from "@/features/alerts/notification-state-pill";
import { useRouter } from "@/i18n/navigation";
import { useListAlerts } from "@/lib/api/generated/syncro";

export interface AlertsTabProps {
  machineId?: string;
}

export function AlertsTab({ machineId }: AlertsTabProps) {
  const t = useTranslations("machineHub");
  const tc = useTranslations("common");
  const format = useFormatter();
  const router = useRouter();

  const { data, isLoading, isError, refetch } = useListAlerts(
    { machineId, page: 0, size: 50, sort: "createdAt,desc" },
    { query: { enabled: Boolean(machineId), staleTime: 15_000 } },
  );

  const items = data?.data?.items ?? [];

  if (!machineId || isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("alerts.loadFailed")}</p>
        <button
          type="button"
          className="rounded-md bg-primary px-3 py-1.5 font-medium text-primary-foreground text-sm hover:bg-primary/90"
          onClick={() => void refetch()}
        >
          {tc("retry")}
        </button>
      </div>
    );
  }

  if (items.length === 0) {
    return <EmptyState title={t("alerts.emptyTitle")} description={t("alerts.emptyDescription")} />;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{tc("status")}</TableHead>
          <TableHead>{t("alerts.colType")}</TableHead>
          <TableHead className="hidden sm:table-cell">{t("alerts.colNotification")}</TableHead>
          <TableHead>{t("alerts.colSparepart")}</TableHead>
          <TableHead className="hidden text-right sm:table-cell">{t("alerts.colThreshold")}</TableHead>
          <TableHead className="hidden text-right sm:table-cell">{t("alerts.colConsumed")}</TableHead>
          <TableHead className="hidden md:table-cell">{tc("createdAt")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {items.map((item) => (
          <TableRow
            key={item.id}
            className="cursor-pointer"
            onClick={() => router.push(`/alerts/${item.id}`)}
            role="button"
            tabIndex={0}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                router.push(`/alerts/${item.id}`);
              }
            }}
            aria-label={t("alerts.viewAria", { name: item.sparepartName ?? item.sparepartCode ?? "" })}
          >
            <TableCell>
              <AlertStatusBadge status={item.status} />
            </TableCell>
            <TableCell>
              <AlertTypeBadge alertType={item.alertType} />
            </TableCell>
            <TableCell className="hidden sm:table-cell">
              <NotificationStatePill summary={item.notificationSummary} />
            </TableCell>
            <TableCell>
              <div className="font-medium">{item.sparepartName ?? item.sparepartCode}</div>
              <div className="text-muted-foreground text-xs">{item.functionName}</div>
            </TableCell>
            <TableCell className="hidden text-right tabular-nums sm:table-cell">
              {item.thresholdPercentage != null ? `${item.thresholdPercentage}%` : "-"}
            </TableCell>
            <TableCell className="hidden text-right tabular-nums sm:table-cell">
              {item.consumedPercentageSnapshot != null ? `${Number(item.consumedPercentageSnapshot).toFixed(1)}%` : "-"}
            </TableCell>
            <TableCell className="hidden text-muted-foreground text-sm md:table-cell">
              {item.createdAt
                ? format.dateTime(new Date(item.createdAt), { dateStyle: "medium", timeStyle: "short" })
                : "-"}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
