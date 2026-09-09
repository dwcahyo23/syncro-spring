"use client";

import Link from "next/link";

import { AlertTriangle } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertStatusBadge } from "@/features/alerts/alert-status-badge";
import type { MachineView } from "@/lib/api/generated/model";
import { useListAlerts } from "@/lib/api/generated/syncro";

export interface OverviewTabProps {
  machine?: MachineView;
  isLoading?: boolean;
}

export function OverviewTab({ machine, isLoading }: OverviewTabProps) {
  const t = useTranslations("machineHub.overview");
  const tc = useTranslations("common");
  const alertsQuery = useListAlerts(
    { machineId: machine?.id, status: "OPEN", page: 0, size: 5, sort: "createdAt,asc" },
    { query: { enabled: Boolean(machine?.id), staleTime: 30_000 } },
  );

  const openAlerts = alertsQuery.data?.data?.items ?? [];
  const openAlertsTotal = alertsQuery.data?.data?.totalElements ?? 0;

  if (isLoading || !machine) {
    return (
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <Skeleton className="h-6 w-48" />
            <Skeleton className="h-4 w-64" />
          </CardHeader>
          <CardContent className="grid gap-4 md:grid-cols-2">
            {[0, 1, 2, 3, 4].map((i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Machine Details */}
      <Card>
        <CardHeader>
          <CardTitle>{t("detailsTitle")}</CardTitle>
          <CardDescription>{t("detailsDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-x-8 gap-y-4 text-sm md:grid-cols-2">
            <div>
              <dt className="font-medium text-muted-foreground">{tc("code")}</dt>
              <dd className="mt-1">{machine.code}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{tc("name")}</dt>
              <dd className="mt-1">{machine.name ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{tc("plant")}</dt>
              <dd className="mt-1">{machine.plantName ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{t("machineGroup")}</dt>
              <dd className="mt-1">{machine.machineGroupName ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{tc("status")}</dt>
              <dd className="mt-1">
                <Badge variant={machine.status === "ACTIVE" ? "default" : "secondary"}>{machine.status ?? "-"}</Badge>
              </dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{t("brand")}</dt>
              <dd className="mt-1">{machine.brand ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{t("installedAt")}</dt>
              <dd className="mt-1">{machine.installedAt ?? "-"}</dd>
            </div>
            <div>
              <dt className="font-medium text-muted-foreground">{t("telemetryFields")}</dt>
              <dd className="mt-1">
                {machine.optionalTelemetryFields && machine.optionalTelemetryFields.length > 0
                  ? machine.optionalTelemetryFields.join(", ")
                  : "-"}
              </dd>
            </div>
          </dl>
          {machine.notes && (
            <div className="mt-4">
              <dt className="text-sm font-medium text-muted-foreground">{tc("notes")}</dt>
              <dd className="mt-1 whitespace-pre-wrap rounded-md bg-muted p-3 text-sm">{machine.notes}</dd>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Alert State Summary */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <AlertTriangle
              className={openAlertsTotal > 0 ? "h-4 w-4 text-destructive" : "h-4 w-4 text-muted-foreground"}
              aria-hidden="true"
            />
            {t("alertStateTitle")}
          </CardTitle>
          <CardDescription>{t("alertStateDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          {alertsQuery.isLoading && (
            <div className="space-y-2">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          )}

          {alertsQuery.isError && <p className="text-sm text-muted-foreground">{t("alertStateLoadFailed")}</p>}

          {!alertsQuery.isLoading && !alertsQuery.isError && openAlertsTotal === 0 && (
            <p className="text-sm text-muted-foreground">{t("noOpenAlerts")}</p>
          )}

          {!alertsQuery.isLoading && !alertsQuery.isError && openAlerts.length > 0 && (
            <ul className="divide-y">
              {openAlerts.map((item) => (
                <li key={item.id}>
                  <Link
                    href={`/alerts/${item.id}`}
                    className="flex items-center justify-between gap-3 py-2 hover:text-foreground"
                    aria-label={t("alertAria", { name: item.sparepartName ?? item.sparepartCode ?? "" })}
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <AlertStatusBadge status={item.status} />
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium">{item.sparepartName ?? item.sparepartCode}</p>
                        <p className="truncate text-xs text-muted-foreground">{item.functionName}</p>
                      </div>
                    </div>
                    <span className="shrink-0 tabular-nums text-xs text-muted-foreground">
                      {Number(item.consumedPercentageSnapshot).toFixed(1)}% / {item.thresholdPercentage}%
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}

          {openAlertsTotal > 0 && (
            <div className="mt-3 border-t pt-3">
              <Link
                href="#alerts"
                className="text-sm text-muted-foreground hover:text-foreground"
                onClick={(e) => {
                  e.preventDefault();
                  document.querySelector<HTMLButtonElement>('[data-value="alerts"]')?.click();
                }}
              >
                {t("viewAllAlerts", { count: openAlertsTotal })}
              </Link>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
