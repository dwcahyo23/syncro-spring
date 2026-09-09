"use client";

import { useFormatter, useTranslations } from "next-intl";

import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useRouter } from "@/i18n/navigation";
import type { AlertViewStatus } from "@/lib/api/generated/model";
import { useListAlerts } from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";

import { AlertStatusBadge } from "./alert-status-badge";
import { AlertTypeBadge } from "./alert-type-badge";
import { NotificationStatePill } from "./notification-state-pill";

interface AlertListPageContentProps {
  statusFilter?: AlertViewStatus;
  machineId?: string;
}

export function AlertListPageContent({ statusFilter, machineId }: AlertListPageContentProps) {
  const t = useTranslations("alerts");
  const tc = useTranslations("common");
  const format = useFormatter();
  const router = useRouter();

  const { data, isLoading, isError, error, refetch } = useListAlerts(
    {
      ...(statusFilter ? { status: statusFilter } : {}),
      ...(machineId ? { machineId } : {}),
      page: 0,
      size: 50,
      sort: "createdAt,desc",
    },
    {
      query: {
        staleTime: 15_000,
        retry: (failureCount, err) =>
          failureCount < 2 && !(err instanceof SyncroApiError && (err.status === 403 || err.status === 401)),
      },
    },
  );

  const items = data?.data?.items ?? [];

  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  if (isError) {
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <div className="rounded-lg border p-6 text-center">
          <h2 className="font-semibold text-lg">{t("listPage.accessDeniedTitle")}</h2>
          <p className="mt-1 text-muted-foreground text-sm">{t("listPage.accessDeniedDescription")}</p>
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("listPage.loadFailed")}</p>
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
    return <EmptyState title={t("listPage.emptyTitle")} description={t("listPage.emptyDescription")} />;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{t("listPage.colStatus")}</TableHead>
          <TableHead>{t("listPage.colType")}</TableHead>
          <TableHead className="hidden sm:table-cell">{t("listPage.colNotification")}</TableHead>
          <TableHead>{t("listPage.colMachine")}</TableHead>
          <TableHead className="hidden md:table-cell">{tc("plant")}</TableHead>
          <TableHead>{t("listPage.colSparepart")}</TableHead>
          <TableHead className="hidden text-right sm:table-cell">{t("listPage.colThreshold")}</TableHead>
          <TableHead className="hidden text-right sm:table-cell">{t("listPage.colConsumed")}</TableHead>
          <TableHead className="hidden lg:table-cell">{tc("createdAt")}</TableHead>
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
            aria-label={t("listPage.rowAria", {
              machineCode: item.machineCode ?? "",
              sparepart: item.sparepartName ?? item.sparepartCode ?? "",
            })}
          >
            <TableCell>
              <AlertStatusBadge status={item.status as AlertViewStatus} />
            </TableCell>
            <TableCell>
              <AlertTypeBadge alertType={item.alertType} />
            </TableCell>
            <TableCell className="hidden sm:table-cell">
              <NotificationStatePill summary={item.notificationSummary} />
            </TableCell>
            <TableCell>
              <div className="font-medium">{item.machineCode}</div>
              {item.machineName && <div className="text-muted-foreground text-xs">{item.machineName}</div>}
            </TableCell>
            <TableCell className="hidden text-sm md:table-cell">
              {item.plantCode}
              {item.plantName && <span className="ml-1 text-muted-foreground">({item.plantName})</span>}
            </TableCell>
            <TableCell>
              <div className="font-medium">{item.sparepartName ?? item.sparepartCode}</div>
              <div className="text-muted-foreground text-xs">{item.functionName}</div>
            </TableCell>
            <TableCell className="hidden text-right tabular-nums sm:table-cell">
              {item.thresholdPercentage != null ? `${item.thresholdPercentage}%` : t("listPage.notApplicable")}
            </TableCell>
            <TableCell className="hidden text-right tabular-nums sm:table-cell">
              {item.consumedPercentageSnapshot != null
                ? `${Number(item.consumedPercentageSnapshot).toFixed(1)}%`
                : t("listPage.notApplicable")}
            </TableCell>
            <TableCell className="hidden text-muted-foreground text-sm lg:table-cell">
              {item.createdAt
                ? format.dateTime(new Date(item.createdAt), { dateStyle: "medium", timeStyle: "short" })
                : t("listPage.notApplicable")}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
