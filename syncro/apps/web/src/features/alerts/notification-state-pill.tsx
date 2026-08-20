"use client";

import { AlertTriangle, CheckCircle, Clock, StopCircle, XCircle } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { NotificationSummary } from "@/lib/api/generated/model";

type NotificationStatePillProps = {
  summary: NotificationSummary | null | undefined;
};

type StatusConfig = {
  label: string;
  Icon: typeof Clock;
  className: string;
};

function getConfig(status: string): StatusConfig {
  switch (status) {
    case "PENDING":
      return {
        label: "Pending",
        Icon: Clock,
        className: "border-transparent bg-slate-500/15 text-slate-700 dark:text-slate-400",
      };
    case "SENT":
    case "ESCALATED":
      return {
        label: "Sent",
        Icon: CheckCircle,
        className: "border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400",
      };
    case "ROUTING_FAILED":
      return {
        label: "No recipient",
        Icon: AlertTriangle,
        className: "border-transparent bg-amber-500/15 text-amber-700 dark:text-amber-400",
      };
    case "EXHAUSTED":
      return {
        label: "Failed",
        Icon: XCircle,
        className: "border-transparent bg-red-500/15 text-red-700 dark:text-red-400",
      };
    case "CANCELLED":
      return {
        label: "Stopped",
        Icon: StopCircle,
        className: "border-transparent bg-slate-500/15 text-slate-500 dark:text-slate-500",
      };
    default:
      return {
        label: status,
        Icon: Clock,
        className: "border-transparent bg-slate-500/15 text-slate-700 dark:text-slate-400",
      };
  }
}

function formatTooltip(summary: NotificationSummary): string {
  if (summary.errorDetail) {
    return summary.errorDetail;
  }
  if (summary.sentAt) {
    return `Sent at ${new Date(summary.sentAt).toLocaleString()}`;
  }
  if (summary.escalationLevel) {
    return `Level: ${summary.escalationLevel}`;
  }
  return `Notification: ${summary.status}`;
}

export function NotificationStatePill({ summary }: NotificationStatePillProps) {
  if (!summary) {
    return null;
  }

  const config = getConfig(summary.status);
  const tooltipText = formatTooltip(summary);

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={`Notification status: ${config.label}`}
          className={config.className}
          variant="outline"
        >
          <config.Icon aria-hidden="true" />
          {config.label}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{tooltipText}</TooltipContent>
    </Tooltip>
  );
}
