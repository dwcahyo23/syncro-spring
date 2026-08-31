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

function getConfig(status: string | undefined): StatusConfig {
  switch (status) {
    case "PENDING":
      return {
        label: "Pending",
        Icon: Clock,
        className: "status-badge-neutral",
      };
    case "SENT":
    case "ESCALATED":
      return {
        label: "Sent",
        Icon: CheckCircle,
        className: "status-badge-healthy",
      };
    case "ROUTING_FAILED":
      return {
        label: "No recipient",
        Icon: AlertTriangle,
        className: "status-badge-warning",
      };
    case "EXHAUSTED":
      return {
        label: "Failed",
        Icon: XCircle,
        className: "status-badge-critical",
      };
    case "CANCELLED":
      return {
        label: "Stopped",
        Icon: StopCircle,
        className: "status-badge-neutral opacity-70",
      };
    default:
      return {
        label: status ?? "UNKNOWN",
        Icon: Clock,
        className: "status-badge-neutral",
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
  return `Notification: ${summary.status ?? "UNKNOWN"}`;
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
