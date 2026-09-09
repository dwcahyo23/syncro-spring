import { useTranslations } from "next-intl";

import { MachineSummaryCard } from "@/components/syncro/machine-summary-card";
import { StatusBadge } from "@/components/syncro/status-badge";
import { TelemetryCard } from "@/components/syncro/telemetry-card";
import { TelemetryCardErrorBoundary } from "@/components/syncro/telemetry-card-error-boundary";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import type { TelemetryMachineView } from "@/features/telemetry/types";

type TelemetryGridProps = {
  machines: TelemetryMachineView[];
};

export function TelemetryGrid({ machines }: TelemetryGridProps) {
  return (
    <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {machines.map((machine) => (
        <li key={machine.id ?? machine.code} className="list-none">
          <TelemetryCardErrorBoundary machineCode={machine.code}>
            <MachineTelemetryCard machine={machine} />
          </TelemetryCardErrorBoundary>
        </li>
      ))}
    </ul>
  );
}

export function MachineTelemetryCard({ machine }: { machine: TelemetryMachineView }) {
  const t = useTranslations("telemetry");
  const telemetry = machine.latestTelemetry ?? undefined;
  const freshness = telemetry?.freshnessState;
  const optionalEntries = Object.entries(telemetry?.optionalFields ?? {});

  return (
    <Card aria-label={t("cardAria", { code: machine.code ?? "" })} className="h-full">
      <CardHeader className="gap-2">
        <MachineSummaryCard
          code={machine.code}
          name={machine.name}
          machineGroupName={machine.machineGroupName}
          status={machine.status}
        />
        <div className="flex flex-wrap items-center gap-2">
          {freshness ? <StatusBadge freshness={freshness} /> : <Badge variant="outline">{t("noDataReceived")}</Badge>}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {telemetry ? (
          <TelemetryCard telemetry={telemetry} />
        ) : (
          <p className="text-muted-foreground text-sm">{t("noDataYet")}</p>
        )}
        {optionalEntries.length > 0 ? (
          <dl aria-label={t("optionalFieldsAria")} className="grid gap-1">
            {optionalEntries.map(([key, value]) => (
              <div key={key} className="flex items-center justify-between gap-2 text-xs">
                <dt className="truncate text-muted-foreground">{key}</dt>
                <dd className="truncate font-medium">{value}</dd>
              </div>
            ))}
          </dl>
        ) : null}
      </CardContent>
    </Card>
  );
}
