"use client";

import { Loader2Icon, RefreshCcw } from "lucide-react";

import { StatusBadge } from "@/components/syncro/status-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import type { MachineView, TelemetryDataFreshnessState } from "@/lib/api/generated/model";

export interface MachineHeaderProps {
  machineCode: string;
  machine?: MachineView;
  freshnessState?: TelemetryDataFreshnessState;
  isLoading: boolean;
  onRefresh: () => void;
}

export function MachineHeader({ machineCode, machine, freshnessState, isLoading, onRefresh }: MachineHeaderProps) {
  const status = machine?.status;
  const isInactive = status === "INACTIVE";

  return (
    <div className="rounded-lg border bg-card p-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div className="min-w-0 space-y-2">
          {isLoading && !machine ? (
            <Skeleton className="h-8 w-64" />
          ) : (
            <h1 className="truncate text-2xl font-bold tracking-tight">{machine?.name ?? machineCode}</h1>
          )}
          <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
            <span className="inline-flex items-center rounded-md bg-muted px-2.5 py-0.5 font-medium">
              Code: {machineCode}
            </span>
            {status && (
              <Badge variant={status === "ACTIVE" ? "default" : "secondary"}>
                Manual: {status === "ACTIVE" ? "ACTIVE" : "INACTIVE"}
              </Badge>
            )}
            {freshnessState ? (
              <StatusBadge freshness={freshnessState} />
            ) : (
              !isInactive && status && <Badge variant="outline">Telemetry: No data</Badge>
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
          {isLoading ? <Loader2Icon className="size-4 animate-spin" /> : <RefreshCcw className="size-4" />}
        </Button>
      </div>
      {machine?.plantName && (
        <p className="mt-4 text-sm text-muted-foreground">
          Plant: {machine.plantName}
          {machine.machineGroupName ? ` • Group: ${machine.machineGroupName}` : ""}
          {machine.brand ? ` • Brand: ${machine.brand}` : ""}
        </p>
      )}
    </div>
  );
}
