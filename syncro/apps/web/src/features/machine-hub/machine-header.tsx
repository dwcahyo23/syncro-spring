"use client";

import { Loader2Icon, RefreshCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { MachineViewStatus } from "@/lib/api/generated/model";

export interface MachineHeaderProps {
  machineCode: string;
  onRefresh: () => void;
  isLoading: boolean;
}

interface CachedMachineData {
  name?: string;
  status?: MachineViewStatus;
  brand?: string;
  plantName?: string;
  telemetry?: {
    timestamp?: string;
    isOnline?: boolean;
  };
}

const cache = new Map<string, CachedMachineData>();

export function MachineHeader({ machineCode, onRefresh, isLoading }: MachineHeaderProps) {
  const data = useMemo(() => cache.get(machineCode), [machineCode]);

  useEffect(() => {
    if (data && data.telemetry?.timestamp) {
      const fiveMinutesAgo = Date.now() - 5 * 60 * 1000;
      const telemetryTime = new Date(data.telemetry.timestamp).getTime();
      const isOnline = telemetryTime > fiveMinutesAgo;
      cache.set(machineCode, { ...data, telemetry: { ...data.telemetry, isOnline } });
    }
  }, [machineCode, data]);

  const cached = cache.get(machineCode);

  return (
    <div className="rounded-lg border bg-card p-6">
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div className="space-y-2">
          <h1 className="text-2xl font-bold tracking-tight">{cached?.name || machineCode}</h1>
          <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
            <span className="inline-flex items-center rounded-md px-2.5 py-0.5 bg-muted font-medium">
              Code: {machineCode}
            </span>
            {cached?.status && (
              <span className="inline-flex items-center gap-1.5">
                <StatusBadge
                  status={cached.status === "ACTIVE" ? "ONLINE" : "OFFLINE"}
                  variant={cached.status === "ACTIVE" ? "success" : "destructive"}
                />
                Manual Status: {cached.status}
              </span>
            )}
            {cached?.telemetry && (
              <span className="inline-flex items-center gap-1.5">
                <StatusBadge
                  status={cached.telemetry.isOnline ? "ONLINE" : "STALE"}
                  variant={cached.telemetry.isOnline ? "success" : "warning"}
                />
                Telemetry: {cached.telemetry.isOnline ? "Fresh" : "Stale"}
              </span>
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
      {cached?.plantName && (
        <p className="mt-4 text-sm text-muted-foreground">
          Plant: {cached.plantName} • Brand: {cached.brand || "Unknown"}
        </p>
      )}
    </div>
  );
}
