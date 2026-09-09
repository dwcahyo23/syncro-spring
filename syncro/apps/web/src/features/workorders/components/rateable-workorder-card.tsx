"use client";

import { useTranslations } from "next-intl";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { RatingPanel } from "@/features/workorders/components/rating-panel";
import type { RateableWorkorderView } from "@/features/workorders/types";

export interface RateableWorkorderCardProps {
  workorder: RateableWorkorderView;
}

/**
 * Card for one CLOSED workorder on the ratings page (story 10-8). Shows the workorder
 * summary and its star-rating panel. Scope/role permissions are enforced server-side;
 * the panel only renders the actions the current role can submit.
 */
export function RateableWorkorderCard({ workorder }: RateableWorkorderCardProps) {
  const t = useTranslations("workOrders");
  return (
    <Card className="shadow-sm">
      <CardHeader className="space-y-1 p-4">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="font-medium text-sm leading-tight">{workorder.id}</CardTitle>
          {workorder.categoryCode ? <Badge variant="outline">{workorder.categoryCode}</Badge> : null}
        </div>
        <p className="line-clamp-2 text-muted-foreground text-xs">{workorder.description ?? t("card.noDescription")}</p>
        {workorder.assignedTechnicianId ? (
          <p className="text-muted-foreground text-xs">
            {t("ratings.assignedTechnician")} <span className="font-mono">{workorder.assignedTechnicianId}</span>
          </p>
        ) : null}
      </CardHeader>
      <CardContent className="p-4 pt-0">
        <RatingPanel workorderId={workorder.id} executorPool={workorder.executorPool} />
      </CardContent>
    </Card>
  );
}
