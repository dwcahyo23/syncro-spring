"use client";

import { useEffect, useState } from "react";

import type { UseQueryResult } from "@tanstack/react-query";
import { CircleCheck, CircleX, RefreshCw, TriangleAlert } from "lucide-react";

import {
  deriveSeverity,
  formatDateTimeUtc,
  HealthCard,
  HealthMetricRow,
  type HealthSeverity,
} from "@/components/syncro/health-card";
import { QuarantineLogTable } from "@/components/syncro/quarantine-log-table";
import { Button } from "@/components/ui/button";
import { useActuatorHealthQuery } from "@/features/system-health/hooks/use-actuator-health-query";
import { useIngestWorkerStatus } from "@/features/system-health/hooks/use-ingest-worker-status";
import { useNotificationWorkerStatus } from "@/features/system-health/hooks/use-notification-worker-status";
import { useQuarantineLog } from "@/features/system-health/hooks/use-quarantine-log";
import type {
  ActuatorHealthComponent,
  ActuatorHealthResponse,
  ActuatorStatus,
  IngestWorkerStatus,
  NotificationWorkerStatus,
} from "@/features/system-health/types";

const STALE_BANNER_THRESHOLD_MS = 60_000;

const DEPENDENCY_KEYS = ["db", "influxdb", "redis", "mqtt", "wahaCircuitBreaker"] as const;

type DependencyKey = (typeof DEPENDENCY_KEYS)[number];

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export function SystemHealthPage() {
  const actuatorHealth = useActuatorHealthQuery();
  const ingestWorker = useIngestWorkerStatus();
  const notificationWorker = useNotificationWorkerStatus();
  const [quarantinePage, setQuarantinePage] = useState(0);
  const quarantineLog = useQuarantineLog(quarantinePage, 20);

  const now = useNow(30_000);
  const dataUpdatedAts = [
    actuatorHealth.dataUpdatedAt,
    ingestWorker.dataUpdatedAt,
    notificationWorker.dataUpdatedAt,
    quarantineLog.dataUpdatedAt,
  ].filter((t): t is number => typeof t === "number" && t > 0);
  const lastUpdated = dataUpdatedAts.length > 0 ? Math.min(...dataUpdatedAts) : 0;
  const isDataStale = lastUpdated > 0 && now - lastUpdated >= STALE_BANNER_THRESHOLD_MS;

  const isLoading = actuatorHealth.isLoading || ingestWorker.isLoading || notificationWorker.isLoading;
  const isFetching = actuatorHealth.isFetching || ingestWorker.isFetching || notificationWorker.isFetching;

  function handleRefresh() {
    void actuatorHealth.refetch();
    void ingestWorker.refetch();
    void notificationWorker.refetch();
    void quarantineLog.refetch();
  }

  const banner = computeOverallBanner({
    actuatorLoading: actuatorHealth.isLoading,
    actuatorError: actuatorHealth.isError,
    components: actuatorHealth.data?.components,
    ingestLoading: ingestWorker.isLoading,
    ingestError: ingestWorker.isError,
    ingestSeverity: resolvedWorkerSeverity(ingestWorker.data),
    notifLoading: notificationWorker.isLoading,
    notifError: notificationWorker.isError,
    notifSeverity: resolvedWorkerSeverity(notificationWorker.data),
  });

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
      {banner ? <OverallStatusBanner state={banner} /> : null}

      {/* Dependency cards */}
      <section aria-label="Dependency health">
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">Dependencies</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <HealthCard
            title="PostgreSQL"
            description="Primary relational store"
            {...dependencyCardProps(actuatorHealth, "db")}
          />
          <HealthCard
            title="InfluxDB"
            description="Telemetry history store"
            {...dependencyCardProps(actuatorHealth, "influxdb")}
          />
          <HealthCard
            title="Redis"
            description="Latest telemetry state cache"
            {...dependencyCardProps(actuatorHealth, "redis")}
          />
          <HealthCard
            title="MQTT / EMQX"
            description="Telemetry ingest transport"
            {...dependencyCardProps(actuatorHealth, "mqtt")}
          />
          <HealthCard
            title="WAHA"
            description="WhatsApp notification provider"
            {...dependencyCardProps(actuatorHealth, "wahaCircuitBreaker")}
          >
            <WahaMetricRows component={actuatorHealth.data?.components?.wahaCircuitBreaker} />
          </HealthCard>
        </div>
      </section>

      {/* Worker cards */}
      <section aria-label="Worker health">
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">Workers</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <HealthCard
            title="Telemetry Ingest Worker"
            description="MQTT telemetry ingest subscriber"
            statusLabel={ingestWorker.data?.statusLabel}
            statusSeverity={resolvedToHealthSeverity(resolvedWorkerSeverity(ingestWorker.data))}
            statusReason={ingestWorker.data?.statusReason ?? null}
            timestamp={ingestWorker.data?.timestamp ?? null}
            loading={ingestWorker.isLoading}
            error={ingestWorker.isError}
            empty={!ingestWorker.data}
          >
            <HealthMetricRow label="MQTT state" value={metricString(ingestWorker.data?.mqttState)} />
            <HealthMetricRow label="Queue depth" value={ingestQueueDepth(ingestWorker.data)} />
            <HealthMetricRow label="Accepted count" value={metricString(ingestWorker.data?.acceptedCount)} />
            <HealthMetricRow
              label="Last accepted"
              value={ingestWorker.data?.lastAcceptedAt ? formatDateTimeUtc(ingestWorker.data.lastAcceptedAt) : "—"}
            />
            <HealthMetricRow
              label="Stale since"
              value={ingestWorker.data?.staleSince ? formatDateTimeUtc(ingestWorker.data.staleSince) : "—"}
            />
          </HealthCard>
          <HealthCard
            title="Notification Worker"
            description="WAHA notification dispatch worker"
            statusLabel={notificationWorker.data?.statusLabel}
            statusSeverity={resolvedToHealthSeverity(resolvedWorkerSeverity(notificationWorker.data))}
            statusReason={notificationWorker.data?.statusReason ?? null}
            timestamp={notificationWorker.data?.timestamp ?? null}
            loading={notificationWorker.isLoading}
            error={notificationWorker.isError}
            empty={!notificationWorker.data}
          >
            <HealthMetricRow label="Pending jobs" value={metricString(notificationWorker.data?.pendingJobCount)} />
            <HealthMetricRow label="Recent failed" value={metricString(notificationWorker.data?.recentFailedCount)} />
            <HealthMetricRow
              label="Last failure reason"
              value={metricString(notificationWorker.data?.lastFailureReason)}
            />
            <HealthMetricRow
              label="Last successful send"
              value={
                notificationWorker.data?.lastSuccessfulSendAt
                  ? formatDateTimeUtc(notificationWorker.data.lastSuccessfulSendAt)
                  : "—"
              }
            />
            <HealthMetricRow label="Circuit state" value={metricString(notificationWorker.data?.circuitBreakerState)} />
          </HealthCard>
        </div>
      </section>

      {/* Telemetry quarantine log */}
      <section aria-label="Telemetry quarantine log">
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">Telemetry Quarantine Log</h2>
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

export type BannerState = "healthy" | "degraded" | "unhealthy";

export type ResolvedSeverity = "success" | "warning" | "critical" | "unknown";

function healthSeverityToResolved(severity: HealthSeverity): ResolvedSeverity {
  if (severity === "CRITICAL") {
    return "critical";
  }
  if (severity === "WARNING") {
    return "warning";
  }
  if (severity === "SUCCESS") {
    return "success";
  }
  return "unknown";
}

function resolvedToHealthSeverity(severity: ResolvedSeverity): HealthSeverity {
  if (severity === "critical") {
    return "CRITICAL";
  }
  if (severity === "warning") {
    return "WARNING";
  }
  if (severity === "success") {
    return "SUCCESS";
  }
  return "NEUTRAL";
}

/** Resolves a dependency card's effective severity from the enriched details, mirroring exactly what the card renders. */
function resolvedDependencySeverity(component: ActuatorHealthComponent | undefined): ResolvedSeverity {
  if (!component) {
    return "unknown";
  }
  const label = dependencyStatusLabel(component);
  const severity = normalizeSeverity(detailString(component, "statusSeverity")) ?? deriveSeverity(label);
  return healthSeverityToResolved(severity);
}

/** Resolves a worker card's effective severity from its payload, mirroring exactly what the card renders. */
function resolvedWorkerSeverity(data: IngestWorkerStatus | NotificationWorkerStatus | undefined): ResolvedSeverity {
  if (!data) {
    return "unknown";
  }
  const severity = normalizeSeverity(data.statusSeverity) ?? deriveSeverity(data.statusLabel);
  return healthSeverityToResolved(severity);
}

/**
 * Computes the overall banner only when every source has resolved.
 * Severity is derived per card (enriched details first, label fallback) so the
 * banner never contradicts the individual cards. CRITICAL → unhealthy,
 * WARNING or unverifiable (absent/error) → degraded, else healthy.
 */
export function computeOverallBanner(args: {
  readonly actuatorLoading: boolean;
  readonly actuatorError: boolean;
  readonly components: ActuatorHealthResponse["components"] | undefined;
  readonly ingestLoading: boolean;
  readonly ingestError: boolean;
  readonly ingestSeverity: ResolvedSeverity;
  readonly notifLoading: boolean;
  readonly notifError: boolean;
  readonly notifSeverity: ResolvedSeverity;
}): BannerState | null {
  const {
    actuatorLoading,
    actuatorError,
    components,
    ingestLoading,
    ingestError,
    ingestSeverity,
    notifLoading,
    notifError,
    notifSeverity,
  } = args;

  if (actuatorLoading || ingestLoading || notifLoading) {
    return null;
  }

  const severities: ResolvedSeverity[] = [];

  if (actuatorError || components == null) {
    severities.push("unknown");
  } else {
    for (const key of DEPENDENCY_KEYS) {
      severities.push(resolvedDependencySeverity(components[key]));
    }
  }

  severities.push(ingestError ? "unknown" : ingestSeverity);
  severities.push(notifError ? "unknown" : notifSeverity);

  if (severities.some((s) => s === "critical")) {
    return "unhealthy";
  }
  if (severities.some((s) => s === "warning" || s === "unknown")) {
    return "degraded";
  }
  return "healthy";
}

function OverallStatusBanner({ state }: { readonly state: BannerState }) {
  if (state === "healthy") {
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
  if (state === "degraded") {
    return (
      <div
        aria-live="polite"
        className="flex items-center gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm"
      >
        <TriangleAlert aria-hidden="true" className="shrink-0 text-amber-600 dark:text-amber-400" />
        One or more dependencies or workers are degraded or could not be verified. See cards below for details.
      </div>
    );
  }
  return (
    <div
      aria-live="polite"
      className="flex items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm"
    >
      <CircleX aria-hidden="true" className="shrink-0 text-destructive" />
      One or more dependencies or workers are unhealthy. See cards below for details.
    </div>
  );
}

// ─── Dependency card normalization ─────────────────────────────────────────────

type DependencyCardProps = {
  readonly loading: boolean;
  readonly error: boolean;
  readonly empty: boolean;
  readonly statusLabel?: string;
  readonly statusSeverity?: HealthSeverity;
  readonly statusReason?: string | null;
  readonly timestamp?: string | null;
};

const STATUS_LABELS: Record<ActuatorStatus, string> = {
  UP: "Up",
  DOWN: "Down",
  OUT_OF_SERVICE: "Out of service",
  UNKNOWN: "Unknown",
};

function dependencyStatusLabel(component: ActuatorHealthComponent): string {
  // biome-ignore lint/nursery/useNullishCoalescing: intentionally use || to catch empty string from details
  return detailString(component, "statusLabel") || (STATUS_LABELS[component.status] ?? component.status ?? "Unknown");
}

function dependencyCardProps(health: UseQueryResult<ActuatorHealthResponse>, key: DependencyKey): DependencyCardProps {
  if (health.isLoading) {
    return { loading: true, error: false, empty: false };
  }
  if (health.isError) {
    return { loading: false, error: true, empty: false };
  }
  const component = health.data?.components?.[key];
  if (!component) {
    return { loading: false, error: false, empty: true };
  }
  return {
    loading: false,
    error: false,
    empty: false,
    statusLabel: dependencyStatusLabel(component),
    statusSeverity: resolvedToHealthSeverity(resolvedDependencySeverity(component)),
    statusReason: detailString(component, "statusReason") ?? null,
    timestamp: detailString(component, "timestamp") ?? null,
  };
}

function WahaMetricRows({ component }: { readonly component: ActuatorHealthComponent | undefined }) {
  if (!component) {
    return null;
  }
  return (
    <>
      <HealthMetricRow label="Circuit state" value={metricString(detailString(component, "state"))} />
      <HealthMetricRow label="Failure rate" value={metricString(detailNumber(component, "failureRate"))} />
    </>
  );
}

function detailString(component: ActuatorHealthComponent, key: string): string | undefined {
  const value = component.details?.[key];
  return typeof value === "string" ? value : undefined;
}

function detailNumber(component: ActuatorHealthComponent, key: string): string | undefined {
  const value = component.details?.[key];
  return typeof value === "number" && Number.isFinite(value) ? String(value) : undefined;
}

function normalizeSeverity(value: string | undefined): HealthSeverity | undefined {
  if (value === "SUCCESS" || value === "WARNING" || value === "CRITICAL" || value === "NEUTRAL") {
    return value;
  }
  return undefined;
}

/** Renders a dash for null, undefined, or empty-string metric values so counts never show "null"/"undefined" or blank rows. */
function metricString(value: string | number | null | undefined): string {
  return value == null || value === "" ? "—" : String(value);
}

function ingestQueueDepth(data: IngestWorkerStatus | undefined): string {
  if (!data) {
    return "—";
  }
  return `${metricString(data.queueDepth)} / ${metricString(data.queueCapacity)}`;
}

// ─── Formatters ───────────────────────────────────────────────────────────────

function formatMinutesAgo(ageMs: number) {
  const minutes = Math.floor(ageMs / 60_000);
  if (minutes < 1) {
    return "less than a minute";
  }
  return `${minutes} min`;
}
