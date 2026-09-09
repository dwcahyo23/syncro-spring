"use client";

import { type ReactNode, useEffect, useState } from "react";

import Link from "next/link";

import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { useFormatter, useLocale, useTranslations } from "next-intl";
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

export function AlertDetailPageContent({ alertId }: AlertDetailPageContentProps) {
  const t = useTranslations("alerts");
  const tc = useTranslations("common");
  const format = useFormatter();
  const locale = useLocale();
  const authUser = useAuthUser();
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error, refetch } = useGetAlert(alertId, {
    query: {
      staleTime: 15_000,
      retry: (failureCount: number, err: unknown) =>
        failureCount < 2 && !(err instanceof SyncroApiError && (err.status === 403 || err.status === 404)),
    },
  });

  // Alert codes (ALERT_CONCURRENT_MODIFICATION, 409/403 statuses) branch first with
  // action-specific copy; everything else falls back to the translated errors chain.
  const concurrentText = t("detailPage.concurrentModified");

  const { mutate: acknowledge, isPending: isAcknowledging } = useAcknowledgeAlert({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() });
        void refetch();
        toast.success(t("detailPage.ackSuccess"));
      },
      onError: (err: unknown) => {
        if (isConcurrentModification(err)) {
          toast.error(concurrentText);
          void refetch();
        } else if (err instanceof SyncroApiError && err.status === 409) {
          toast.error(t("detailPage.ackInvalidState"));
        } else {
          toast.error(t("detailPage.ackFailed"));
        }
      },
    },
  });

  const { mutate: resolve, isPending: isResolving } = useResolveAlert({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() });
        void refetch();
        toast.success(t("detailPage.resolveSuccess"));
      },
      onError: (err: unknown) => {
        if (isConcurrentModification(err)) {
          toast.error(concurrentText);
          void refetch();
        } else if (err instanceof SyncroApiError && err.status === 409) {
          toast.error(t("detailPage.resolveInvalidState"));
        } else {
          toast.error(t("detailPage.resolveFailed"));
        }
      },
    },
  });

  const { mutate: resolveOverride, isPending: isResolvingOverride } = useResolveAlertOverride({
    mutation: {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() });
        void refetch();
        toast.success(t("detailPage.resolveOverrideSuccess"));
      },
      onError: (err: unknown) => {
        if (err instanceof SyncroApiError && err.status === 403) {
          toast.error(t("detailPage.overrideForbidden"));
        } else if (isConcurrentModification(err)) {
          toast.error(concurrentText);
          void refetch();
        } else if (err instanceof SyncroApiError && err.status === 409) {
          toast.error(t("detailPage.overrideNotOpen"));
        } else {
          toast.error(t("detailPage.resolveFailed"));
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

  const basisLabel = (basis: string | undefined): string =>
    basis && t.has(`detailPage.basis.${basis}`) ? t(`detailPage.basis.${basis}`) : t("detailPage.basisUnknown");

  const bold = (chunks: React.ReactNode) => <span className="font-semibold">{chunks}</span>;
  const semiboldTabular = (chunks: React.ReactNode) => <span className="font-semibold tabular-nums">{chunks}</span>;

  const backLink = (
    <Link
      href="/alerts"
      className="inline-flex items-center gap-1.5 text-muted-foreground text-sm hover:text-foreground"
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      {t("detailPage.backToAlerts")}
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
            <h2 className="font-semibold text-lg">{t("detailPage.notFoundTitle")}</h2>
            <p className="mt-1 text-muted-foreground text-sm">{t("detailPage.notFoundDescription")}</p>
          </div>
        </div>
      );
    }
    if (error instanceof SyncroApiError && error.status === 403) {
      return (
        <div className="space-y-4">
          {backLink}
          <div className="rounded-lg border p-6 text-center">
            <h2 className="font-semibold text-lg">{t("detailPage.accessDeniedTitle")}</h2>
            <p className="mt-1 text-muted-foreground text-sm">{t("detailPage.accessDeniedDescription")}</p>
          </div>
        </div>
      );
    }
    return (
      <div className="space-y-4">
        {backLink}
        <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
          <p className="text-muted-foreground text-sm">{t("detailPage.loadFailed")}</p>
          <button
            type="button"
            className="rounded-md bg-primary px-3 py-1.5 font-medium text-primary-foreground text-sm hover:bg-primary/90"
            onClick={() => void refetch()}
          >
            {tc("retry")}
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
            {alert.machineName
              ? t("detailPage.titleWithName", {
                  machineCode: alert.machineCode ?? "",
                  machineName: alert.machineName,
                })
              : t("detailPage.title", { machineCode: alert.machineCode ?? "" })}
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
              aria-label={t("detailPage.acknowledgeAria")}
            >
              {isAcknowledging ? t("detailPage.acknowledging") : t("detailPage.acknowledge")}
            </button>
          )}
          {alert.status === "OPEN" && isSuperAdmin && (
            <button
              type="button"
              disabled={isResolvingOverride}
              onClick={() => resolveOverride({ alertId })}
              className="rounded-md bg-accent px-3 py-1.5 font-medium text-sm text-accent-foreground hover:bg-accent/90 disabled:opacity-50"
              aria-label={t("detailPage.resolveOverrideAria")}
            >
              {isResolvingOverride ? t("detailPage.resolving") : t("detailPage.resolveOverride")}
            </button>
          )}
          {alert.status === "ACKNOWLEDGED" && (
            <button
              type="button"
              disabled={isResolving}
              onClick={() => resolve({ alertId })}
              className="rounded-md bg-secondary px-3 py-1.5 font-medium text-sm text-secondary-foreground hover:bg-secondary/90 disabled:opacity-50"
              aria-label={t("detailPage.resolveAria")}
            >
              {isResolving ? t("detailPage.resolving") : t("detailPage.resolve")}
            </button>
          )}
        </div>
      </div>

      {/* Why it fired */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("detailPage.whyTitle")}</CardTitle>
          {alert.alertType === "PROCUREMENT_RISK" ? (
            <CardDescription>{t("detailPage.whyProcurementDescription")}</CardDescription>
          ) : (
            <CardDescription>{t("detailPage.whyThresholdDescription")}</CardDescription>
          )}
        </CardHeader>
        <CardContent>
          {alert.alertType === "PROCUREMENT_RISK" ? (
            <div className="space-y-3">
              <p className="text-sm">
                {t.rich("detailPage.procurementParagraph", {
                  sparepartName: alert.sparepartName ?? alert.sparepartCode ?? "",
                  functionName: alert.functionName ?? "",
                  machineCode: alert.machineCode ?? "",
                  sparepart: bold,
                  machine: bold,
                })}
              </p>
              <dl className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                <div>
                  <dt className="text-muted-foreground text-xs">{t("detailPage.rateLabel")}</dt>
                  <dd className="font-semibold tabular-nums">
                    {alert.ratePerOperatingHour != null
                      ? t("detailPage.rateValue", { value: Number(alert.ratePerOperatingHour).toFixed(2) })
                      : t("detailPage.notApplicable")}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground text-xs">{t("detailPage.basisLabel")}</dt>
                  <dd className="font-semibold">{basisLabel(alert.calculationBasis ?? undefined)}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground text-xs">{t("detailPage.leadTimeLabel")}</dt>
                  <dd className="font-semibold tabular-nums">
                    {alert.leadTimeHours != null
                      ? t("detailPage.leadTimeValue", { value: Number(alert.leadTimeHours).toFixed(2) })
                      : t("detailPage.notApplicable")}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground text-xs">{t("detailPage.projectedDepletionLabel")}</dt>
                  <dd className="font-semibold tabular-nums">
                    {alert.projectedDepletionAt
                      ? formatDateTimeUtc(alert.projectedDepletionAt, locale)
                      : t("detailPage.notApplicable")}
                  </dd>
                </div>
              </dl>
            </div>
          ) : (
            <p className="text-sm">
              {t.rich("detailPage.thresholdParagraph", {
                consumedPct:
                  alert.consumedPercentageSnapshot != null
                    ? `${Number(alert.consumedPercentageSnapshot).toFixed(2)}%`
                    : t("detailPage.notApplicable"),
                thresholdPct: `${alert.thresholdPercentage}%`,
                sparepartName: alert.sparepartName ?? alert.sparepartCode ?? "",
                functionName: alert.functionName ?? "",
                machineCode: alert.machineCode ?? "",
                consumed: semiboldTabular,
                threshold: semiboldTabular,
                sparepart: bold,
                machine: bold,
              })}
            </p>
          )}
          {alert.statusReason && (
            <p className="mt-2 text-muted-foreground text-sm">
              <span className="font-medium">{t("detailPage.statusReasonLabel")}</span> {alert.statusReason}
            </p>
          )}
        </CardContent>
      </Card>

      {/* Lifetime progress — only meaningful for threshold alerts; procurement-risk alerts
          carry no threshold snapshots, so this would fabricate zeros. */}
      {alert.alertType === "PROCUREMENT_RISK" ? null : (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t("detailPage.lifetimeEvidenceTitle")}</CardTitle>
            <CardDescription>{t("detailPage.lifetimeEvidenceDescription")}</CardDescription>
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
        <div className="status-banner-warning rounded-md border px-4 py-3 text-sm" role="status">
          {t("detailPage.staleEscalationBanner")}
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
          <CardTitle className="text-base">{t("detailPage.machineSparepartTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <div>
              <dt className="text-muted-foreground text-xs">{t("detailPage.machineCodeLabel")}</dt>
              <dd className="font-medium">{alert.machineCode}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">{tc("plant")}</dt>
              <dd className="font-medium">
                {alert.plantCode} — {alert.plantName}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">{t("detailPage.machineGroupLabel")}</dt>
              <dd className="font-medium">{alert.machineGroupName}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">{t("detailPage.sparepartLabel")}</dt>
              <dd className="font-medium">{alert.sparepartName ?? alert.sparepartCode}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">{t("detailPage.functionLabel")}</dt>
              <dd className="font-medium">{alert.functionName}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">{t("detailPage.installationIdLabel")}</dt>
              <dd className="truncate font-mono text-xs">{alert.installationId}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      {/* Metadata */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("detailPage.metadataTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <div>
              <dt className="text-muted-foreground text-xs">{tc("createdAt")}</dt>
              <dd className="font-medium">
                {alert.createdAt
                  ? format.dateTime(new Date(alert.createdAt), { dateStyle: "medium", timeStyle: "short" })
                  : t("detailPage.notApplicable")}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">{t("detailPage.lastUpdatedLabel")}</dt>
              <dd className="font-medium">
                {alert.updatedAt
                  ? format.dateTime(new Date(alert.updatedAt), { dateStyle: "medium", timeStyle: "short" })
                  : t("detailPage.notApplicable")}
              </dd>
            </div>
            <div className="col-span-2">
              <dt className="text-muted-foreground text-xs">{t("detailPage.traceIdLabel")}</dt>
              <dd className="font-mono text-xs">{alert.traceId}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}
