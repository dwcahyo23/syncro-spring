"use client";

import { Activity, Clock, Gauge, Hash } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import type { LatestTelemetry } from "@/features/telemetry/types";

type TelemetryCardProps = {
  telemetry: LatestTelemetry;
};

export function TelemetryCard({ telemetry }: TelemetryCardProps) {
  const t = useTranslations("telemetry.card");
  const format = useFormatter();
  const runningLabel = telemetry.running ? t("running") : t("stopped");
  return (
    <dl aria-label={t("latestValues")} className="grid grid-cols-2 gap-2">
      <TelemetryMetric
        icon={<Activity aria-hidden="true" />}
        label={t("runningState")}
        value={runningLabel}
        valueClassName={telemetry.running ? "status-icon-healthy" : undefined}
      />
      <TelemetryMetric
        icon={<Gauge aria-hidden="true" />}
        label={t("runtimeHours")}
        value={telemetry.runtimeHours == null ? t("noDataReceived") : formatRuntime(format, telemetry.runtimeHours)}
      />
      <TelemetryMetric
        icon={<Hash aria-hidden="true" />}
        label={t("productionCount")}
        value={telemetry.counting == null ? t("noDataReceived") : format.number(telemetry.counting)}
      />
      <TelemetryMetric
        icon={<Clock aria-hidden="true" />}
        label={t("lastReceived")}
        value={
          telemetry.lastReceivedAt
            ? format.dateTime(new Date(telemetry.lastReceivedAt), { dateStyle: "medium", timeStyle: "short" })
            : t("noDataReceived")
        }
      />
    </dl>
  );
}

function TelemetryMetric({
  icon,
  label,
  value,
  valueClassName,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  valueClassName?: string;
}) {
  return (
    <div className="grid min-w-0 gap-1 rounded-lg border p-2">
      <dt className="flex items-center gap-1.5 text-muted-foreground text-xs">
        {icon}
        {label}
      </dt>
      <dd className={`truncate font-medium text-sm ${valueClassName ?? ""}`}>{value}</dd>
    </div>
  );
}

function formatRuntime(format: ReturnType<typeof useFormatter>, hours: number) {
  return `${format.number(hours, { maximumFractionDigits: 1 })} h`;
}
