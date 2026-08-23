"use client";

import { AlertCircle, CircleCheck, CircleDashed } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { AlertViewStatus } from "@/lib/api/generated/model";

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
    className: "border-transparent bg-amber-500/15 text-amber-700 dark:text-amber-400",
    Icon: AlertCircle,
  },
  ACKNOWLEDGED: {
    label: "Acknowledged",
    description: "Alert has been acknowledged. Escalation is paused.",
    className: "border-transparent bg-blue-500/15 text-blue-700 dark:text-blue-400",
    Icon: CircleDashed,
  },
  RESOLVED: {
    label: "Resolved",
    description: "Alert has been resolved. No further action required.",
    className: "border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400",
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
