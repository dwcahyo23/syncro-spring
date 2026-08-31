"use client";

import { AlertTriangle, Gauge } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { AlertViewAlertType } from "@/lib/api/generated/model";

export type AlertType = AlertViewAlertType;

type AlertTypeBadgeProps = {
  alertType: AlertType | undefined;
};

const TYPE_CONFIG: Partial<
  Record<AlertType, { label: string; description: string; className: string; Icon: typeof Gauge }>
> = {
  THRESHOLD_PERCENTAGE: {
    label: "Threshold",
    description: "Consumed lifetime reached the configured percentage threshold.",
    className: "status-badge-info",
    Icon: Gauge,
  },
  PROCUREMENT_RISK: {
    label: "Procurement risk",
    description: "Projected depletion falls within the sparepart lead-time window.",
    className: "status-badge-warning",
    Icon: AlertTriangle,
  },
};

const UNKNOWN_CONFIG = {
  label: "Unknown",
  description: "Alert type is not recognized.",
  className: "border-transparent bg-muted text-muted-foreground",
  Icon: Gauge,
};

export function AlertTypeBadge({ alertType }: AlertTypeBadgeProps) {
  const config = alertType ? (TYPE_CONFIG[alertType] ?? UNKNOWN_CONFIG) : UNKNOWN_CONFIG;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={`Alert type: ${config.label}. ${config.description}`}
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
