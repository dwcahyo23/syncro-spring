"use client";

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
  const pct = Math.min(Number(consumedPercentageSnapshot), 100);
  const threshold = Math.min(thresholdPercentage, 100);

  return (
    <div className="space-y-3">
      <div className="relative">
        <Progress value={pct} aria-label={`Lifetime consumed: ${pct.toFixed(1)}%`} className="h-3" />
        {/* Threshold marker */}
        <div
          aria-hidden="true"
          className="absolute top-0 h-3 w-0.5 bg-destructive/70"
          style={{ left: `${threshold}%` }}
          title={`Threshold: ${threshold}%`}
        />
      </div>

      <div className="grid grid-cols-2 gap-x-6 gap-y-1.5 text-sm sm:grid-cols-3">
        <div>
          <p className="text-xs text-muted-foreground">Baseline counter</p>
          <p className="font-medium tabular-nums">{baselineCounter.toLocaleString()}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Current counter (at firing)</p>
          <p className="font-medium tabular-nums">{currentCounterSnapshot.toLocaleString()}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Expected lifetime</p>
          <p className="font-medium tabular-nums">{expectedProductionCount.toLocaleString()}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Consumed count</p>
          <p className="font-medium tabular-nums">{consumedProductionCountSnapshot.toLocaleString()}</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Consumed %</p>
          <p className="font-medium tabular-nums">{Number(consumedPercentageSnapshot).toFixed(2)}%</p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">Threshold</p>
          <p className="font-medium tabular-nums">{thresholdPercentage}%</p>
        </div>
      </div>
    </div>
  );
}
