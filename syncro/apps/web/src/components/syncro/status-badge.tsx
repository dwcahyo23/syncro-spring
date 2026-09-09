"use client";

import { CircleCheck, CircleDashed, CircleSlash } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { TelemetryFreshnessState } from "@/features/telemetry/types";

type StatusBadgeProps = {
  freshness: TelemetryFreshnessState;
};

// Styling map keyed on the raw freshness state; labels/descriptions resolve via
// t(`freshness.${state}.*`).
const FRESHNESS_CONFIG: Record<TelemetryFreshnessState, { className: string; Icon: typeof CircleCheck }> = {
  ONLINE: {
    className: "status-badge-healthy",
    Icon: CircleCheck,
  },
  OFFLINE: {
    className: "status-badge-warning",
    Icon: CircleDashed,
  },
  STALE: {
    className: "border-transparent bg-destructive/15 text-destructive",
    Icon: CircleSlash,
  },
};

export function StatusBadge({ freshness }: StatusBadgeProps) {
  const t = useTranslations("telemetry.statusBadge");
  // freshness is server-derived — an unknown value must not crash the badge.
  const config = FRESHNESS_CONFIG[freshness] ?? FRESHNESS_CONFIG.OFFLINE;
  const label = t.has(`freshness.${freshness}.label`) ? t(`freshness.${freshness}.label`) : freshness;
  const description = t.has(`freshness.${freshness}.description`) ? t(`freshness.${freshness}.description`) : label;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={t("aria", { label, description })}
          className={config.className}
          title={description}
          variant="outline"
        >
          <config.Icon aria-hidden="true" />
          {label}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{description}</TooltipContent>
    </Tooltip>
  );
}
