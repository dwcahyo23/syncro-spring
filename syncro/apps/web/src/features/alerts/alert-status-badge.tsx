"use client";

import { AlertCircle, CircleCheck, CircleDashed } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { AlertViewStatus } from "@/lib/api/generated/model";

export type AlertStatus = AlertViewStatus;

type AlertStatusBadgeProps = {
  status: AlertStatus | undefined;
};

const STATUS_CONFIG: Record<AlertStatus, { className: string; Icon: typeof CircleCheck }> = {
  OPEN: {
    className: "status-badge-warning",
    Icon: AlertCircle,
  },
  ACKNOWLEDGED: {
    className: "status-badge-info",
    Icon: CircleDashed,
  },
  RESOLVED: {
    className: "status-badge-healthy",
    Icon: CircleCheck,
  },
};

export function AlertStatusBadge({ status }: AlertStatusBadgeProps) {
  const t = useTranslations("alerts");
  const key = (status ?? "OPEN") as AlertStatus;
  // ponytail: fallback keeps the defensive raw-code label reachable if the enum
  // drifts ahead of STATUS_CONFIG; contract-typed today, so this arm is dead.
  const config = STATUS_CONFIG[key] ?? STATUS_CONFIG.OPEN;
  // Unknown codes (defensive; enum is contract-typed): render the raw code as data.
  const known = t.has(`status.${key}`);
  const label = known ? t(`status.${key}`) : String(key);
  const description = known ? t(`statusDescription.${key}`) : "";
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={t("statusAria", { label, description })}
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
