import { Activity, Clock, Gauge, Hash } from "lucide-react";

import type { LatestTelemetry } from "@/features/telemetry/types";

type TelemetryCardProps = {
  telemetry: LatestTelemetry;
};

export function TelemetryCard({ telemetry }: TelemetryCardProps) {
  const runningLabel = telemetry.running ? "Running" : "Stopped";
  return (
    <dl aria-label="Latest telemetry values" className="grid grid-cols-2 gap-2">
      <TelemetryMetric
        icon={<Activity aria-hidden="true" />}
        label="Running state"
        value={runningLabel}
        valueClassName={telemetry.running ? "text-emerald-700 dark:text-emerald-400" : undefined}
      />
      <TelemetryMetric
        icon={<Gauge aria-hidden="true" />}
        label="Runtime hours"
        value={telemetry.runtimeHours == null ? "No data received" : formatRuntime(telemetry.runtimeHours)}
      />
      <TelemetryMetric
        icon={<Hash aria-hidden="true" />}
        label="Production count"
        value={telemetry.counting == null ? "No data received" : telemetry.counting.toLocaleString("en")}
      />
      <TelemetryMetric
        icon={<Clock aria-hidden="true" />}
        label="Last received"
        value={telemetry.lastReceivedAt ? formatDateTime(telemetry.lastReceivedAt) : "No data received"}
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

function formatRuntime(hours: number) {
  return `${hours.toLocaleString("en", { maximumFractionDigits: 1 })} h`;
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}
