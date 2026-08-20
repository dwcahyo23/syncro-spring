"use client";

import { useRouter } from "next/navigation";

import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useListAlerts } from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { AlertStatusBadge } from "@/features/alerts/alert-status-badge";
import { NotificationStatePill } from "@/features/alerts/notification-state-pill";

export interface AlertsTabProps {
  machineId?: string;
}

export function AlertsTab({ machineId }: AlertsTabProps) {
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
        description="This machine has no active sparepart lifetime alerts."
      />
    );
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Status</TableHead>
          <TableHead className="hidden sm:table-cell">Notification</TableHead>
          <TableHead>Sparepart</TableHead>
          <TableHead className="hidden sm:table-cell text-right">Threshold</TableHead>
          <TableHead className="hidden sm:table-cell text-right">Consumed</TableHead>
          <TableHead className="hidden md:table-cell">Created</TableHead>
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
            aria-label={`View alert: ${item.sparepartName ?? item.sparepartCode}`}
          >
            <TableCell>
              <AlertStatusBadge status={item.status} />
            </TableCell>
            <TableCell className="hidden sm:table-cell">
              <NotificationStatePill summary={item.notificationSummary} />
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
            <TableCell className="hidden md:table-cell text-sm text-muted-foreground">
              {item.createdAt ? new Date(item.createdAt).toLocaleString() : "-"}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
