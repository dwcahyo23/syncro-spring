"use client";

import { useEffect, useId, useState } from "react";

import Link from "next/link";

import type { UseQueryResult } from "@tanstack/react-query";
import { CircleCheck, CircleX, RefreshCw, TriangleAlert } from "lucide-react";

import { DataQualityPanel } from "@/components/syncro/data-quality-panel";
import {
  deriveSeverity,
  formatDateTimeUtc,
  HealthCard,
  HealthMetricRow,
  type HealthSeverity,
} from "@/components/syncro/health-card";
import { HealthEvidenceLink } from "@/components/syncro/health-evidence-link";
import { LatencyIndicator } from "@/components/syncro/latency-indicator";
import { QuarantineLogTable } from "@/components/syncro/quarantine-log-table";
import { Button } from "@/components/ui/button";
import { useActuatorHealthQuery } from "@/features/system-health/hooks/use-actuator-health-query";
import { useDataQuality } from "@/features/system-health/hooks/use-data-quality";
import { useIngestWorkerStatus } from "@/features/system-health/hooks/use-ingest-worker-status";
import { useNotificationWorkerStatus } from "@/features/system-health/hooks/use-notification-worker-status";
import { useQuarantineLog } from "@/features/system-health/hooks/use-quarantine-log";
import { useStaleMachines } from "@/features/system-health/hooks/use-stale-machines";
import { useTelemetryFreshness } from "@/features/system-health/hooks/use-telemetry-freshness";
import type {
  ActuatorHealthComponent,
  ActuatorHealthResponse,
  ActuatorStatus,
  IngestWorkerStatus,
  NotificationWorkerStatus,
  StaleMachineItem,
  TelemetryFreshnessStatus,
} from "@/features/system-health/types";

const STALE_BANNER_THRESHOLD_MS = 60_000;

const ALERT_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const DEPENDENCY_KEYS = ["db", "influxdb", "redis", "mqtt", "wahaCircuitBreaker"] as const;

type DependencyKey = (typeof DEPENDENCY_KEYS)[number];

// Suggested next diagnostic action per dependency, shown only on warning/critical cards.
// Product-generic by design: pgAdmin and other local/dev tooling stay in local docs only.
const DEPENDENCY_NEXT_STEP_HINTS: Record<DependencyKey, string> = {
  db: "Verify the PostgreSQL service is running and check backend logs for connection errors.",
  influxdb: "Verify the InfluxDB service is reachable; telemetry history writes fail while it is down.",
  redis: "Verify the Redis service is reachable; latest telemetry state cannot be read while it is down.",
  mqtt: "Verify the EMQX broker is reachable; telemetry ingest stops while the MQTT connection is down.",
  wahaCircuitBreaker: "Verify the WAHA service is reachable; notification sends pause while the circuit is open.",
};

const INGEST_WORKER_NEXT_STEP_HINT =
  "Check the ingest worker logs and MQTT subscription state; review the quarantine log below for rejected messages.";

const NOTIFICATION_WORKER_NEXT_STEP_HINT =
  "Check the notification worker logs and pending jobs; open the failing alert's notification history for attempt details.";

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
  const freshness = useTelemetryFreshness();
  const staleMachines = useStaleMachines();
  const dataQuality = useDataQuality();
  const [quarantinePage, setQuarantinePage] = useState(0);
  const quarantineLog = useQuarantineLog(quarantinePage, 20);

  const now = useNow(30_000);
  const dataUpdatedAts = [
    actuatorHealth.dataUpdatedAt,
    ingestWorker.dataUpdatedAt,
    notificationWorker.dataUpdatedAt,
    freshness.dataUpdatedAt,
    staleMachines.dataUpdatedAt,
    dataQuality.dataUpdatedAt,
    quarantineLog.dataUpdatedAt,
  ].filter((t): t is number => typeof t === "number" && t > 0);
  const lastUpdated = dataUpdatedAts.length > 0 ? Math.min(...dataUpdatedAts) : 0;
  const isDataStale = lastUpdated > 0 && now - lastUpdated >= STALE_BANNER_THRESHOLD_MS;

  const isLoading =
    actuatorHealth.isLoading ||
    ingestWorker.isLoading ||
    notificationWorker.isLoading ||
    freshness.isLoading ||
    staleMachines.isLoading ||
    dataQuality.isLoading;
  const isFetching =
    actuatorHealth.isFetching ||
    ingestWorker.isFetching ||
    notificationWorker.isFetching ||
    freshness.isFetching ||
    staleMachines.isFetching ||
    dataQuality.isFetching;

  function handleRefresh() {
    void actuatorHealth.refetch();
    void ingestWorker.refetch();
    void notificationWorker.refetch();
    void freshness.refetch();
    void staleMachines.refetch();
    void dataQuality.refetch();
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
    freshnessLoading: freshness.isLoading,
    freshnessError: freshness.isError,
    freshnessSeverity: freshnessBannerSeverity(freshness.data),
  });

  // Evidence deep links live outside HealthCard (read-only by design) and only appear on
  // failure-severity cards with a concrete evidence target.
  const lastFailedAlertId = notificationWorker.data?.lastFailedAlertId ?? null;
  const wahaEvidenceLink = evidenceLink({
    alertId: lastFailedAlertId,
    severity: resolvedDependencySeverity(actuatorHealth.data?.components?.wahaCircuitBreaker),
  });
  const notifEvidenceLink = evidenceLink({
    alertId: lastFailedAlertId,
    severity: resolvedWorkerSeverity(notificationWorker.data),
  });

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-semibold text-2xl tracking-tight">System Health</h1>
            <LatencyIndicator
              latencyState={dataQuality.data?.latencyState}
              lastLatencyMs={dataQuality.data?.lastLatencyMs}
              isLoading={dataQuality.isLoading}
              isError={dataQuality.isError}
            />
          </div>
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
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="db" />
          </HealthCard>
          <HealthCard
            title="InfluxDB"
            description="Telemetry history store"
            {...dependencyCardProps(actuatorHealth, "influxdb")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="influxdb" />
          </HealthCard>
          <HealthCard
            title="Redis"
            description="Latest telemetry state cache"
            {...dependencyCardProps(actuatorHealth, "redis")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="redis" />
          </HealthCard>
          <HealthCard
            title="MQTT / EMQX"
            description="Telemetry ingest transport"
            {...dependencyCardProps(actuatorHealth, "mqtt")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="mqtt" />
          </HealthCard>
          <div className="flex flex-col gap-1">
            <HealthCard
              title="WAHA"
              description="WhatsApp notification provider"
              {...dependencyCardProps(actuatorHealth, "wahaCircuitBreaker")}
            >
              <WahaMetricRows component={actuatorHealth.data?.components?.wahaCircuitBreaker} />
              <DependencyNextStepRow health={actuatorHealth} hintKey="wahaCircuitBreaker" />
            </HealthCard>
            {wahaEvidenceLink}
          </div>
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
            {nextStepRow(resolvedWorkerSeverity(ingestWorker.data), INGEST_WORKER_NEXT_STEP_HINT)}
          </HealthCard>
          <div className="flex flex-col gap-1">
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
              <HealthMetricRow
                label="Circuit state"
                value={metricString(notificationWorker.data?.circuitBreakerState)}
              />
              {nextStepRow(resolvedWorkerSeverity(notificationWorker.data), NOTIFICATION_WORKER_NEXT_STEP_HINT)}
            </HealthCard>
            {notifEvidenceLink}
          </div>
        </div>
      </section>

      {/* Data quality */}
      <section aria-label="Data Quality">
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">Data Quality</h2>
        <div className="max-w-2xl">
          <DataQualityPanel status={dataQuality.data} isLoading={dataQuality.isLoading} isError={dataQuality.isError} />
        </div>
      </section>

      {/* Telemetry freshness */}
      <section aria-label="Telemetry Freshness">
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">Telemetry Freshness</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <div className="flex flex-col gap-1">
            <HealthCard
              title="Telemetry Freshness"
              description="Latest accepted telemetry from the ingest path"
              statusLabel={freshness.data?.statusLabel}
              statusSeverity={resolvedToHealthSeverity(resolvedFreshnessSeverity(freshness.data))}
              statusReason={freshness.data?.statusReason ?? null}
              timestamp={freshness.data?.timestamp ?? null}
              loading={freshness.isLoading}
              error={freshness.isError}
              empty={!freshness.data}
            >
              <HealthMetricRow
                label="Latest received"
                value={
                  freshness.data?.lastAcceptedAt
                    ? `${formatDateTimeUtc(freshness.data.lastAcceptedAt)} (${formatRelativeFreshness(freshness.data.lastAcceptedAt, now)})`
                    : "—"
                }
              />
              <HealthMetricRow
                label="Stale since"
                value={freshness.data?.staleSince ? formatDateTimeUtc(freshness.data.staleSince) : "—"}
              />
              <HealthMetricRow
                label="Machines with stale telemetry"
                value={metricString(staleMachines.data?.staleMachineCount)}
              />
            </HealthCard>
            {staleMachines.data && staleMachines.data.items.length > 0 ? (
              <StaleMachineEvidenceList items={staleMachines.data.items} now={now} />
            ) : null}
            {staleMachines.isError ? (
              <p className="text-destructive text-xs">Unable to load stale machine evidence.</p>
            ) : null}
          </div>
        </div>
      </section>

      {/* Telemetry quarantine log */}
      <section aria-label="Telemetry quarantine log" id="telemetry-quarantine-log" className="scroll-mt-24">
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
  return resolveSeverity(detailString(component, "statusSeverity"), dependencyStatusLabel(component));
}

/** Resolves a worker card's effective severity from its payload, mirroring exactly what the card renders. */
function resolvedWorkerSeverity(data: IngestWorkerStatus | NotificationWorkerStatus | undefined): ResolvedSeverity {
  if (!data) {
    return "unknown";
  }
  return resolveSeverity(data.statusSeverity, data.statusLabel);
}

/** Resolves the telemetry freshness card's effective severity from its payload, mirroring exactly what the card renders. */
function resolvedFreshnessSeverity(data: TelemetryFreshnessStatus | undefined): ResolvedSeverity {
  if (!data) {
    return "unknown";
  }
  return resolveSeverity(data.statusSeverity, data.statusLabel);
}

/** Single severity-resolution pipeline shared by every health card: explicit severity first, label fallback. */
function resolveSeverity(statusSeverity: string | undefined, statusLabel: string | undefined): ResolvedSeverity {
  const severity = normalizeSeverity(statusSeverity) ?? deriveSeverity(statusLabel);
  return healthSeverityToResolved(severity);
}

/**
 * Resolves the telemetry freshness contribution to the overall banner.
 * Excluded by resolved severity, not by status name: NO_DATA (NEUTRAL → "unknown") is
 * intentionally excluded so a fresh system awaiting its first message does not downgrade
 * an otherwise healthy dependency/worker surface; LIVE resolves to "success" (a no-op);
 * only STALE (WARNING) downgrades the banner. Severity-based exclusion is forward-compatible
 * with any future state that carries a neutral severity.
 */
function freshnessBannerSeverity(data: TelemetryFreshnessStatus | undefined): ResolvedSeverity | undefined {
  const severity = resolvedFreshnessSeverity(data);
  return severity === "unknown" ? undefined : severity;
}

/**
 * Computes the overall banner only when every source has resolved.
 * Severity is derived per card (enriched details first, label fallback) so the
 * banner never contradicts the individual cards. CRITICAL → unhealthy,
 * WARNING or unverifiable (absent/error) → degraded, else healthy.
 * The optional freshness inputs let the telemetry-path stall (STALE) downgrade the
 * banner while NO_DATA and LIVE stay neutral.
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
  readonly freshnessLoading?: boolean;
  readonly freshnessError?: boolean;
  readonly freshnessSeverity?: ResolvedSeverity | undefined;
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
    freshnessLoading = false,
    freshnessError = false,
    freshnessSeverity,
  } = args;

  if (actuatorLoading || ingestLoading || notifLoading || freshnessLoading) {
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
  if (freshnessError) {
    severities.push("unknown");
  } else if (freshnessSeverity !== undefined) {
    // NO_DATA exclusion is by omission: freshnessBannerSeverity returns undefined for NO_DATA.
    severities.push(freshnessSeverity);
  }

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

/** Formats a backend-provided ISO timestamp as a relative "Xs ago" freshness string, display only. */
export function formatRelativeFreshness(iso: string, now: number): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return "—";
  }
  const diffMs = now - then;
  if (diffMs < 0) {
    // Clock skew: backend timestamp in the future relative to the client clock.
    // Never report a negative age; the absolute UTC row still shows the value.
    return "just now";
  }
  const seconds = Math.floor(diffMs / 1000);
  if (seconds < 1) {
    // Sub-second freshness reads as "just now", matching the clock-skew string above.
    return "just now";
  }
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  return `${Math.floor(hours / 24)}d ago`;
}

// ─── Failure evidence (Story 6.6) ─────────────────────────────────────────────

/** Static next-action hint rendered inside a card only on warning/critical severity. */
function nextStepRow(severity: ResolvedSeverity, hint: string) {
  if (severity !== "warning" && severity !== "critical") {
    return null;
  }
  return <HealthMetricRow label="Next step" value={hint} />;
}

/** Dependency-card variant resolving severity from the actuator component itself. */
function DependencyNextStepRow({
  health,
  hintKey,
}: {
  readonly health: UseQueryResult<ActuatorHealthResponse>;
  readonly hintKey: DependencyKey;
}) {
  return nextStepRow(
    resolvedDependencySeverity(health.data?.components?.[hintKey]),
    DEPENDENCY_NEXT_STEP_HINTS[hintKey],
  );
}

/**
 * Deep link to the failing alert's notification history, rendered outside the card.
 * Present only when the card is at failure severity AND the alert id is a well-formed UUID
 * (defense-in-depth: the worker-status fetcher does not shape-guard this field).
 */
function evidenceLink({ alertId, severity }: { readonly alertId: string | null; readonly severity: ResolvedSeverity }) {
  if (!alertId || !ALERT_ID_PATTERN.test(alertId) || (severity !== "warning" && severity !== "critical")) {
    return null;
  }
  return <HealthEvidenceLink href={`/dashboard/alerts/${alertId}`}>View notification history</HealthEvidenceLink>;
}

/** Expandable per-machine stale-telemetry evidence list linking to each machine hub. */
function StaleMachineEvidenceList({
  items,
  now,
}: {
  readonly items: readonly StaleMachineItem[];
  readonly now: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  return (
    <div className="space-y-1">
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="h-auto justify-start px-2 py-1 text-xs"
        aria-expanded={expanded}
        aria-controls={listId}
        onClick={() => setExpanded((value) => !value)}
      >
        {expanded ? "Hide stale machines" : `Show stale machines (${items.length})`}
      </Button>
      {/* Always mounted so aria-controls resolves; hidden removes it from the a11y tree. */}
      <ul id={listId} aria-label="Machines with stale telemetry" hidden={!expanded} className="space-y-1">
        {items.map((item) => (
          <li key={item.machineId} className="flex flex-wrap items-baseline justify-between gap-x-2 gap-y-0.5 text-xs">
            <Link
              className="font-medium text-primary underline-offset-4 hover:underline"
              href={`/dashboard/master-data/machines/by-id/${encodeURIComponent(item.machineId)}`}
            >
              {item.machineCode}
            </Link>
            <span className="text-muted-foreground">
              {item.plantCode} · {item.statusLabel}
            </span>
            <span className="min-w-0 break-words text-right">
              {item.lastReceivedAt
                ? `${formatDateTimeUtc(item.lastReceivedAt)} (${formatRelativeFreshness(item.lastReceivedAt, now)})`
                : "No telemetry received"}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
