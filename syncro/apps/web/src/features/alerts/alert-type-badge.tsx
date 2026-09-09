"use client";

import { AlertTriangle, Gauge } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { AlertViewAlertType } from "@/lib/api/generated/model";

export type AlertType = AlertViewAlertType;

type AlertTypeBadgeProps = {
  alertType: AlertType | undefined;
};

const TYPE_CONFIG: Record<string, { className: string; Icon: typeof Gauge }> = {
  THRESHOLD_PERCENTAGE: {
    className: "status-badge-info",
    Icon: Gauge,
  },
  PROCUREMENT_RISK: {
    className: "status-badge-warning",
    Icon: AlertTriangle,
  },
  // Unknown alert types render with this neutral styling (labels resolve at runtime).
  UNKNOWN: {
    className: "border-transparent bg-muted text-muted-foreground",
    Icon: Gauge,
  },
};

export function AlertTypeBadge({ alertType }: AlertTypeBadgeProps) {
  const t = useTranslations("alerts");
  const code = alertType && t.has(`typeBadge.${alertType}.label`) ? alertType : "UNKNOWN";
  const config = TYPE_CONFIG[code] ?? TYPE_CONFIG.UNKNOWN;
  const label = t(`typeBadge.${code}.label`);
  const description = t.has(`typeBadge.${code}.description`) ? t(`typeBadge.${code}.description`) : label;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          aria-label={t("typeBadge.ariaLabel", { label, description })}
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
