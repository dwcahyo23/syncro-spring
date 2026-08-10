import { CircleCheck, CircleDashed, CircleSlash } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { TelemetryFreshnessState } from "@/features/telemetry/types";

type StatusBadgeProps = {
  freshness: TelemetryFreshnessState;
};

const FRESHNESS_CONFIG: Record<
  TelemetryFreshnessState,
  { label: string; description: string; className: string; Icon: typeof CircleCheck }
> = {
  ONLINE: {
    label: "Online",
    description: "Telemetry received within the last 5 minutes.",
    className: "border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400",
    Icon: CircleCheck,
  },
  OFFLINE: {
    label: "Offline",
    description: "No telemetry for 5-15 minutes. Machine is still ACTIVE.",
    className: "border-transparent bg-amber-500/15 text-amber-700 dark:text-amber-400",
    Icon: CircleDashed,
  },
  STALE: {
    label: "Stale",
    description: "No telemetry for more than 15 minutes.",
    className: "border-transparent bg-destructive/15 text-destructive",
    Icon: CircleSlash,
  },
};

export function StatusBadge({ freshness }: StatusBadgeProps) {
  const config = FRESHNESS_CONFIG[freshness];
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={`Telemetry freshness: ${config.label}. ${config.description}`}
          className={config.className}
          title={config.description}
          variant="outline"
        >
          <config.Icon aria-hidden="true" />
          {config.label}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{config.description}</TooltipContent>
    </Tooltip>
  );
}
