"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { RateableWorkorderCard } from "@/features/workorders/components/rateable-workorder-card";
import { useRateableWorkorders } from "@/features/workorders/hooks/use-ratings";

/**
 * Ratings page content (story 10-8). Lists the CLOSED workorders the current user can
 * rate (scope-filtered by the backend), each with a star-rating panel. Loading/empty/
 * error states are required (project rule).
 */
export function RatingsPageContent() {
  const t = useTranslations("workOrders");
  const tc = useTranslations("common");
  const { data, isLoading, isError, refetch } = useRateableWorkorders();

  if (isLoading) {
    return (
      <div className="grid gap-4 md:grid-cols-2">
        {[0, 1, 2].map((index) => (
          <div key={index} className="space-y-3 rounded-lg border p-4">
            <Skeleton className="h-5 w-32" />
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("ratings.loadFailed")}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => void refetch()}>
          {tc("retry")}
        </Button>
      </div>
    );
  }

  const workorders = data ?? [];
  if (workorders.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 rounded-lg border p-6">
        <p className="text-muted-foreground text-sm">{t("ratings.empty")}</p>
      </div>
    );
  }

  return (
    <div className="grid gap-4 md:grid-cols-2">
      {workorders.map((workorder) => (
        <RateableWorkorderCard key={workorder.id} workorder={workorder} />
      ))}
    </div>
  );
}
