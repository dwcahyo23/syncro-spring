"use client";

import { AlertCircle, CircleCheck, CircleDashed } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { AlertViewStatus } from "@/lib/api/generated/model";

export type AlertStatus = AlertViewStatus;

type AlertStatusBadgeProps = {
  status: AlertStatus | undefined;
};

const STATUS_CONFIG: Record<
  AlertStatus,
  { label: string; description: string; className: string; Icon: typeof CircleCheck }
> = {
  OPEN: {
    label: "Open",
    description: "Alert is open and requires attention.",
    className: "status-badge-warning",
    Icon: AlertCircle,
  },
  ACKNOWLEDGED: {
    label: "Acknowledged",
    description: "Alert has been acknowledged. Escalation is paused.",
    className: "status-badge-info",
    Icon: CircleDashed,
  },
  RESOLVED: {
    label: "Resolved",
    description: "Alert has been resolved. No further action required.",
    className: "status-badge-healthy",
    Icon: CircleCheck,
  },
};

export function AlertStatusBadge({ status }: AlertStatusBadgeProps) {
  const config = STATUS_CONFIG[(status ?? "OPEN") as AlertStatus];
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={`Alert status: ${config.label}. ${config.description}`}
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
