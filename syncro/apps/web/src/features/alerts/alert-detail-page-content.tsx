"use client";

import { useEffect, useState } from "react";

import Link from "next/link";

import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { toast } from "sonner";

import { formatDateTimeUtc } from "@/components/syncro/health-card";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  getListAlertsQueryKey,
  useAcknowledgeAlert,
  useGetAlert,
  useGetAlertNotifications,
  useListAuditLogEntries,
  useResolveAlert,
  useResolveAlertOverride,
} from "@/lib/api/generated/syncro";
import { SyncroApiError } from "@/lib/api/orval-mutator";
import { useAuthUser } from "@/lib/auth/use-auth-user";

import { AlertNotificationHistory } from "./alert-notification-history";
import { AlertStatusBadge } from "./alert-status-badge";
import { AlertTypeBadge } from "./alert-type-badge";
import { LifetimeProgress } from "./lifetime-progress";

interface AlertDetailPageContentProps {
  alertId: string;
}

function isConcurrentModification(err: unknown): boolean {
  return (
    err instanceof SyncroApiError &&
    err.status === 409 &&
    typeof err.payload === "object" &&
    err.payload !== null &&
    "code" in err.payload &&
    (err.payload as { code?: unknown }).code === "ALERT_CONCURRENT_MODIFICATION"
  );
}

const BASIS_LABELS: Record<string, string> = {
  ROLLING_30_DAY: "Rolling 30 days",
  FULL_HISTORY: "Full history",
};

function basisLabel(basis: string | undefined): string {
  return (basis && BASIS_LABELS[basis]) || "Unknown basis";
}

export function AlertDetailPageContent({ alertId }: AlertDetailPageContentProps) {
  const authUser = useAuthUser();
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error, refetch } = useGetAlert(alertId, {
    query: {
      staleTime: 15_000,
      retry: (failureCount: number, err: unknown) =>
        failureCount < 2 && !(err instanceof SyncroApiError && (err.status === 403 || err.status === 404)),
    },
  });

  const { mutate: acknowledge, isPending: isAcknowledging } = useAcknowledgeAlert({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() });
        void refetch();
        toast.success("Alert acknowledged.");
      },
      onError: (err: unknown) => {
        if (isConcurrentModification(err)) {
          toast.error("Alert was modified concurrently. Reload and retry.");
          void refetch();
        } else if (err instanceof SyncroApiError && err.status === 409) {
          toast.error("Cannot acknowledge: invalid state transition.");
        } else {
          toast.error("Failed to acknowledge alert.");
        }
      },
    },
  });

  const { mutate: resolve, isPending: isResolving } = useResolveAlert({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() });
        void refetch();
        toast.success("Alert resolved.");
      },
      onError: (err: unknown) => {
        if (isConcurrentModification(err)) {
          toast.error("Alert was modified concurrently. Reload and retry.");
          void refetch();
        } else if (err instanceof SyncroApiError && err.status === 409) {
          toast.error("Cannot resolve: invalid state transition.");
        } else {
          toast.error("Failed to resolve alert.");
        }
      },
    },
  });

  const { mutate: resolveOverride, isPending: isResolvingOverride } = useResolveAlertOverride({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() });
        void refetch();
        toast.success("Alert resolved (override).");
      },
      onError: (err: unknown) => {
        if (err instanceof SyncroApiError && err.status === 403) {
          toast.error("Only SUPER_ADMIN can use resolve override.");
        } else if (isConcurrentModification(err)) {
          toast.error("Alert was modified concurrently. Reload and retry.");
          void refetch();
        } else if (err instanceof SyncroApiError && err.status === 409) {
          toast.error("Cannot override: alert is not in OPEN state.");
        } else {
          toast.error("Failed to resolve alert.");
        }
      },
    },
  });

  const isSuperAdmin = authUser?.applicationRole === "SUPER_ADMIN";
  const alert = data?.data;

  const {
    data: notifData,
    isLoading: notifLoading,
    isError: notifError,
    error: notifErr,
    refetch: refetchNotif,
  } = useGetAlertNotifications(alertId, {
    query: {
      staleTime: 15_000,
      refetchInterval: 60_000,
      retry: 1,
      enabled: Boolean(alert) && !isError,
    },
  });

  const {
    data: auditData,
    isLoading: auditLoading,
    isError: auditError,
    error: auditErr,
    refetch: refetchAudit,
  } = useListAuditLogEntries(
    { entityType: "ALERT", entityId: alertId, size: 50, sort: "createdAt,desc" },
    {
      query: {
        staleTime: 15_000,
        enabled: Boolean(alert) && !isError,
      },
    },
  );

  const history = notifData?.data;
  const auditEntries = auditData?.data?.items ?? undefined;

  // Force recompute stale banner every 60s so threshold crossing appears without manual refetch
  const [nowTick, setNowTick] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNowTick(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);

  // stale banner: OPEN and last sent >15m ago with no PENDING queued and not cancelled
  // Fallback to alert creation time when no sentAt exists (e.g., only ROUTING_FAILED) so never-sent alerts still surface stale
  const isStale = (() => {
    if (alert?.status !== "OPEN" || !history || (history.items ?? []).length === 0) return false;
    const jobItems = history.items ?? [];
    const hasPending = jobItems.some((j) => j.status === "PENDING");
    const hasCancelled = jobItems.some((j) => j.status === "CANCELLED");
    if (hasPending || hasCancelled) return false;
    const sentTimes = jobItems
      .map((j) => j.sentAt)
      .filter((v): v is string => Boolean(v))
      .map((v) => new Date(v).getTime())
      .filter((t) => !Number.isNaN(t));
    if (sentTimes.length === 0) {
      const created = alert.createdAt ? new Date(alert.createdAt).getTime() : NaN;
      if (Number.isNaN(created)) return false;
      return nowTick - created > 15 * 60 * 1000;
    }
    const lastSent = Math.max(...sentTimes);
    return nowTick - lastSent > 15 * 60 * 1000;
  })();

  const backLink = (
    <Link
      href="/alerts"
      className="inline-flex items-center gap-1.5 text-muted-foreground text-sm hover:text-foreground"
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      Back to Alerts
    </Link>
  );

  if (isLoading) {
    return (
      <div className="space-y-4">
        {backLink}
        <Skeleton className="h-48 w-full" />
      </div>
    );
  }

  if (isError) {
    const apiError = error instanceof SyncroApiError ? error : null;
    if (apiError?.status === 404) {
      return (
        <div className="space-y-4">
          {backLink}
          <div className="rounded-lg border p-6 text-center">
            <h2 className="font-semibold text-lg">Alert not found</h2>
            <p className="mt-1 text-muted-foreground text-sm">This alert does not exist or is not visible to you.</p>
          </div>
        </div>
      );
    }
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <div className="space-y-4">
          {backLink}
          <div className="rounded-lg border p-6 text-center">
            <h2 className="font-semibold text-lg">Access denied</h2>
            <p className="mt-1 text-muted-foreground text-sm">You do not have permission to view this alert.</p>
          </div>
        </div>
      );
    }
    return (
      <div className="space-y-4">
        {backLink}
        <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
          <p className="text-muted-foreground text-sm">Failed to load alert.</p>
          <button
            type="button"
            className="rounded-md bg-primary px-3 py-1.5 font-medium text-primary-foreground text-sm hover:bg-primary/90"
            onClick={() => void refetch()}
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  if (!alert) {
    return null;
  }

  return (
    <div className="space-y-6">
      {backLink}

      {/* Header */}
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="font-semibold text-xl">
            Alert — {alert.machineCode}
            {alert.machineName ? ` (${alert.machineName})` : ""}
          </h1>
          <p className="text-muted-foreground text-sm">
            {alert.plantCode} · {alert.machineGroupName} · {alert.sparepartName ?? alert.sparepartCode}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <AlertTypeBadge alertType={alert.alertType} />
          <AlertStatusBadge status={alert.status} />
          {alert.status === "OPEN" && (
            <button
              type="button"
              disabled={isAcknowledging}
              onClick={() => acknowledge({ alertId })}
              className="rounded-md bg-primary px-3 py-1.5 font-medium text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              aria-label="Acknowledge this alert"
            >
              {isAcknowledging ? "Acknowledging…" : "Acknowledge"}
            </button>
          )}
          {alert.status === "OPEN" && isSuperAdmin && (
            <button
              type="button"
              disabled={isResolvingOverride}
              onClick={() => resolveOverride({ alertId })}
              className="rounded-md bg-accent px-3 py-1.5 font-medium text-sm text-accent-foreground hover:bg-accent/90 disabled:opacity-50"
              aria-label="Resolve this alert directly (SUPER_ADMIN override)"
            >
              {isResolvingOverride ? "Resolving…" : "Resolve Override"}
            </button>
          )}
          {alert.status === "ACKNOWLEDGED" && (
            <button
              type="button"
              disabled={isResolving}
              onClick={() => resolve({ alertId })}
              className="rounded-md bg-secondary px-3 py-1.5 font-medium text-sm text-secondary-foreground hover:bg-secondary/90 disabled:opacity-50"
              aria-label="Resolve this alert"
            >
              {isResolving ? "Resolving…" : "Resolve"}
            </button>
          )}
        </div>
      </div>

      {/* Why it fired */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Why this alert fired</CardTitle>
          {alert.alertType === "PROCUREMENT_RISK" ? (
            <CardDescription>Projected depletion falls within the sparepart lead-time window.</CardDescription>
          ) : (
            <CardDescription>
              Consumed lifetime reached the configured threshold at the time of detection.
            </CardDescription>
          )}
        </CardHeader>
        <CardContent>
          {alert.alertType === "PROCUREMENT_RISK" ? (
            <div className="space-y-3">
              <p className="text-sm">
                Projected depletion for sparepart{" "}
                <span className="font-semibold">{alert.sparepartName ?? alert.sparepartCode}</span> (function:{" "}
                {alert.functionName}) on machine <span className="font-semibold">{alert.machineCode}</span> falls within
                its procurement lead-time window, so ordering should start before stock-out.
              </p>
              <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                <div>
                  <dt className="text-muted-foreground text-xs">Rate per operating hour</dt>
                  <dd className="font-semibold tabular-nums">
                    {alert.ratePerOperatingHour != null
                      ? `${Number(alert.ratePerOperatingHour).toFixed(2)} counters/op-hour`
                      : "-"}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground text-xs">Projection basis</dt>
                  <dd className="font-semibold">{basisLabel(alert.calculationBasis ?? undefined)}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground text-xs">Lead time used</dt>
                  <dd className="font-semibold tabular-nums">
                    {alert.leadTimeHours != null ? `${Number(alert.leadTimeHours).toFixed(2)} h` : "-"}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground text-xs">Projected depletion</dt>
                  <dd className="font-semibold tabular-nums">
                    {alert.projectedDepletionAt ? formatDateTimeUtc(alert.projectedDepletionAt) : "-"}
                  </dd>
                </div>
              </dl>
            </div>
          ) : (
            <p className="text-sm">
              Consumed percentage{" "}
              <span className="font-semibold tabular-nums">
                {alert.consumedPercentageSnapshot != null
                  ? `${Number(alert.consumedPercentageSnapshot).toFixed(2)}%`
                  : "-"}
              </span>{" "}
              reached the configured threshold of{" "}
              <span className="font-semibold tabular-nums">{alert.thresholdPercentage}%</span> for sparepart{" "}
              <span className="font-semibold">{alert.sparepartName ?? alert.sparepartCode}</span> (function:{" "}
              {alert.functionName}) on machine <span className="font-semibold">{alert.machineCode}</span>.
            </p>
          )}
          {alert.statusReason && (
            <p className="mt-2 text-muted-foreground text-sm">
              <span className="font-medium">Status reason:</span> {alert.statusReason}
            </p>
          )}
        </CardContent>
      </Card>

      {/* Lifetime progress — only meaningful for threshold alerts; procurement-risk alerts
          carry no threshold snapshots, so this would fabricate zeros. */}
      {alert.alertType === "PROCUREMENT_RISK" ? null : (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Lifetime Evidence</CardTitle>
            <CardDescription>Snapshot values recorded at the time the alert was created.</CardDescription>
          </CardHeader>
          <CardContent>
            <LifetimeProgress
              baselineCounter={alert.baselineCounter ?? 0}
              currentCounterSnapshot={alert.currentCounterSnapshot ?? 0}
              expectedProductionCount={alert.expectedProductionCount ?? 0}
              consumedProductionCountSnapshot={alert.consumedProductionCountSnapshot ?? 0}
              consumedPercentageSnapshot={alert.consumedPercentageSnapshot ?? 0}
              thresholdPercentage={alert.thresholdPercentage ?? 0}
            />
          </CardContent>
        </Card>
      )}

      {/* Stale escalation banner */}
      {isStale ? (
        <div
          className="status-banner-warning rounded-md border px-4 py-3 text-sm"
          role="status"
        >
          Next escalation pending — check worker status in System Health.
        </div>
      ) : null}

      {/* Escalation Timeline — first-class section per page-spec §3 */}
      <AlertNotificationHistory
        history={history}
        auditEntries={auditEntries}
        isLoadingHistory={notifLoading}
        isLoadingAudit={auditLoading}
        errorHistory={notifError ? notifErr : null}
        errorAudit={auditError ? auditErr : null}
        onRetryHistory={() => void refetchNotif()}
        onRetryAudit={() => void refetchAudit()}
        alertStatus={alert?.status as "OPEN" | "ACKNOWLEDGED" | "RESOLVED"}
        alertCreatedAt={alert?.createdAt ?? null}
      />

      {/* Machine & Sparepart details */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Machine &amp; Sparepart</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <div>
              <dt className="text-muted-foreground text-xs">Machine code</dt>
              <dd className="font-medium">{alert.machineCode}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Plant</dt>
              <dd className="font-medium">
                {alert.plantCode} — {alert.plantName}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Machine group</dt>
              <dd className="font-medium">{alert.machineGroupName}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Sparepart</dt>
              <dd className="font-medium">{alert.sparepartName ?? alert.sparepartCode}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Function</dt>
              <dd className="font-medium">{alert.functionName}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Installation ID</dt>
              <dd className="truncate font-mono text-xs">{alert.installationId}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      {/* Metadata */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Alert Metadata</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <div>
              <dt className="text-muted-foreground text-xs">Created</dt>
              <dd className="font-medium">{alert.createdAt ? new Date(alert.createdAt).toLocaleString() : "-"}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Last updated</dt>
              <dd className="font-medium">{alert.updatedAt ? new Date(alert.updatedAt).toLocaleString() : "-"}</dd>
            </div>
            <div className="col-span-2">
              <dt className="text-muted-foreground text-xs">Trace ID</dt>
              <dd className="font-mono text-xs">{alert.traceId}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}
