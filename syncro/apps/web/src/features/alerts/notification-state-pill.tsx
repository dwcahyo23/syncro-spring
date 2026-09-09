"use client";

import { AlertTriangle, CheckCircle, Clock, StopCircle, XCircle } from "lucide-react";
import { useFormatter, useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { NotificationSummary } from "@/lib/api/generated/model";

type NotificationStatePillProps = {
  summary: NotificationSummary | null | undefined;
};

type StatusConfig = {
  Icon: typeof Clock;
  className: string;
};

// Styling map stays keyed on the raw status code (AD-23: colors are not text).
const STATUS_CONFIG: Record<string, StatusConfig> = {
  PENDING: { Icon: Clock, className: "status-badge-neutral" },
  SENT: { Icon: CheckCircle, className: "status-badge-healthy" },
  ESCALATED: { Icon: CheckCircle, className: "status-badge-healthy" },
  ROUTING_FAILED: { Icon: AlertTriangle, className: "status-badge-warning" },
  EXHAUSTED: { Icon: XCircle, className: "status-badge-critical" },
  CANCELLED: { Icon: StopCircle, className: "status-badge-neutral opacity-70" },
};

const FALLBACK_CONFIG: StatusConfig = { Icon: Clock, className: "status-badge-neutral" };

export function NotificationStatePill({ summary }: NotificationStatePillProps) {
  const t = useTranslations("alerts");
  const format = useFormatter();
  if (!summary) {
    return null;
  }

  const status = summary.status ?? undefined;
  // Unknown codes render the raw value as data — never a translated fragment, never a crash.
  const label = status && t.has(`notificationPill.${status}`) ? t(`notificationPill.${status}`) : (status ?? "UNKNOWN");
  const config = STATUS_CONFIG[status ?? ""] ?? FALLBACK_CONFIG;
  let tooltipText: string;
  if (summary.errorDetail) {
    tooltipText = summary.errorDetail;
  } else if (summary.sentAt) {
    tooltipText = t("notificationPill.sentAt", {
      datetime: format.dateTime(new Date(summary.sentAt), { dateStyle: "medium", timeStyle: "short" }),
    });
  } else if (summary.escalationLevel) {
    tooltipText = t("notificationPill.levelLine", { level: summary.escalationLevel });
  } else {
    tooltipText = t("notificationPill.notificationLine", { status: status ?? "UNKNOWN" });
  }

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge aria-label={t("notificationPill.ariaLabel", { label })} className={config.className} variant="outline">
          <config.Icon aria-hidden="true" />
          {label}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{tooltipText}</TooltipContent>
    </Tooltip>
  );
}
