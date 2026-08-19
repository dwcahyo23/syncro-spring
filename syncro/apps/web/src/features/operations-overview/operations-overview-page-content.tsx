"use client";

import Link from "next/link";

import { AlertTriangle, Bell } from "lucide-react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useListAlerts } from "@/lib/api/generated/syncro";
import { AlertStatusBadge } from "@/features/alerts/alert-status-badge";
import { usePlantScope } from "@/features/plant-scope/plant-scope-store";

function timeAgo(dateStr?: string): string {
  if (!dateStr) return "-";
  const diffMs = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diffMs / 60_000);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function OperationsOverviewPageContent() {
  const { scope, activePlantId, loadError } = usePlantScope();

  const plantId =
    activePlantId && activePlantId !== "all" ? activePlantId : undefined;

  const openAlertsQuery = useListAlerts(
    { status: "OPEN", plantId, page: 0, size: 5, sort: "createdAt,asc" },
    { query: { enabled: !!scope, staleTime: 30_000 } },
  );

  // --- guard states ---

  if (loadError) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <header className="space-y-1">
          <p className="text-sm font-medium text-muted-foreground">Syncro</p>
          <h1 className="text-3xl font-semibold tracking-tight">Operations Overview</h1>
        </header>
        <Card>
          <CardHeader>
            <CardTitle>Plant scope unavailable</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              Plant scope could not be loaded. Try again or contact your administrator.
            </p>
          </CardContent>
        </Card>
      </main>
    );
  }

  if (!scope) {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <header className="space-y-1">
          <p className="text-sm font-medium text-muted-foreground">Syncro</p>
          <h1 className="text-3xl font-semibold tracking-tight">Operations Overview</h1>
        </header>
        <Skeleton className="h-32 w-full" />
      </main>
    );
  }

  if (scope.mode === "EMPTY") {
    return (
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
        <header className="space-y-1">
          <p className="text-sm font-medium text-muted-foreground">Syncro</p>
          <h1 className="text-3xl font-semibold tracking-tight">Operations Overview</h1>
        </header>
        <Card>
          <CardHeader>
            <CardTitle>No plants assigned</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              No plants assigned to your account. Contact your administrator.
            </p>
          </CardContent>
        </Card>
      </main>
    );
  }

  const openAlerts = openAlertsQuery.data?.data?.items ?? [];
  const openAlertsTotal = openAlertsQuery.data?.data?.totalElements ?? 0;
  const isLoadingAlerts = openAlertsQuery.isLoading;
  const isErrorAlerts = openAlertsQuery.isError;

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      {/* Page header */}
      <header className="space-y-1">
        <p className="text-sm font-medium text-muted-foreground">Syncro</p>
        <h1 className="text-3xl font-semibold tracking-tight">Operations Overview</h1>
        <p className="text-muted-foreground">What needs attention now.</p>
      </header>

      {/* Summary metric bar */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Link href="/alerts?status=OPEN" className="block">
          <Card className="hover:border-foreground/20 transition-colors">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium text-muted-foreground">Open Alerts</CardTitle>
            </CardHeader>
            <CardContent>
              {isLoadingAlerts ? (
                <Skeleton className="h-8 w-12" />
              ) : (
                <p className="text-2xl font-bold tabular-nums">{openAlertsTotal}</p>
              )}
            </CardContent>
          </Card>
        </Link>
        {/* Placeholder metrics — Epic 5+ will fill these */}
        <Card className="opacity-50">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Active Machines</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold text-muted-foreground">—</p>
          </CardContent>
        </Card>
        <Card className="opacity-50">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Stale Telemetry</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold text-muted-foreground">—</p>
          </CardContent>
        </Card>
        <Card className="opacity-50">
          <CardHeader className="pb-2">
            <CardTitle className="text-sm font-medium text-muted-foreground">Health Status</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-bold text-muted-foreground">—</p>
          </CardContent>
        </Card>
      </div>

      {/* Alerts Requiring Action */}
      <section aria-labelledby="alerts-section-heading">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="alerts-section-heading" className="flex items-center gap-2 text-base font-semibold">
            <AlertTriangle className="h-4 w-4 text-destructive" aria-hidden="true" />
            Alerts Requiring Action
          </h2>
          <Link
            href="/alerts"
            className="text-sm text-muted-foreground hover:text-foreground"
          >
            View all alerts →
          </Link>
        </div>

        {isLoadingAlerts && (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-14 w-full" />
            ))}
          </div>
        )}

        {isErrorAlerts && (
          <Card>
            <CardContent className="flex flex-col items-center gap-3 py-6">
              <p className="text-sm text-muted-foreground">Failed to load alerts.</p>
              <button
                type="button"
                className="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90"
                onClick={() => void openAlertsQuery.refetch()}
              >
                Retry
              </button>
            </CardContent>
          </Card>
        )}

        {!isLoadingAlerts && !isErrorAlerts && openAlerts.length === 0 && (
          <Card>
            <CardContent className="py-8 text-center">
              <Bell className="mx-auto mb-2 h-6 w-6 text-muted-foreground" aria-hidden="true" />
              <p className="text-sm font-medium">No open alerts</p>
              <p className="mt-1 text-xs text-muted-foreground">
                All spareparts within threshold.
              </p>
            </CardContent>
          </Card>
        )}

        {!isLoadingAlerts && !isErrorAlerts && openAlerts.length > 0 && (
          <Card>
            <CardContent className="p-0">
              <ul role="list" className="divide-y">
                {openAlerts.map((item) => (
                  <li key={item.id}>
                    <Link
                      href={`/alerts/${item.id}`}
                      className="flex flex-col gap-1 px-4 py-3 hover:bg-muted/50 sm:flex-row sm:items-center sm:justify-between"
                      aria-label={`Alert for ${item.sparepartName ?? item.sparepartCode} on ${item.machineCode}`}
                    >
                      <div className="flex items-center gap-3">
                        <AlertStatusBadge status={item.status} />
                        <div>
                          <p className="text-sm font-medium">
                            {item.machineCode}
                            {item.machineName ? ` — ${item.machineName}` : ""}
                          </p>
                          <p className="text-xs text-muted-foreground">
                            {item.sparepartName ?? item.sparepartCode} · {item.functionName}
                          </p>
                        </div>
                      </div>
                      <div className="flex items-center gap-4 pl-9 text-xs text-muted-foreground sm:pl-0">
                        <span className="tabular-nums">
                          {Number(item.consumedPercentageSnapshot).toFixed(1)}% consumed
                          <span className="mx-1">·</span>
                          threshold {item.thresholdPercentage}%
                        </span>
                        <span className="hidden sm:inline">{timeAgo(item.createdAt)}</span>
                      </div>
                    </Link>
                  </li>
                ))}
              </ul>
              {openAlertsTotal > 5 && (
                <div className="border-t px-4 py-3 text-center">
                  <Link href="/alerts?status=OPEN" className="text-sm text-muted-foreground hover:text-foreground">
                    +{openAlertsTotal - 5} more open alerts — view all
                  </Link>
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </section>
    </main>
  );
}
