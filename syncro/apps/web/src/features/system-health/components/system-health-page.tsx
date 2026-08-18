"use client";

import { useEffect, useState } from "react";

import { CircleCheck, CircleSlash, CircleX, Loader2, RefreshCw } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { QuarantineLogTable } from "@/components/syncro/quarantine-log-table";
import { useActuatorHealthQuery, SYSTEM_HEALTH_REFRESH_INTERVAL_MS } from "@/features/system-health/hooks/use-actuator-health-query";
import { useQuarantineLog } from "@/features/system-health/hooks/use-quarantine-log";
import type { ActuatorHealthComponent, ActuatorHealthResponse, ActuatorStatus } from "@/features/system-health/types";
import { useHealth } from "@/lib/api/generated/syncro";

const STALE_BANNER_THRESHOLD_MS = 60_000;

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export function SystemHealthPage() {
  const apiHealth = useHealth({
    query: { refetchInterval: SYSTEM_HEALTH_REFRESH_INTERVAL_MS, retry: 2 },
  });
  const actuatorHealth = useActuatorHealthQuery();
  const [quarantinePage, setQuarantinePage] = useState(0);
  const quarantineLog = useQuarantineLog(quarantinePage, 20);

  const now = useNow(30_000);
  const lastUpdated = Math.max(apiHealth.dataUpdatedAt ?? 0, actuatorHealth.dataUpdatedAt ?? 0);
  const isDataStale = lastUpdated > 0 && now - lastUpdated >= STALE_BANNER_THRESHOLD_MS;

  const isLoading = apiHealth.isLoading || actuatorHealth.isLoading;
  const isFetching = apiHealth.isFetching || actuatorHealth.isFetching;

  function handleRefresh() {
    void apiHealth.refetch();
    void actuatorHealth.refetch();
    void quarantineLog.refetch();
  }

  const components = actuatorHealth.data?.components;
  const overallStatus = actuatorHealth.data?.status ?? (actuatorHealth.isError ? "DOWN" : undefined);

  const apiData = apiHealth.data?.data as Record<string, unknown> | undefined;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <h1 className="font-semibold text-2xl tracking-tight">System Health</h1>
          <p className="text-muted-foreground text-sm">
            Dependency and worker diagnostics. Refreshes automatically every 30 seconds.
          </p>
        </div>
        <Button variant="outline" onClick={handleRefresh} disabled={isLoading}>
          <RefreshCw aria-hidden="true" className={isFetching ? "animate-spin" : undefined} />
          Refresh
        </Button>
      </div>

      {/* Stale data banner */}
      {isDataStale ? (
        <div
          aria-live="polite"
          className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2"
        >
          <p className="text-sm">
            Last updated {formatMinutesAgo(now - lastUpdated)} ago. Automatic refresh may be delayed.
          </p>
          <Button size="sm" variant="outline" onClick={handleRefresh}>
            Refresh now
          </Button>
        </div>
      ) : null}

      {/* Overall status banner */}
      {!isLoading && overallStatus ? (
        <OverallStatusBanner status={overallStatus} />
      ) : null}

      {/* Dependency cards */}
      <section aria-label="Dependency health">
        <h2 className="mb-3 font-medium text-sm text-muted-foreground">Dependencies</h2>
        {isLoading ? (
          <DependencySkeleton />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <ApiHealthCard data={apiData} isError={apiHealth.isError} />
            <DependencyCard
              title="MQTT Worker"
              description="Telemetry ingest subscriber"
              component={components?.mqtt}
              isError={actuatorHealth.isError}
              detailKeys={["lastError"]}
            />
            <DependencyCard
              title="PostgreSQL"
              description="Primary relational store"
              component={components?.db}
              isError={actuatorHealth.isError}
              detailKeys={["database", "validationQuery"]}
            />
            <DependencyCard
              title="Redis"
              description="Latest telemetry state cache"
              component={components?.redis}
              isError={actuatorHealth.isError}
              detailKeys={["version"]}
            />
            <DependencyCard
              title="InfluxDB"
              description="Telemetry history store"
              component={components?.influxdb}
              isError={actuatorHealth.isError}
            />
          </div>
        )}
      </section>

      {/* Quarantine log */}
      <section aria-label="Telemetry quarantine log">
        <h2 className="mb-3 font-medium text-sm text-muted-foreground">Telemetry Quarantine Log</h2>
        <QuarantineLogTable
          entries={quarantineLog.data?.content ?? []}
          isLoading={quarantineLog.isLoading}
          isError={quarantineLog.isError}
          page={quarantineLog.data?.number ?? quarantinePage}
          totalPages={quarantineLog.data?.totalPages ?? 0}
          onPageChange={setQuarantinePage}
        />
      </section>
    </div>
  );
}

// ─── Overall status banner ────────────────────────────────────────────────────

function OverallStatusBanner({ status }: { status: ActuatorStatus }) {
  if (status === "UP") {
    return (
      <div
        aria-live="polite"
        className="flex items-center gap-2 rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm"
      >
        <CircleCheck aria-hidden="true" className="shrink-0 text-emerald-600 dark:text-emerald-400" />
        All systems operational.
      </div>
    );
  }
  return (
    <div
      aria-live="polite"
      className="flex items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm"
    >
      <CircleX aria-hidden="true" className="shrink-0 text-destructive" />
      One or more dependencies are unhealthy. See cards below for details.
    </div>
  );
}

// ─── API health card (custom /api/v1/health endpoint) ─────────────────────────

function ApiHealthCard({
  data,
  isError,
}: {
  data: Record<string, unknown> | undefined;
  isError: boolean;
}) {
  const up = !isError && data != null;
  const status = up ? "UP" : isError ? "DOWN" : "UNKNOWN";

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-sm font-medium">Backend API</CardTitle>
          <StatusBadge status={status as ActuatorStatus} />
        </div>
        <CardDescription className="text-xs">Application health endpoint</CardDescription>
      </CardHeader>
      <CardContent className="space-y-1">
        {up ? (
          <>
            <DetailRow label="Service" value={String(data.service ?? "—")} />
            <DetailRow label="Reported at" value={data.timestamp ? formatDateTime(String(data.timestamp)) : "—"} />
          </>
        ) : isError ? (
          <p className="text-destructive text-xs">Could not reach backend API.</p>
        ) : null}
      </CardContent>
    </Card>
  );
}

// ─── Generic dependency card ──────────────────────────────────────────────────

function DependencyCard({
  title,
  description,
  component,
  isError,
  detailKeys,
}: {
  title: string;
  description: string;
  component: ActuatorHealthComponent | undefined;
  isError: boolean;
  detailKeys?: string[];
}) {
  // component absent = actuator fetch errored, or that component not registered
  const status: ActuatorStatus = isError ? "UNKNOWN" : (component?.status ?? "UNKNOWN");

  const shownDetails =
    detailKeys && component?.details
      ? detailKeys
          .filter((k) => component.details![k] != null)
          .map((k) => ({ label: k, value: String(component.details![k]) }))
      : [];

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-sm font-medium">{title}</CardTitle>
          <StatusBadge status={status} />
        </div>
        <CardDescription className="text-xs">{description}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-1">
        {isError ? (
          <p className="text-muted-foreground text-xs">Actuator health unavailable.</p>
        ) : component == null ? (
          <p className="text-muted-foreground text-xs">No health data reported.</p>
        ) : shownDetails.length > 0 ? (
          shownDetails.map(({ label, value }) => <DetailRow key={label} label={label} value={value} />)
        ) : null}
      </CardContent>
    </Card>
  );
}

// ─── Skeleton ─────────────────────────────────────────────────────────────────

function DependencySkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {["s1", "s2", "s3", "s4", "s5"].map((key) => (
        <Skeleton key={key} className="h-32 w-full" />
      ))}
    </div>
  );
}

// ─── Sub-components ───────────────────────────────────────────────────────────

const STATUS_CONFIG: Record<ActuatorStatus, { label: string; className: string; Icon: typeof CircleCheck }> = {
  UP: {
    label: "Up",
    className: "border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400",
    Icon: CircleCheck,
  },
  DOWN: {
    label: "Down",
    className: "border-transparent bg-destructive/15 text-destructive",
    Icon: CircleX,
  },
  OUT_OF_SERVICE: {
    label: "Out of service",
    className: "border-transparent bg-destructive/15 text-destructive",
    Icon: CircleX,
  },
  UNKNOWN: {
    label: "Unknown",
    className: "border-transparent bg-muted/60 text-muted-foreground",
    Icon: Loader2,
  },
};

function StatusBadge({ status }: { status: ActuatorStatus }) {
  const config = STATUS_CONFIG[status] ?? STATUS_CONFIG.UNKNOWN;
  return (
    <Badge
      aria-label={`Status: ${config.label}`}
      className={config.className}
      variant="outline"
    >
      <config.Icon aria-hidden="true" className="shrink-0" />
      {config.label}
    </Badge>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2 text-xs">
      <span className="shrink-0 text-muted-foreground capitalize">{label}</span>
      <span className="truncate font-medium text-right" title={value}>
        {value}
      </span>
    </div>
  );
}

// ─── Formatters ───────────────────────────────────────────────────────────────

function formatMinutesAgo(ageMs: number) {
  const minutes = Math.floor(ageMs / 60_000);
  if (minutes < 1) return "less than a minute";
  return `${minutes} min`;
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
