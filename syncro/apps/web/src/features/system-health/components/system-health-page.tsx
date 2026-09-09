"use client";

import { useEffect, useId, useState } from "react";

import Link from "next/link";

import type { UseQueryResult } from "@tanstack/react-query";
import { CircleCheck, CircleX, RefreshCw, TriangleAlert } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";

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

type PageTranslator = ReturnType<typeof useTranslations<"systemHealth">>;

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export function SystemHealthPage() {
  const t = useTranslations("systemHealth");
  const tc = useTranslations("common");
  const locale = useLocale();
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
  ].filter((v): v is number => typeof v === "number" && v > 0);
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
  const viewHistoryLabel = t("page.viewNotificationHistory");
  const wahaEvidenceLink = evidenceLink({
    alertId: lastFailedAlertId,
    severity: resolvedDependencySeverity(actuatorHealth.data?.components?.wahaCircuitBreaker),
    label: viewHistoryLabel,
  });
  const notifEvidenceLink = evidenceLink({
    alertId: lastFailedAlertId,
    severity: resolvedWorkerSeverity(notificationWorker.data),
    label: viewHistoryLabel,
  });

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6">
      {/* Page header */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-semibold text-2xl tracking-tight">{t("page.title")}</h1>
            <LatencyIndicator
              latencyState={dataQuality.data?.latencyState}
              lastLatencyMs={dataQuality.data?.lastLatencyMs}
              isLoading={dataQuality.isLoading}
              isError={dataQuality.isError}
            />
          </div>
          <p className="text-muted-foreground text-sm">{t("page.subtitle")}</p>
        </div>
        <Button variant="outline" onClick={handleRefresh} disabled={isLoading}>
          <RefreshCw aria-hidden="true" className={isFetching ? "animate-spin" : undefined} />
          {tc("refresh")}
        </Button>
      </div>

      {/* Stale data banner */}
      {isDataStale ? (
        <div
          aria-live="polite"
          className="status-banner-warning flex flex-wrap items-center justify-between gap-2 rounded-lg border px-3 py-2"
        >
          <p className="text-sm">{formatStaleBanner(t, now - lastUpdated)}</p>
          <Button size="sm" variant="outline" onClick={handleRefresh}>
            {t("page.refreshNow")}
          </Button>
        </div>
      ) : null}

      {/* Overall status banner */}
      {banner ? <OverallStatusBanner state={banner} t={t} /> : null}

      {/* Dependency cards */}
      <section aria-label={t("page.sectionAriaDependencies")}>
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">{t("page.headingDependencies")}</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <HealthCard
            title={t("cardTitles.postgres")}
            description={t("cardTitles.postgresDescription")}
            {...dependencyCardProps(t, actuatorHealth, "db")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="db" t={t} />
          </HealthCard>
          <HealthCard
            title={t("cardTitles.influxdb")}
            description={t("cardTitles.influxdbDescription")}
            {...dependencyCardProps(t, actuatorHealth, "influxdb")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="influxdb" t={t} />
          </HealthCard>
          <HealthCard
            title={t("cardTitles.redis")}
            description={t("cardTitles.redisDescription")}
            {...dependencyCardProps(t, actuatorHealth, "redis")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="redis" t={t} />
          </HealthCard>
          <HealthCard
            title={t("cardTitles.mqtt")}
            description={t("cardTitles.mqttDescription")}
            {...dependencyCardProps(t, actuatorHealth, "mqtt")}
          >
            <DependencyNextStepRow health={actuatorHealth} hintKey="mqtt" t={t} />
          </HealthCard>
          <div className="flex flex-col gap-1">
            <HealthCard
              title={t("cardTitles.waha")}
              description={t("cardTitles.wahaDescription")}
              {...dependencyCardProps(t, actuatorHealth, "wahaCircuitBreaker")}
            >
              <WahaMetricRows component={actuatorHealth.data?.components?.wahaCircuitBreaker} t={t} />
              <DependencyNextStepRow health={actuatorHealth} hintKey="wahaCircuitBreaker" t={t} />
            </HealthCard>
            {wahaEvidenceLink}
          </div>
        </div>
      </section>

      {/* Worker cards */}
      <section aria-label={t("page.sectionAriaWorkers")}>
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">{t("page.headingWorkers")}</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <HealthCard
            title={t("cardTitles.ingestWorker")}
            description={t("cardTitles.ingestWorkerDescription")}
            statusLabel={ingestWorker.data?.statusLabel}
            statusSeverity={resolvedToHealthSeverity(resolvedWorkerSeverity(ingestWorker.data))}
            statusReason={ingestWorker.data?.statusReason ?? null}
            timestamp={ingestWorker.data?.timestamp ?? null}
            loading={ingestWorker.isLoading}
            error={ingestWorker.isError}
            empty={!ingestWorker.data}
          >
            <HealthMetricRow
              label={t("page.metricLabels.mqttState")}
              value={metricString(ingestWorker.data?.mqttState)}
            />
            <HealthMetricRow label={t("page.metricLabels.queueDepth")} value={ingestQueueDepth(ingestWorker.data)} />
            <HealthMetricRow
              label={t("page.metricLabels.acceptedCount")}
              value={metricString(ingestWorker.data?.acceptedCount)}
            />
            <HealthMetricRow
              label={t("page.metricLabels.lastAccepted")}
              value={
                ingestWorker.data?.lastAcceptedAt
                  ? formatDateTimeUtc(ingestWorker.data.lastAcceptedAt, locale)
                  : t("page.freshness.dash")
              }
            />
            <HealthMetricRow
              label={t("page.metricLabels.staleSince")}
              value={
                ingestWorker.data?.staleSince
                  ? formatDateTimeUtc(ingestWorker.data.staleSince, locale)
                  : t("page.freshness.dash")
              }
            />
            {nextStepRow(t, resolvedWorkerSeverity(ingestWorker.data), "ingestWorker")}
          </HealthCard>
          <div className="flex flex-col gap-1">
            <HealthCard
              title={t("cardTitles.notificationWorker")}
              description={t("cardTitles.notificationWorkerDescription")}
              statusLabel={notificationWorker.data?.statusLabel}
              statusSeverity={resolvedToHealthSeverity(resolvedWorkerSeverity(notificationWorker.data))}
              statusReason={notificationWorker.data?.statusReason ?? null}
              timestamp={notificationWorker.data?.timestamp ?? null}
              loading={notificationWorker.isLoading}
              error={notificationWorker.isError}
              empty={!notificationWorker.data}
            >
              <HealthMetricRow
                label={t("page.metricLabels.pendingJobs")}
                value={metricString(notificationWorker.data?.pendingJobCount)}
              />
              <HealthMetricRow
                label={t("page.metricLabels.recentFailed")}
                value={metricString(notificationWorker.data?.recentFailedCount)}
              />
              <HealthMetricRow
                label={t("page.metricLabels.lastFailureReason")}
                value={metricString(notificationWorker.data?.lastFailureReason)}
              />
              <HealthMetricRow
                label={t("page.metricLabels.lastSuccessfulSend")}
                value={
                  notificationWorker.data?.lastSuccessfulSendAt
                    ? formatDateTimeUtc(notificationWorker.data.lastSuccessfulSendAt, locale)
                    : t("page.freshness.dash")
                }
              />
              <HealthMetricRow
                label={t("page.metricLabels.circuitState")}
                value={metricString(notificationWorker.data?.circuitBreakerState)}
              />
              {nextStepRow(t, resolvedWorkerSeverity(notificationWorker.data), "notificationWorker")}
            </HealthCard>
            {notifEvidenceLink}
          </div>
        </div>
      </section>

      {/* Data quality */}
      <section aria-label={t("page.sectionAriaDataQuality")}>
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">{t("page.headingDataQuality")}</h2>
        <div className="max-w-2xl">
          <DataQualityPanel status={dataQuality.data} isLoading={dataQuality.isLoading} isError={dataQuality.isError} />
        </div>
      </section>

      {/* Telemetry freshness */}
      <section aria-label={t("page.sectionAriaFreshness")}>
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">{t("page.headingFreshness")}</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <div className="flex flex-col gap-1">
            <HealthCard
              title={t("cardTitles.telemetryFreshness")}
              description={t("cardTitles.telemetryFreshnessDescription")}
              statusLabel={freshness.data?.statusLabel}
              statusSeverity={resolvedToHealthSeverity(resolvedFreshnessSeverity(freshness.data))}
              statusReason={freshness.data?.statusReason ?? null}
              timestamp={freshness.data?.timestamp ?? null}
              loading={freshness.isLoading}
              error={freshness.isError}
              empty={!freshness.data}
            >
              <HealthMetricRow
                label={t("page.metricLabels.latestReceived")}
                value={
                  freshness.data?.lastAcceptedAt
                    ? `${formatDateTimeUtc(freshness.data.lastAcceptedAt, locale)} (${formatRelativeFreshness(t, freshness.data.lastAcceptedAt, now)})`
                    : t("page.freshness.dash")
                }
              />
              <HealthMetricRow
                label={t("page.metricLabels.staleSince")}
                value={
                  freshness.data?.staleSince
                    ? formatDateTimeUtc(freshness.data.staleSince, locale)
                    : t("page.freshness.dash")
                }
              />
              <HealthMetricRow
                label={t("page.metricLabels.staleTelemetryMachines")}
                value={metricString(staleMachines.data?.staleMachineCount)}
              />
            </HealthCard>
            {staleMachines.data && staleMachines.data.items.length > 0 ? (
              <StaleMachineEvidenceList items={staleMachines.data.items} now={now} t={t} />
            ) : null}
            {staleMachines.isError ? (
              <p className="text-destructive text-xs">{t("page.staleMachineLoadError")}</p>
            ) : null}
          </div>
        </div>
      </section>

      {/* Telemetry quarantine log */}
      <section aria-label={t("page.sectionAriaQuarantine")} id="telemetry-quarantine-log" className="scroll-mt-24">
        <h2 className="mb-3 font-medium text-muted-foreground text-sm">{t("page.headingQuarantine")}</h2>
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

// English canonical labels for severity derivation ONLY (spec: deriveSeverity reads
// English label text — key off the raw code, never the translated string).
const ACTUATOR_STATUS_LABELS_EN: Record<string, string> = {
  UP: "Up",
  DOWN: "Down",
  OUT_OF_SERVICE: "Out of service",
  UNKNOWN: "Unknown",
};

/** English/canonical status label fed to deriveSeverity — locale-independent. */
function canonicalStatusLabel(component: ActuatorHealthComponent): string {
  // biome-ignore lint/nursery/useNullishCoalescing: intentionally use || to catch empty string from details
  const code = component.status || "UNKNOWN";
  return detailString(component, "statusLabel") || (ACTUATOR_STATUS_LABELS_EN[code] ?? code);
}

/** Localized display label for the card badge — code-keyed via the actuatorStatus catalog. */
function dependencyStatusLabel(t: PageTranslator, component: ActuatorHealthComponent): string {
  // biome-ignore lint/nursery/useNullishCoalescing: intentionally use || to catch empty string from details
  const code = component.status ?? "UNKNOWN";
  const backend = detailString(component, "statusLabel");
  if (backend) return backend;
  return t.has(`page.actuatorStatus.${code}`) ? t(`page.actuatorStatus.${code}`) : code;
}

/** Resolves a dependency card's effective severity from the enriched details, mirroring exactly what the card renders. */
function resolvedDependencySeverity(component: ActuatorHealthComponent | undefined): ResolvedSeverity {
  if (!component) {
    return "unknown";
  }
  return resolveSeverity(detailString(component, "statusSeverity"), canonicalStatusLabel(component));
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
  // deriveSeverity consumes the RAW backend statusLabel (contract data, never
  // translated text) — the sweep keeps severity logic keyed on raw values.
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

function OverallStatusBanner({ state, t }: { readonly state: BannerState; readonly t: PageTranslator }) {
  if (state === "healthy") {
    return (
      <div
        aria-live="polite"
        className="status-banner-healthy flex items-center gap-2 rounded-lg border px-3 py-2 text-sm"
      >
        <CircleCheck aria-hidden="true" className="status-icon-healthy shrink-0" />
        {t("page.bannerHealthy")}
      </div>
    );
  }
  if (state === "degraded") {
    return (
      <div
        aria-live="polite"
        className="status-banner-warning flex items-center gap-2 rounded-lg border px-3 py-2 text-sm"
      >
        <TriangleAlert aria-hidden="true" className="status-icon-warning shrink-0" />
        {t("page.bannerDegraded")}
      </div>
    );
  }
  return (
    <div
      aria-live="polite"
      className="flex items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm"
    >
      <CircleX aria-hidden="true" className="shrink-0 text-destructive" />
      {t("page.bannerUnhealthy")}
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

function dependencyCardProps(
  t: PageTranslator,
  health: UseQueryResult<ActuatorHealthResponse>,
  key: DependencyKey,
): DependencyCardProps {
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
    statusLabel: dependencyStatusLabel(t, component),
    statusSeverity: resolvedToHealthSeverity(resolvedDependencySeverity(component)),
    statusReason: detailString(component, "statusReason") ?? null,
    timestamp: detailString(component, "timestamp") ?? null,
  };
}

function WahaMetricRows({
  component,
  t,
}: {
  readonly component: ActuatorHealthComponent | undefined;
  readonly t: PageTranslator;
}) {
  if (!component) {
    return null;
  }
  return (
    <>
      <HealthMetricRow
        label={t("page.metricLabels.circuitState")}
        value={metricString(detailString(component, "state"))}
      />
      <HealthMetricRow
        label={t("page.metricLabels.failureRate")}
        value={metricString(detailNumber(component, "failureRate"))}
      />
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

function formatStaleBanner(t: PageTranslator, ageMs: number) {
  const minutes = Math.floor(ageMs / 60_000);
  if (minutes < 1) {
    return t("page.staleBannerUnderMinute");
  }
  return t("page.staleBannerMinutes", { count: minutes });
}

/** English fallback strings for the 2-arg (locale-agnostic, test/programmatic) call form. */
const FRESHNESS_EN: PageTranslator = ((_key: string, values?: Record<string, string | number | Date>) => {
  const count = Number(values?.count ?? 0);
  if (_key.endsWith("justNow")) return "just now";
  if (_key.endsWith("dash")) return "—";
  if (_key.endsWith("seconds")) return `${count}s ago`;
  if (_key.endsWith("minutes")) return `${count}m ago`;
  if (_key.endsWith("hours")) return `${count}h ago`;
  return `${count}d ago`;
}) as PageTranslator;
FRESHNESS_EN.has = () => true;

/** Formats a backend-provided ISO timestamp as a relative "Xs ago" freshness string, display only. */
export function formatRelativeFreshness(t: PageTranslator, iso: string, now: number): string;
export function formatRelativeFreshness(iso: string, now: number): string;
export function formatRelativeFreshness(
  tOrIso: PageTranslator | string,
  isoOrNow: string | number,
  nowMaybe?: number,
): string {
  const t = typeof tOrIso === "function" ? tOrIso : FRESHNESS_EN;
  const iso = typeof tOrIso === "function" ? (isoOrNow as string) : tOrIso;
  const now = typeof tOrIso === "function" ? (nowMaybe as number) : (isoOrNow as number);
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return t("page.freshness.dash");
  }
  const diffMs = now - then;
  if (diffMs < 0) {
    // Clock skew: backend timestamp in the future relative to the client clock.
    // Never report a negative age; the absolute UTC row still shows the value.
    return t("page.freshness.justNow");
  }
  const seconds = Math.floor(diffMs / 1000);
  if (seconds < 1) {
    // Sub-second freshness reads as "just now", matching the clock-skew string above.
    return t("page.freshness.justNow");
  }
  if (seconds < 60) {
    return t("page.freshness.seconds", { count: seconds });
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return t("page.freshness.minutes", { count: minutes });
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return t("page.freshness.hours", { count: hours });
  }
  return t("page.freshness.days", { count: Math.floor(hours / 24) });
}

// ─── Failure evidence (Story 6.6) ─────────────────────────────────────────────

/** Static next-action hint rendered inside a card only on warning/critical severity. */
function nextStepRow(t: PageTranslator, severity: ResolvedSeverity, hintKey: string) {
  if (severity !== "warning" && severity !== "critical") {
    return null;
  }
  return <HealthMetricRow label={t("page.metricLabels.nextStep")} value={t(`page.nextSteps.${hintKey}`)} />;
}

/** Dependency-card variant resolving severity from the actuator component itself. */
function DependencyNextStepRow({
  health,
  hintKey,
  t,
}: {
  readonly health: UseQueryResult<ActuatorHealthResponse>;
  readonly hintKey: DependencyKey;
  readonly t: PageTranslator;
}) {
  return nextStepRow(t, resolvedDependencySeverity(health.data?.components?.[hintKey]), hintKey);
}

/**
 * Deep link to the failing alert's notification history, rendered outside the card.
 * Present only when the card is at failure severity AND the alert id is a well-formed UUID
 * (defense-in-depth: the worker-status fetcher does not shape-guard this field).
 */
function evidenceLink({
  alertId,
  severity,
  label,
}: {
  readonly alertId: string | null;
  readonly severity: ResolvedSeverity;
  readonly label: string;
}) {
  if (!alertId || !ALERT_ID_PATTERN.test(alertId) || (severity !== "warning" && severity !== "critical")) {
    return null;
  }
  return <HealthEvidenceLink href={`/dashboard/alerts/${alertId}`}>{label}</HealthEvidenceLink>;
}

/** Expandable per-machine stale-telemetry evidence list linking to each machine hub. */
function StaleMachineEvidenceList({
  items,
  now,
  t,
}: {
  readonly items: readonly StaleMachineItem[];
  readonly now: number;
  readonly t: PageTranslator;
}) {
  const locale = useLocale();
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
        {expanded ? t("page.hideStaleMachines") : t("page.showStaleMachines", { count: items.length })}
      </Button>
      {/* Always mounted so aria-controls resolves; hidden removes it from the a11y tree. */}
      <ul id={listId} aria-label={t("page.staleMachinesAria")} hidden={!expanded} className="space-y-1">
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
                ? `${formatDateTimeUtc(item.lastReceivedAt, locale)} (${formatRelativeFreshness(t, item.lastReceivedAt, now)})`
                : t("page.noTelemetryReceived")}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
