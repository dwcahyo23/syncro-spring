"use client";

import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";

type MachineSummaryCardProps = {
  code?: string;
  name?: string;
  machineGroupName?: string;
  status?: string;
};

export function MachineSummaryCard({ code, name, machineGroupName, status }: MachineSummaryCardProps) {
  const t = useTranslations("analytics.shared.machineSummary");
  const isActive = status === "ACTIVE";
  const statusLabel = isActive ? t("active") : t("inactive");
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div className="flex min-w-0 items-center justify-between gap-2">
        <p className="truncate font-semibold text-sm" title={name ?? code}>
          {name ?? code ?? t("unknownMachine")}
        </p>
        <Badge aria-label={t("manualStatus", { status: statusLabel })} variant={isActive ? "secondary" : "outline"}>
          {statusLabel}
        </Badge>
      </div>
      <p className="truncate text-muted-foreground text-xs">
        {code}
        {machineGroupName ? ` · ${machineGroupName}` : ""}
      </p>
    </div>
  );
}
