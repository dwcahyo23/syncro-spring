"use client";

import { Loader2Icon, RefreshCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { MachineViewStatus, TelemetryData } from "@/lib/api/generated/model";
import { StatusBadge } from "@/components/syncro/status-badge";
import { Skeleton } from "@/components/ui/skeleton";

export interface MachineHeaderProps {
  machineCode: string;
  onRefresh: () => void;
  isLoading: boolean;
  machineStatus?: MachineViewStatus;
  freshnessState?: "ONLINE" | "OFFLINE" | "STALE";
}

export function MachineHeader({
  machineCode,
  onRefresh,
  isLoading,
  machineStatus,
  freshnessState,
}: MachineHeaderProps) {
  return (
    <div className="rounded-lg border bg-card p-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div className="space-y-2">
          {isLoading ? (
            <Skeleton className="h-8 w-[200px]" />
          ) : (
            <h1 className="text-2xl font-bold tracking-tight">{machineCode}</h1>
          )}
          <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
            {!isLoading && (
              <>
                <span className="inline-flex items-center rounded-md px-2.5 py-0.5 bg-muted font-medium">
                  Code: {machineCode}
                </span>
                {machineStatus && (
                  <span className="inline-flex items-center gap-1.5">
                    <StatusBadge
                      status={machineStatus === "ACTIVE" ? "ONLINE" : "OFFLINE"}
                      variant={machineStatus === "ACTIVE" ? "success" : "destructive"}
                    />
                    Manual Status: {machineStatus}
                  </span>
                )}
                {(freshnessState === "ONLINE" || freshnessState === "OFFLINE" || freshnessState === "STALE") && (
                  <span className="inline-flex items-center gap-1.5">
                    <StatusBadge status={freshnessState} variant="outline" />
                    Telemetry Freshness
                  </span>
                )}
                {!machineStatus && !freshnessState && (
                  <span className="text-muted-foreground italic">No data available</span>
                )}
              </>
            )}
          </div>
        </div>
        <Button
          onClick={onRefresh}
          disabled={isLoading}
          variant="outline"
          size="icon"
          className="shrink-0"
          aria-label="Refresh machine data"
        >
          {isLoading ? (
            <Loader2Icon className="size-4 animate-spin" />
          ) : (
            <RefreshCcw className="size-4" />
          )}
        </Button>
      </div>
    </div>
  );
}
