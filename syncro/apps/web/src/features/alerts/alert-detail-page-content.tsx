"use client";

import Link from "next/link";

import { ArrowLeft } from "lucide-react";
import { toast } from "sonner";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAcknowledgeAlert, useGetAlert, useResolveAlert, useResolveAlertOverride } from "@/lib/api/generated/syncro";
import { useAuthUser } from "@/lib/auth/use-auth-user";
import { SyncroApiError } from "@/lib/api/orval-mutator";

import { AlertStatusBadge } from "./alert-status-badge";
import { LifetimeProgress } from "./lifetime-progress";

interface AlertDetailPageContentProps {
  alertId: string;
}

export function AlertDetailPageContent({ alertId }: AlertDetailPageContentProps) {
  const authUser = useAuthUser();
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
        void refetch();
        toast.success("Alert acknowledged.");
      },
      onError: (err: unknown) => {
        if (err instanceof SyncroApiError && err.status === 409) {
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
        void refetch();
        toast.success("Alert resolved.");
      },
      onError: (err: unknown) => {
        if (err instanceof SyncroApiError && err.status === 409) {
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
        void refetch();
        toast.success("Alert resolved (override).");
      },
      onError: (err: unknown) => {
        if (err instanceof SyncroApiError && err.status === 403) {
          toast.error("Only SUPER_ADMIN can use resolve override.");
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

  const backLink = (
    <Link
      href="/alerts"
      className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground"
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
            <h2 className="text-lg font-semibold">Alert not found</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              This alert does not exist or is not visible to you.
            </p>
          </div>
        </div>
      );
    }
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <div className="space-y-4">
          {backLink}
          <div className="rounded-lg border p-6 text-center">
            <h2 className="text-lg font-semibold">Access denied</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              You do not have permission to view this alert.
            </p>
          </div>
        </div>
      );
    }
    return (
      <div className="space-y-4">
        {backLink}
        <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
          <p className="text-sm text-muted-foreground">Failed to load alert.</p>
          <button
            type="button"
            className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
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
          <h1 className="text-xl font-semibold">
            Alert — {alert.machineCode}
            {alert.machineName ? ` (${alert.machineName})` : ""}
          </h1>
          <p className="text-sm text-muted-foreground">
            {alert.plantCode} · {alert.machineGroupName} · {alert.sparepartName ?? alert.sparepartCode}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <AlertStatusBadge status={alert.status} />
          {alert.status === "OPEN" && (
            <button
              type="button"
              disabled={isAcknowledging}
              onClick={() => acknowledge({ alertId })}
              className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
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
              className="rounded-md bg-orange-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-orange-700 disabled:opacity-50"
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
              className="rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
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
          <CardDescription>
            Consumed lifetime reached the configured threshold at the time of detection.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p className="text-sm">
            Consumed percentage{" "}
            <span className="font-semibold tabular-nums">
              {Number(alert.consumedPercentageSnapshot).toFixed(2)}%
            </span>{" "}
            reached the configured threshold of{" "}
            <span className="font-semibold tabular-nums">{alert.thresholdPercentage}%</span> for
            sparepart{" "}
            <span className="font-semibold">
              {alert.sparepartName ?? alert.sparepartCode}
            </span>{" "}
            (function: {alert.functionName}) on machine{" "}
            <span className="font-semibold">{alert.machineCode}</span>.
          </p>
          {alert.statusReason && (
            <p className="mt-2 text-sm text-muted-foreground">
              <span className="font-medium">Status reason:</span> {alert.statusReason}
            </p>
          )}
        </CardContent>
      </Card>

      {/* Lifetime progress */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Lifetime Evidence</CardTitle>
          <CardDescription>
            Snapshot values recorded at the time the alert was created.
          </CardDescription>
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

      {/* Machine & Sparepart details */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Machine &amp; Sparepart</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <div>
              <dt className="text-xs text-muted-foreground">Machine code</dt>
              <dd className="font-medium">{alert.machineCode}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Plant</dt>
              <dd className="font-medium">{alert.plantCode} — {alert.plantName}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Machine group</dt>
              <dd className="font-medium">{alert.machineGroupName}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Sparepart</dt>
              <dd className="font-medium">{alert.sparepartName ?? alert.sparepartCode}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Function</dt>
              <dd className="font-medium">{alert.functionName}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Installation ID</dt>
              <dd className="font-mono text-xs truncate">{alert.installationId}</dd>
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
              <dt className="text-xs text-muted-foreground">Created</dt>
              <dd className="font-medium">
                {alert.createdAt ? new Date(alert.createdAt).toLocaleString() : "-"}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Last updated</dt>
              <dd className="font-medium">
                {alert.updatedAt ? new Date(alert.updatedAt).toLocaleString() : "-"}
              </dd>
            </div>
            <div className="col-span-2">
              <dt className="text-xs text-muted-foreground">Trace ID</dt>
              <dd className="font-mono text-xs">{alert.traceId}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}
