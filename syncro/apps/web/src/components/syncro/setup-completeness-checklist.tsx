"use client";

import Link from "next/link";

import { Circle, CircleCheckIcon, type LucideIcon, OctagonXIcon } from "lucide-react";
import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import type { SetupCompletenessResponse } from "@/lib/api/generated/model";

type StepStatus = "COMPLETE" | "INCOMPLETE" | "BLOCKED";

const STEP_ICONS: Record<StepStatus, LucideIcon> = {
  COMPLETE: CircleCheckIcon,
  INCOMPLETE: Circle,
  BLOCKED: OctagonXIcon,
};

const STEP_BADGE_VARIANT: Record<StepStatus, "default" | "secondary" | "destructive"> = {
  COMPLETE: "default",
  INCOMPLETE: "secondary",
  BLOCKED: "destructive",
};

export function SetupCompletenessChecklist({ data, readOnly }: { data: SetupCompletenessResponse; readOnly: boolean }) {
  const t = useTranslations("organization.shared.setupChecklist");
  const steps = data.steps ?? [];
  const overallStatus = data.overallStatus ?? "INCOMPLETE";

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>
          {t("description", { eligible: data.machinesEligibleCount ?? 0, total: data.machineCount ?? 0 })}
        </CardDescription>
        <div className="flex flex-wrap items-center gap-2 pt-2">
          <Badge
            variant={overallStatus === "COMPLETE" ? "default" : "secondary"}
            data-status={overallStatus.toLowerCase()}
          >
            {overallStatus === "COMPLETE" ? t("overallComplete") : t("overallIncomplete")}
          </Badge>
          {readOnly ? <Badge variant="secondary">{t("readOnly")}</Badge> : null}
        </div>
      </CardHeader>
      <CardContent>
        <ul className="divide-y">
          {steps.map((step) => {
            const status = (step.status ?? "INCOMPLETE") as StepStatus;
            // Server data is cast to StepStatus — unknown values must not crash the row.
            const StepIcon = STEP_ICONS[status] ?? STEP_ICONS.INCOMPLETE;
            const statusLabel = t.has(`status.${status}`) ? t(`status.${status}`) : status.replaceAll("_", " ");
            return (
              <li key={step.key} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="flex min-w-0 items-center gap-3">
                  <StepIcon className="size-5 shrink-0" />
                  <div className="min-w-0">
                    <p className="font-medium">{step.label}</p>
                    {step.href && step.nextAction ? (
                      <Link
                        href={step.href}
                        prefetch={false}
                        className="text-muted-foreground text-sm underline-offset-4 hover:underline"
                      >
                        {step.nextAction}
                      </Link>
                    ) : null}
                    {!step.href && step.nextAction ? (
                      <p className="text-muted-foreground text-sm">{step.nextAction}</p>
                    ) : null}
                  </div>
                </div>
                <Badge
                  variant={STEP_BADGE_VARIANT[status] ?? "secondary"}
                  aria-label={t("stepAria", { label: step.label ?? "", status })}
                >
                  {statusLabel}
                </Badge>
              </li>
            );
          })}
        </ul>
      </CardContent>
    </Card>
  );
}
