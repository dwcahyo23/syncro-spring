"use client";

import Link from "next/link";

import { Circle, CircleCheckIcon, type LucideIcon, OctagonXIcon } from "lucide-react";

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
  const steps = data.steps ?? [];
  const overallStatus = data.overallStatus ?? "INCOMPLETE";

  return (
    <Card>
      <CardHeader>
        <CardTitle>Setup Completeness</CardTitle>
        <CardDescription>
          {data.machinesEligibleCount ?? 0} of {data.machineCount ?? 0} machines in scope are ready for telemetry and
          alerts.
        </CardDescription>
        <div className="flex flex-wrap items-center gap-2 pt-2">
          <Badge
            variant={overallStatus === "COMPLETE" ? "default" : "secondary"}
            data-status={overallStatus.toLowerCase()}
          >
            {overallStatus === "COMPLETE" ? "Complete" : "Incomplete"}
          </Badge>
          {readOnly ? <Badge variant="secondary">Read-only</Badge> : null}
        </div>
      </CardHeader>
      <CardContent>
        <ul className="divide-y">
          {steps.map((step) => {
            const status = (step.status ?? "INCOMPLETE") as StepStatus;
            const StepIcon = STEP_ICONS[status];
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
                <Badge variant={STEP_BADGE_VARIANT[status]} aria-label={`${step.label} ${status}`}>
                  {status.replaceAll("_", " ")}
                </Badge>
              </li>
            );
          })}
        </ul>
      </CardContent>
    </Card>
  );
}
