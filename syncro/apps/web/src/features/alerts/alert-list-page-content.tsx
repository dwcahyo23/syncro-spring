"use client";

import { useRouter } from "next/navigation";

import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useListAlerts } from "@/lib/api/generated/syncro";
import type { AlertViewStatus } from "@/lib/api/generated/model";
import { SyncroApiError } from "@/lib/api/orval-mutator";

import { AlertStatusBadge } from "./alert-status-badge";
import { NotificationStatePill } from "./notification-state-pill";

interface AlertListPageContentProps {
  statusFilter?: AlertViewStatus;
  machineId?: string;
}

export function AlertListPageContent({ statusFilter, machineId }: AlertListPageContentProps) {
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
          <h2 className="text-lg font-semibold">Access denied</h2>
          <p className="mt-1 text-sm text-muted-foreground">You do not have access to alerts.</p>
        </div>
      );
    }
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-sm text-muted-foreground">Failed to load alerts.</p>
        <button
          type="button"
          className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={() => void refetch()}
        >
          Retry
        </button>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <EmptyState
        title="No alerts"
        description="No sparepart lifetime alerts match the current filter."
      />
    );
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Status</TableHead>
          <TableHead className="hidden sm:table-cell">Notification</TableHead>
          <TableHead>Machine</TableHead>
          <TableHead className="hidden md:table-cell">Plant</TableHead>
          <TableHead>Sparepart</TableHead>
          <TableHead className="hidden sm:table-cell text-right">Threshold</TableHead>
          <TableHead className="hidden sm:table-cell text-right">Consumed</TableHead>
          <TableHead className="hidden lg:table-cell">Created</TableHead>
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
            aria-label={`View alert for ${item.machineCode} — ${item.sparepartName ?? item.sparepartCode}`}
          >
            <TableCell>
              <AlertStatusBadge status={item.status as AlertViewStatus} />
            </TableCell>
            <TableCell className="hidden sm:table-cell">
              <NotificationStatePill summary={item.notificationSummary} />
            </TableCell>
            <TableCell>
              <div className="font-medium">{item.machineCode}</div>
              {item.machineName && (
                <div className="text-xs text-muted-foreground">{item.machineName}</div>
              )}
            </TableCell>
            <TableCell className="hidden md:table-cell text-sm">
              {item.plantCode}
              {item.plantName && (
                <span className="ml-1 text-muted-foreground">({item.plantName})</span>
              )}
            </TableCell>
            <TableCell>
              <div className="font-medium">{item.sparepartName ?? item.sparepartCode}</div>
              <div className="text-xs text-muted-foreground">{item.functionName}</div>
            </TableCell>
            <TableCell className="hidden sm:table-cell text-right tabular-nums">
              {item.thresholdPercentage}%
            </TableCell>
            <TableCell className="hidden sm:table-cell text-right tabular-nums">
              {Number(item.consumedPercentageSnapshot).toFixed(1)}%
            </TableCell>
            <TableCell className="hidden lg:table-cell text-sm text-muted-foreground">
              {item.createdAt ? new Date(item.createdAt).toLocaleString() : "-"}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
