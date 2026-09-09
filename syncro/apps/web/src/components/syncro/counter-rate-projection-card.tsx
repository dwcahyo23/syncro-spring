"use client";

import { useFormatter, useLocale, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { InstallationProjection, MachineSparepartProjectionsView } from "@/lib/api/generated/model";
import { useGetMachineSparepartProjections } from "@/lib/api/generated/syncro";

import { formatDateTimeUtc } from "./health-card";

type CalculationBasis = NonNullable<MachineSparepartProjectionsView["calculationBasis"]>;

/**
 * Presentational view of the counter-rate estimate and per-installation depletion
 * projections (story 8-6). The backend owns every calculation; this card only renders
 * returned values and explicit insufficient-data reasons.
 */
export function CounterRateProjectionView({
  data,
  isLoading = false,
  error = false,
}: {
  readonly data?: MachineSparepartProjectionsView;
  readonly isLoading?: boolean;
  readonly error?: boolean;
}) {
  const t = useTranslations("systemHealth.counterProjection");
  const tc = useTranslations("common");
  const format = useFormatter();
  const locale = useLocale();
  const hasKnownData = Boolean(data);
  const rateAvailable = data?.rateAvailable === true;
  const projections = data?.projections ?? [];

  // Handles unknown backend enum values defensively instead of crashing on a future contract.
  const insufficientReasonText = (reason: MachineSparepartProjectionsView["insufficientReason"]): string =>
    reason && t.has(`insufficientReasons.${reason}`) ? t(`insufficientReasons.${reason}`) : t("unavailableEstimate");

  const basisLabel = (basis: CalculationBasis | undefined): string =>
    basis && t.has(`basis.${basis}`) ? t(`basis.${basis}`) : t("basisUnknown");

  const evidence = (value: string | undefined | null) =>
    value ? formatDateTimeUtc(value, locale) : tc("notAvailable");

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="font-medium text-base">{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {isLoading ? (
          <div aria-hidden="true" className="space-y-2">
            <Skeleton className="h-10 w-40" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-full" />
          </div>
        ) : null}
        {!isLoading && error ? (
          <p className="text-destructive text-xs">{hasKnownData ? t("unableRefresh") : t("loadFailed")}</p>
        ) : null}
        {!isLoading && !error && !hasKnownData ? <p className="text-muted-foreground text-xs">{t("noData")}</p> : null}
        {!isLoading && hasKnownData ? (
          <>
            {!rateAvailable ? (
              <p className="status-icon-warning text-xs" role="status">
                {insufficientReasonText(data?.insufficientReason)}
              </p>
            ) : (
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="font-semibold text-3xl tabular-nums">
                  {format.number(data?.ratePerOperatingHour ?? 0, { maximumFractionDigits: 2 })}
                </span>
                <span className="text-muted-foreground text-sm">{t("countersPerOpHour")}</span>
                <Badge variant="outline">{basisLabel(data?.calculationBasis)}</Badge>
              </div>
            )}
            <div className="space-y-1 text-xs">
              {data?.windowStartAt ? (
                <EvidenceRow label={t("windowStart")} value={evidence(data.windowStartAt)} />
              ) : null}
              {data?.windowEndAt ? <EvidenceRow label={t("windowEnd")} value={evidence(data.windowEndAt)} /> : null}
              {data?.firstSampleAt ? (
                <EvidenceRow label={t("firstSample")} value={evidence(data.firstSampleAt)} />
              ) : null}
              {data?.lastSampleAt ? <EvidenceRow label={t("lastSample")} value={evidence(data.lastSampleAt)} /> : null}
              {data?.dailyOperatingHours != null ? (
                <EvidenceRow
                  label={t("operatingTime")}
                  value={t("operatingTimeValue", {
                    value: format.number(data.dailyOperatingHours, { maximumFractionDigits: 2 }),
                    schedule: data.shiftSource ?? t("unknownSchedule"),
                  })}
                />
              ) : null}
            </div>
            {projections.length > 0 ? (
              <ul className="divide-y rounded-md border">
                {projections.map((projection) => (
                  <InstallationRow key={projection.installationId} projection={projection} />
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground text-xs">{t("noInstallations")}</p>
            )}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}

function EvidenceRow({ label, value }: { readonly label: string; readonly value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <span className="min-w-0 break-words text-right font-medium">{value}</span>
    </div>
  );
}

function InstallationRow({ projection }: { readonly projection: InstallationProjection }) {
  const t = useTranslations("systemHealth.counterProjection");
  const format = useFormatter();
  const locale = useLocale();
  return (
    <li className="space-y-1 p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium text-sm">{projection.functionName ?? t("installation")}</span>
        {projection.available ? (
          <Badge variant="outline" className="status-badge-healthy">
            {t("projected")}
          </Badge>
        ) : (
          <Badge variant="outline" className="border-transparent bg-muted/60 text-muted-foreground">
            {t("unavailable")}
          </Badge>
        )}
      </div>
      {projection.available ? (
        <div className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 text-xs">
          <span className="text-muted-foreground">{t("remainingCounters")}</span>
          <span className="text-right font-medium tabular-nums">
            {format.number(projection.remainingCounters ?? 0, { maximumFractionDigits: 2 })}
          </span>
          <span className="text-muted-foreground">{t("projectedDepletion")}</span>
          <span className="text-right font-medium">
            {projection.projectedDepletionAt ? formatDateTimeUtc(projection.projectedDepletionAt, locale) : "—"}
          </span>
          {projection.consumptionDuringLeadTime != null ? (
            <>
              <span className="text-muted-foreground">{t("leadTimeConsumption")}</span>
              <span className="text-right font-medium tabular-nums">
                {t("consumptionLine", {
                  consumption: format.number(projection.consumptionDuringLeadTime, { maximumFractionDigits: 2 }),
                  hours: format.number(projection.leadTimeHours ?? 0, { maximumFractionDigits: 2 }),
                })}
              </span>
            </>
          ) : null}
        </div>
      ) : (
        <p className="text-muted-foreground text-xs">
          {projection.reason && t.has(`insufficientReasons.${projection.reason}`)
            ? t(`insufficientReasons.${projection.reason}`)
            : t("unavailableEstimate")}
        </p>
      )}
    </li>
  );
}

/**
 * Self-fetching wrapper used by the machine hub overview; the presentational
 * {@link CounterRateProjectionView} stays fixture-testable without network mocks.
 */
export function CounterRateProjectionCard({ machineId }: { readonly machineId?: string | null }) {
  const query = useGetMachineSparepartProjections(machineId ?? "", {
    query: { enabled: Boolean(machineId), staleTime: 60_000 },
  });

  return <CounterRateProjectionView data={query.data?.data} isLoading={query.isLoading} error={query.isError} />;
}
