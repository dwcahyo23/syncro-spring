"use client";

import { useFormatter, useTranslations } from "next-intl";

import { Progress } from "@/components/ui/progress";

export interface LifetimeProgressProps {
  baselineCounter: number;
  currentCounterSnapshot: number;
  expectedProductionCount: number;
  consumedProductionCountSnapshot: number;
  consumedPercentageSnapshot: string | number;
  thresholdPercentage: number;
}

export function LifetimeProgress({
  baselineCounter,
  currentCounterSnapshot,
  expectedProductionCount,
  consumedProductionCountSnapshot,
  consumedPercentageSnapshot,
  thresholdPercentage,
}: LifetimeProgressProps) {
  const t = useTranslations("alerts");
  const format = useFormatter();
  const pct = Math.min(Number(consumedPercentageSnapshot), 100);
  const threshold = Math.min(thresholdPercentage, 100);

  return (
    <div className="space-y-3">
      <div className="relative">
        <Progress value={pct} aria-label={t("lifetime.consumedAria", { pct: pct.toFixed(1) })} className="h-3" />
        {/* Threshold marker */}
        <div
          aria-hidden="true"
          className="absolute top-0 h-3 w-0.5 bg-destructive/70"
          style={{ left: `${threshold}%` }}
          title={t("lifetime.thresholdTitle", { pct: String(threshold) })}
        />
      </div>

      <div className="grid grid-cols-2 gap-x-6 gap-y-1.5 text-sm sm:grid-cols-3">
        <div>
          <p className="text-xs text-muted-foreground">{t("lifetime.baselineCounter")}</p>
          <p className="font-medium tabular-nums">{format.number(baselineCounter)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{t("lifetime.currentCounter")}</p>
          <p className="font-medium tabular-nums">{format.number(currentCounterSnapshot)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{t("lifetime.expectedLifetime")}</p>
          <p className="font-medium tabular-nums">{format.number(expectedProductionCount)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{t("lifetime.consumedCount")}</p>
          <p className="font-medium tabular-nums">{format.number(consumedProductionCountSnapshot)}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{t("lifetime.consumedPct")}</p>
          <p className="font-medium tabular-nums">{Number(consumedPercentageSnapshot).toFixed(2)}%</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">{t("lifetime.threshold")}</p>
          <p className="font-medium tabular-nums">{thresholdPercentage}%</p>
        </div>
      </div>
    </div>
  );
}
