"use client";

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
        <p className="text-muted-foreground text-sm">Failed to load alerts.</p>
        <button
          type="button"
          className="rounded-md bg-primary px-3 py-1.5 font-medium text-primary-foreground text-sm hover:bg-primary/90"
          onClick={() => void refetch()}
        >
          Retry
        </button>
      </div>
    );
  }

  if (items.length === 0) {
    return <EmptyState title="No alerts" description="This machine has no active sparepart lifetime alerts." />;
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Status</TableHead>
          <TableHead>Type</TableHead>
          <TableHead className="hidden sm:table-cell">Notification</TableHead>
          <TableHead>Sparepart</TableHead>
          <TableHead className="hidden text-right sm:table-cell">Threshold</TableHead>
          <TableHead className="hidden text-right sm:table-cell">Consumed</TableHead>
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
              {item.createdAt ? new Date(item.createdAt).toLocaleString() : "-"}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
